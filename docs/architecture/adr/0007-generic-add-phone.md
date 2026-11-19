# ADR 0007: Add-phone answers the same for taken and free numbers; the owner gets one email a day; reset keeps creating a credential but says so

Status: accepted, 2026-11-20 (CTO, class L, standing Owner approval R-20261119-01). Work id F-20261119-07
(TD-P6-20).

## Context
- Add-phone threw 409 `AUTH_PHONE_TAKEN` when the number was verified on another account, and so did the
  verify race. Any signed-in user could test whether a number is registered.
- Uniqueness of verified numbers is enforced by the partial unique index `users_phone_verified_key`
  (`V2__phase6_tokens_phone_otp.sql`), not by the 409.
- Every account has an email. Redis is already used fail-open (`UserSessionCache`).
- Password reset is the only way to set a password, and it creates a credential for Google-only users
  without telling them.

## Decision
- **Generic add.** Taken and free numbers run the same steps: store unverified, issue the code with the same
  30 s cooldown, return 202 with an empty body. Only the last step differs: a free number gets the SMS; a
  taken number gets no SMS and hands the owner notice to `PhoneClaimNotifier` asynchronously. A resend
  inside 30 s is the same 429 `AUTH_RATE_LIMITED` for both.
- **Generic verify race.** A lost race or a guessed decoy code returns 401 `AUTH_OTP_INVALID`, the same as a
  wrong code. The code is consumed in its own transaction **before** the in-transaction re-check
  (`OneTimeTokenService.verifyCode`), so a refused code cannot be replayed. The DB re-check and the unique
  index remain the safety and keep their tests. `AUTH_PHONE_TAKEN` is `@Deprecated(forRemoval = true)`,
  stays in the enum, and is no longer emitted.
- **Owner notice.** The owner gets an email (`PHONE_CLAIM_ATTEMPT`, last 4 digits only) through
  `NotificationGateway`, limited to one per owner per `AUTH_PHONE_CLAIM_NOTICE_WINDOW` (default 24 h) by
  Redis `SET NX PX` on `auth:phone-claim-notice:{ownerId}` (no phone number in Redis). If Redis is
  unavailable the notice is skipped (fail-closed for the notice only; WARN throttled to one per 30 s).
- **Reset (AC-12 option b).** Keep creating the credential. When the reset created it, send a
  `PASSWORD_SET` email to the account address after commit (fail-open). Documented in `api.md` and
  `auth-service.md`.

## Alternatives considered
- **Skip issuing a code for taken numbers:** the cooldown 429 then differs between taken and free numbers,
  an oracle. Rejected.
- **SMS notice to the number:** costs money and lets users trigger paid SMS to registered numbers. Rejected.
- **Fail-open notice when Redis is down:** unbounded email to the owner. Rejected.
- **AC-12 (a), gate the reset for Google-only users:** removes their only way to get a password and adds no
  protection, because mailbox control already allows a reset on password accounts. Rejected.
- **204 on the verify race without verifying:** the UI would say "verified" while the profile says not.
  Rejected.
- **Reuse F-06 `SharedWindowCounter`:** not on this branch, and a counter is the wrong primitive for a
  one-shot flag. Rejected.

## Consequences
- An honest user who mistypes a number sees no "already in use" message. The storefront hint says "If the
  code does not arrive, check the number." without describing the rule.
- **Deploy order: notification-service first, then auth-service.** An old notification-service rejects the
  unknown template names (HTTP 400). If auth deploys first, `PHONE_CLAIM_ATTEMPT` and `PASSWORD_SET` notices
  are lost (WARN in the gateway); nothing fails for the user.
- **Rollback:** revert both images. Redis keys expire within the window; no migration.
