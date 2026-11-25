# F-20261119-07 Tasks: generic add-phone, owner notice, reset side effect (AC-9..AC-12)

Class L, about 140 production lines. Order is dependency order and also deploy order: notification-service first (CTO condition 7).
Auth paths are under `services/auth-service/auth-service-app/src/main/java/com/acme/ecommerce/auth/`. Tests mirror them under `src/test/java/...`.
Gradle command prefix: `bash .openforge/scripts/gradle.sh` (one build at a time; check `gradle_rc=0`).
Never edit `docs/techdebt/phase6.md`, `docs/techdebt/README.md` or `docs/techdebt/remediation-plan.md` (CTO condition 6).

## T1. notification-service: two new email templates (deploys first)
- Owner: forge-backend-engineer
- Goal: add `PHONE_CLAIM_ATTEMPT` and `PASSWORD_SET` to `EmailTemplate`, and two `case`s in the exhaustive `EmailRenderer` switch. Phone-claim copy per design C5 (last 4 digits, names neither account nor attacker). Both render via `EmailRenderer.layout` and HTML-escape `name`, `phoneTail` and `link`.
- Files: `services/notification-service/notification-service-api/.../dto/EmailTemplate.java`, `services/notification-service/notification-service-app/.../mail/EmailRenderer.java`, test `.../mail/EmailRendererTest.java`.
- ACs: AC-10, AC-12. CTO: 5, 7.
- Tests (included): `EmailRendererTest` renders both templates, asserts key copy, asserts escaping of `<script>` in name.
- Verify: `bash .openforge/scripts/gradle.sh :services:notification-service:notification-service-app:test`

## T2. auth config, error-code deprecation and env doc
- Owner: forge-backend-engineer
- Goal: mark `AUTH_PHONE_TAKEN` `@Deprecated(forRemoval = true)` with a "no longer emitted" comment (stays in enum). Add `auth.phone.claim-notice-window: ${AUTH_PHONE_CLAIM_NOTICE_WINDOW:24h}` to `application.yml`. Add the variable to `docs/reference/environment-variables.md`.
- Files: `services/auth-service/auth-service-api/.../AuthErrorCode.java`, `services/auth-service/auth-service-app/src/main/resources/application.yml`, `docs/reference/environment-variables.md`.
- ACs: AC-9 (contract). CTO: 1.
- Tests (included): compile check only (no behaviour); the existing `AuthErrorCode` and problem-handler tests must still pass. Add a one-line assertion in an existing error-code test that the constant still exists and is deprecated, if such a test class exists; otherwise rely on the full suite.
- Verify: `bash .openforge/scripts/gradle.sh :services:auth-service:auth-service-app:test`

## T3. PhoneClaimNotifier (SET NX once per owner per window, fail-closed, non-throwing)
- Owner: forge-backend-engineer
- Goal: new `service/PhoneClaimNotifier` (about 70 lines). Virtual-thread executor like `NotificationGateway`. `attempted(String e164)` resolves the owner via `findWithRolesByPhoneAndPhoneVerifiedTrue`, runs `setIfAbsent("auth:phone-claim-notice:" + ownerId, "1", window)`, and on true sends `PHONE_CLAIM_ATTEMPT` by email to `owner.email` with `{name, phoneTail, link}`. Redis failure: skip the notice, WARN throttled to one per 30 s, never throws. Window read via `@Value("${auth.phone.claim-notice-window...}")`. No phone number stored in Redis.
- Files: `service/PhoneClaimNotifier.java`, test `service/PhoneClaimNotifierRedisTest.java`.
- ACs: AC-10. CTO: 2.
- Tests (included, inline Testcontainers `GenericContainer("redis:7-alpine")`, class-local): 20 concurrent `attempted` calls give exactly one `sendEmail`; TTL about 24 h; after key deletion a notice is sent again; no owner means no email; closed port means no throw, no email, one WARN.
- Verify: `bash .openforge/scripts/gradle.sh :services:auth-service:auth-service-app:test`

## T4. Generic add-phone and generic verify race
- Owner: forge-backend-engineer
- Goal: in `PhoneVerificationService.addPhone` store `taken` instead of throwing at :56-58. Run identical `changePhone`, `issueCode` (same 30 s cooldown) and the same early return when A already owns P verified. Free: `sendSms` (unchanged). Taken: `claimNotifier.attempted(P)` and no SMS. In `verify`, the in-tx re-check (:89-92) and the `DataIntegrityViolationException` mapping (:95-96) both throw `AUTH_OTP_INVALID`; keep the re-check and the unique index; log INFO `phone verify refused: verified elsewhere userId=`. Controller status and body unchanged (202 empty, 204).
- Files: `service/PhoneVerificationService.java`, tests `service/PhoneVerificationServiceTest.java` (unit) and `web/PhoneEnumerationIntegrationTest.java` (AbstractAuthIntegrationTest, `@MockitoBean NotificationGateway`, `@MockitoBean PhoneClaimNotifier`).
- ACs: AC-9, AC-11. CTO: 1, 3.
- Tests (included): taken vs free add give identical status, body and headers (ignoring Date and correlation id); `sendSms` never called for a taken P; `attempted(P)` called once; resend within 30 s gives 429 `AUTH_RATE_LIMITED` for both; decoy code via `issueCode(..., Duration.ZERO)` gives 401 `AUTH_OTP_INVALID` and `phone_verified=false`; race (A and B add free P, B verifies 204, A gets 401 `AUTH_OTP_INVALID`, existing re-check and unique index tests kept); free P happy path 202, SMS, 204, verified (AC-11); unit test: `DataIntegrityViolationException` maps to `AUTH_OTP_INVALID`; no `AUTH_PHONE_TAKEN` on add, resend or verify.
- Verify: `bash .openforge/scripts/gradle.sh :services:auth-service:auth-service-app:test`

## T5. Password reset: PASSWORD_SET email when a credential is created (AC-12 b)
- Owner: forge-backend-engineer
- Goal: in `PasswordResetService.reset`, the tx lambda records whether it created the `UserCredential` (the `orElse` branch at :113). After commit, if created, `notifications.sendEmail(user.email, PASSWORD_SET, {name, link: /account/security})`, fail-open. Behaviour otherwise unchanged (204, row created, sessions revoked).
- Files: `service/PasswordResetService.java`, test `web/PasswordResetIntegrationTest.java`.
- ACs: AC-12. CTO: 5.
- Tests (included): Google-only user, reset gives 204, `user_credentials` row exists, `sendEmail(email, PASSWORD_SET, ...)` once and only after commit; password user gives no `PASSWORD_SET`; a failing gateway does not fail the reset.
- Verify: `bash .openforge/scripts/gradle.sh :services:auth-service:auth-service-app:test`

## T6. Storefront neutral hint
- Owner: forge-frontend-engineer
- Goal: add the neutral sentence "If the code does not arrive, check the number." to the hint in `PhoneSection.tsx` (about :76). It must not describe the rule (no "already verified" wording). No `errors.ts` change.
- Files: `apps/product-catalog/src/components/account/PhoneSection.tsx`, its existing test (extend or add `PhoneSection.test.tsx`).
- ACs: AC-9 (UX side). CTO: 4.
- Tests (included): renders the hint text once the code field is shown; asserts the hint contains no "already" or "in use" wording; verify 401 `AUTH_OTP_INVALID` shows the existing message.
- Verify: `cd apps/product-catalog && npx vitest run && npx tsc --noEmit`

## T7. Docs and ADR 0007 (Accepted)
- Owner: forge-backend-engineer
- Goal: update `docs/reference/api.md` (add-phone contract: 202 for taken and free, resend 429, verify 401 `AUTH_OTP_INVALID`; reset creates credential and sends `PASSWORD_SET`), `docs/services/auth-service.md` (generic add, owner notice, reset side effect), and ADR 0007 status Accepted. ADR states: code is consumed before the in-tx re-check (condition 3); AC-12 (b) and why (a) was rejected; deploy notification-service first and the mixed-deploy effect (condition 7); rollback. Do not touch `docs/techdebt/*` (condition 6).
- Files: `docs/reference/api.md`, `docs/services/auth-service.md`, `docs/adr/0007-*.md`.
- ACs: AC-9, AC-10, AC-12. CTO: 3, 6, 7.
- Tests (included): doc checks by grep, no code. `git diff --stat -- docs/techdebt` shows no change.
- Verify: `grep -c "AUTH_PHONE_TAKEN" docs/reference/api.md` (only the deprecation note remains), `grep -n "Accepted" docs/adr/0007-*.md`, `git diff --stat -- docs/techdebt` (empty)

## T8. Final verification
- Owner: forge-backend-engineer
- Goal: run the full gate on the worktree and record the output. Run notification-service tests, then auth-service tests, then the storefront checks, then the forge-verify gate. No code changes unless a failure needs a fix.
- Files: none expected.
- ACs: AC-9, AC-10, AC-11, AC-12. CTO: 1-7 (regression).
- Verify: `bash .openforge/scripts/gradle.sh :services:notification-service:notification-service-app:test`, then `bash .openforge/scripts/gradle.sh :services:auth-service:auth-service-app:test`, then `cd apps/product-catalog && npx vitest run && npx tsc --noEmit`, then `node .claude/hooks/forge-verify.mjs --changed`

## Coverage table

| AC | Tasks |
|---|---|
| AC-9 | T2, T4, T6, T7, T8 |
| AC-10 | T1, T3, T7, T8 |
| AC-11 | T4, T8 |
| AC-12 | T1, T5, T7, T8 |

| CTO condition | Tasks |
|---|---|
| 1. Same queries, cooldown, 202 empty body; tests compare status, body, headers, 429 | T2, T4 |
| 2. Email SET NX once per 24 h; Redis down skips; non-throwing, throttled WARN | T3 |
| 3. AUTH_OTP_INVALID on race; re-check and index kept and tested; ADR says code consumed first | T4, T7 |
| 4. Neutral storefront hint | T6 |
| 5. AC-12(b) PASSWORD_SET after commit, fail-open; test row and email | T1, T5 |
| 6. No TD-P6-20 edits to phase6.md, README.md, remediation-plan.md | T7 (guard), T8 |
| 7. Deploy notification-service first; ADR 0007 Accepted | T1 (first), T7 |

Gaps: none.
