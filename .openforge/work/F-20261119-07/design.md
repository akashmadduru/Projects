# F-20261119-07 Design: generic add-phone, owner notice, reset side effect (AC-9..AC-12). Class L. ADR 0007.
Paths are under `services/auth-service/auth-service-app/src/main/java/com/acme/ecommerce/auth/` unless stated. Line refs are to the F-07 worktree.

## 1. Current behaviour (proof)
- Add: `addPhone` normalises (service/PhoneVerificationService.java:50-55), then throws 409 `AUTH_PHONE_TAKEN` if P is verified on another account (:56-58). Otherwise it stores P unverified on A (:59-65; `changePhone` clears verification, domain/UserAccount.java:144-148), issues a code with a 30 s per-user cooldown (:69-73; OneTimeTokenService.java:105-119), and sends it by SMS (:74-75). Controller answers 202 with no body (web/AuthController.java:272-277). "Resend" is the same call (storefront PhoneSection.tsx:120 calls `sendCode`).
- Verify: wrong code gives 401 `AUTH_OTP_INVALID` (:82-85; AuthErrorCode.java:95). A correct code is consumed in its own transaction first (OneTimeTokenService.java:127-151). Then the in-tx re-check throws `AUTH_PHONE_TAKEN` (:89-92), and a lost race on the partial unique index `users_phone_verified_key` (V2__phase6_tokens_phone_otp.sql:12) is mapped to `AUTH_PHONE_TAKEN` (:95-96). Success gives 204 (AuthController.java:280-285).
- Problem body: detail = title = "Phone number already in use" (AuthProblemHandler.java:69-81; AuthErrorCode.java:101).
- **Client on 409 today:** `errors.ts` has no `AUTH_PHONE_TAKEN` entry (lib/errors.ts:17-56), so `errorMessage` shows `problem.detail` (:64). On add, PhoneSection shows "Phone number already in use" and stays on the number field (`codeSent` not set, PhoneSection.tsx:29-35). On verify it shows the same text and keeps the code field (:43-51). The admin app has no reference (grep `PHONE_TAKEN` in apps/: only these paths). No other repo caller.
- Notification path: `NotificationGateway.sendEmail/sendSms` is async and fail-open (service/NotificationGateway.java:15-57). Every account has an email: `email` is `nullable = false` (UserAccount.java:46). Templates are an enum (notification-service-api `EmailTemplate.java`) rendered by an exhaustive switch (notification-service-app `mail/EmailRenderer.java:19-217`).
- Reset: `reset` creates `UserCredential` when none exists (PasswordResetService.java:109-113), marks email verified (:114), revokes sessions (:116), and sends no email. Reset is the **only** way to set a password: no change-password endpoint exists (grep `changePassword` finds only UserCredential.java:132 and PasswordResetService.java:112).
- Redis: `StringRedisTemplate` is used fail-open by UserSessionCache (cache/UserSessionCache.java:46-53); host/port at application.yml:79-82, no command timeout (Lettuce default 60 s).

## 2. Proposed change
**C1. Generic add (PhoneVerificationService.addPhone).** Keep the same query at :56 but store the boolean `taken` instead of throwing. Then run the identical steps for both cases: `changePhone`, `issueCode` with the same cooldown (so a resend inside 30 s gives the same 429 for taken and free numbers), and the same early return when A already owns P verified. Only the last step differs:
- free: `notifications.sendSms(P, code)` (unchanged);
- taken: `claimNotifier.attempted(P)`. No SMS goes to P, and A never receives the code. Both branches only submit a task to an executor, so the request path does the same DB work and the same constant-time hand-off.

**C2. Generic verify race (:89-96).** Both throws become `AUTH_OTP_INVALID`, the same exception a wrong code gets. Safety is unchanged: the in-tx re-check and the partial unique index still refuse the second verification. Only the error code changes. A code issued under C1's taken branch was never delivered; if guessed (5 tries of 10^6), the same re-check refuses it. Log at INFO `phone verify refused: verified elsewhere userId=`.

**C3. `service/PhoneClaimNotifier` (new, about 70 lines).** It owns a virtual-thread executor (same pattern as NotificationGateway.java:29, :59-62). `attempted(String e164)` submits a task that:
1. resolves the owner with the existing `findWithRolesByPhoneAndPhoneVerifiedTrue` (UserAccountRepository.java:52); if absent, return;
2. runs `redis.opsForValue().setIfAbsent("auth:phone-claim-notice:" + ownerId, "1", window)`, which is atomic SET NX PX. Concurrent attempts therefore yield exactly one `true` per owner per window;
3. if true, `notifications.sendEmail(owner.email, PHONE_CLAIM_ATTEMPT, {name, phoneTail: last 4 digits, link: storefront /account/security})`.
- **Redis down: skip the notice** (fail-closed for the notice only), with a WARN throttled to one every 30 s. Never throws. AC-10's "at most one" is a hard bound; a missed notice is low harm because the attack already failed, while an unlimited one is an email-spam lever. The task is async, so the 60 s default timeout never reaches the request.
- Channel: **email**, not SMS. Email always exists (UserAccount.java:46) and costs nothing. An SMS to P would let any signed-in user trigger paid SMS to registered numbers (SMS pumping). The key uses the owner UUID, so no phone number is stored in Redis.
- Config: `auth.phone.claim-notice-window: ${AUTH_PHONE_CLAIM_NOTICE_WINDOW:24h}`, read with `@Value`, as OneTimeTokenService.java:64 does.

**C4. AC-12 choice: (b) document and notify.** In `reset`, the tx lambda records whether it created the row (the `orElse` branch at :113). After commit, if it did: `notifications.sendEmail(user.email, PASSWORD_SET, {name, link: /account/security})`. Behaviour is otherwise unchanged and documented. Why (b): (a) would leave Google-only users with no way to ever get a password (no other endpoint, section 1), and it adds no protection, because whoever controls the mailbox can already take over password accounts through the same reset. The email gives the real owner a signal and a link.

**C5. notification-service.** Add `PHONE_CLAIM_ATTEMPT` and `PASSWORD_SET` to `EmailTemplate`, and two `case`s in `EmailRenderer` (required: the switch is exhaustive). The phone-claim copy says "Someone tried to add your number ending NNNN to another account. It is still only on yours; no action needed unless this keeps happening." It names neither the other account nor the attacker.

**C6. API contract.** `AUTH_PHONE_TAKEN` gets `@Deprecated(forRemoval = true)` and is "no longer emitted". It stays in the enum, so auth-service-api consumers still compile.
| Call | Old (taken P) | New (taken P) = free P |
|---|---|---|
| POST /api/auth/me/phone | 409 problem `AUTH_PHONE_TAKEN` | 202, empty body; no SMS; owner email (max 1/24 h) |
| same, again within 30 s | 409 | 429 `AUTH_RATE_LIMITED` (cooldown, same as free) |
| POST /me/phone/verify, lost race or guessed decoy | 409 `AUTH_PHONE_TAKEN` | 401 `AUTH_OTP_INVALID` (same as wrong code) |
| POST /password/reset, Google-only | 204, credential row created, no email | 204, row created, `PASSWORD_SET` email |

**C7. Storefront (PhoneSection.tsx:76 hint, +2 lines).** The flow works unchanged: taken P shows the code field, and verify shows `AUTH_OTP_INVALID` text (errors.ts:39). An honest user who typed the wrong number no longer sees "already in use". To cover that, the hint gains the neutral sentence "If the code does not arrive, check the number." It does not describe the rule, and it is the same for every number, so it is no oracle. No errors.ts change, since there was no entry to remove.

## 3. AC mapping
- AC-9: C1 and C2. Same status, empty body and headers on add and resend; no `AUTH_PHONE_TAKEN` on any path; `sendSms` is never called for a taken P.
- AC-10: C3. One `PHONE_CLAIM_ATTEMPT` email to the owner; SET NX with a 24 h TTL caps it at one per owner per window.
- AC-11: the C1 free branch and the verify happy path are untouched: 202, SMS, 204, `phone_verified=true`.
- AC-12: C4 and C5, option (b). The credential row is created and a `PASSWORD_SET` email goes to the account address. Documented in api.md, auth-service.md and ADR 0007.

## 4. Reuse and alternatives
- **Reused:** the `PhoneNumbers.normalise` key form; both existing repository queries; `NotificationGateway.sendEmail`; the `StringRedisTemplate` pattern (UserSessionCache); the LoginAlertService security-email pattern (LoginAlertService.java:69-77); `EmailRenderer.layout`; the `FixedTimeProvider`/MockitoBean test style.
- **Not reused:** F-06 `SharedWindowCounter`. It is not on this branch (parts are disjoint, objective.md:5), and a counter is the wrong primitive for a one-shot flag; SET NX is a single atomic built-in. Gateway `RateLimitWebFilter` is reactive. No shared lib is warranted.
- **Rejected:**
  - Taken branch skips `issueCode`: the 30 s cooldown 429 would then differ between taken and free numbers, which is an oracle.
  - SMS notice to P: cost and a pumping vector.
  - Return 204 on the verify race without verifying: the UI would say "verified" while /me says not. A lie is worse than "code invalid".
  - AC-12 (a): see C4.
  - Fail-open notice on Redis outage: unbounded email.

## 5. Risks, rollback, tests
- **Honest-typo UX:** no "already in use" message (C7 hint mitigates). Support can still see ownership. Accepted by the AC.
- **Mixed deploy:** an old notification-service rejects the unknown enum value (Jackson 400). NotificationGateway logs a WARN and the notice is lost, never an error. **Deploy notification-service first.**
- **Notice DoS on the owner:** at most 1 email per 24 h per owner. The attacker spends one account cooldown per try.
- **SMS cost on add-phone:** this change cuts it (no SMS for a taken P). The per-user cooldown is otherwise unchanged; the caps are F-06 scope.
- **Timing:** same queries on both branches. Residual: the executor task differs off-thread only.
- **Rollback:** revert both images. Keys expire within 24 h. No migration.
- **Tests** (`bash .openforge/scripts/gradle.sh :services:auth-service:auth-service-app:test`, then the same for notification-service):
  - `PhoneEnumerationIntegrationTest` (AbstractAuthIntegrationTest, `@MockitoBean NotificationGateway`, `@MockitoBean PhoneClaimNotifier`): add taken and free P, assert identical status, body and headers (ignoring correlation/Date), `sendSms` never for a taken P, `attempted(P)` once; resend in 30 s gives 429 in both cases; a decoy code fetched via `issueCode(..., Duration.ZERO)` gives 401 `AUTH_OTP_INVALID` and `phone_verified=false` (AC-9). Race: A and B add free P, B verifies 204, A gets 401 `AUTH_OTP_INVALID` (C2). Free P: 202, SMS, 204, verified (AC-11).
  - `PhoneVerificationServiceTest` (unit): `DataIntegrityViolationException` maps to `AUTH_OTP_INVALID`.
  - `PhoneClaimNotifierRedisTest`: inline Testcontainers `GenericContainer("redis:7-alpine")`, class-local so it cannot clash with F-06's support class. 20 concurrent `attempted` calls give exactly one `sendEmail`; TTL is about 24 h; after the key is deleted a notice is sent again. Closed port: no throw, no email, WARN (AC-10).
  - `PasswordResetIntegrationTest`: Google-only user, reset 204, `user_credentials` row exists, `sendEmail(email, PASSWORD_SET, ...)` once. Password user: no `PASSWORD_SET` (AC-12).
  - `EmailRendererTest`: both new templates render and escape.
  - Storefront: `npx tsc --noEmit` and `npx vitest run`.

## 6. Files (estimate about 140 production lines, about 320 test lines)
- auth: `service/PhoneVerificationService.java` (+12/-8), new `service/PhoneClaimNotifier.java` (~70), `service/PasswordResetService.java` (+12), `auth-service-api/.../AuthErrorCode.java` (+2), `application.yml` (+3).
- notification: `notification-service-api/.../dto/EmailTemplate.java` (+4), `notification-service-app/.../mail/EmailRenderer.java` (+22).
- storefront: `apps/product-catalog/src/components/account/PhoneSection.tsx` (+2).
- Docs: `docs/reference/api.md` (add-phone contract, reset behaviour), `docs/services/auth-service.md`, `docs/reference/environment-variables.md` (1 var).

## CTO decision
Recorded by forge-cto on 2026-11-20 in its reply and transcribed verbatim by forge-ceo. This is class L: under charter R-20261119-01, CTO acceptance counts as standing Owner approval (see the complexity.md line).

ACCEPT, with conditions.

1. Add-phone: same queries, same cooldown, 202 with an empty body for taken and free numbers. The remaining difference is off-thread only. Tests must compare status, body and headers, and show the same 429 on resend.
2. Owner notice: email with SET NX once per 24 h is right. Skipping when Redis is down is acceptable, because the bound on email volume matters more than one missed notice. Keep it non-throwing, with the WARN throttled.
3. Verify race: AUTH_OTP_INVALID is accepted. The re-check and the unique index must stay and keep their tests. Say in the ADR that the code is consumed before the re-check.
4. Storefront hint: reword it so it does not describe the rule ("only numbers not already verified…"). Use neutral copy such as "If the code does not arrive, check the number."
5. AC-12(b): accepted. PASSWORD_SET is sent after commit and fails open. Test both the DB row and the email.
6. Register: accepted. Remove the TD-P6-20 edits to phase6.md, README.md and remediation-plan.md from section 6. The API docs and ADR 0007 meet AC-12(b).
7. Deploy notification-service first. Mark ADR 0007 Accepted.
