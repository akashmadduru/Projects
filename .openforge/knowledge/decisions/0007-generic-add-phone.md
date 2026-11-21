# 0007 Add-phone answers the same for taken and free numbers; the owner gets one email a day; reset keeps creating a credential but says so

## Status
Accepted (2026-11-20, CTO, class L, standing Owner approval R-20261119-01).

## Context
- Add-phone throws 409 `AUTH_PHONE_TAKEN` when the number is verified elsewhere (`PhoneVerificationService.java:56-58`). The verify race does the same (:89-96). Any signed-in user can therefore test whether a number is registered (TD-P6-20).
- The storefront shows the problem detail "Phone number already in use" (`lib/errors.ts:64`, `PhoneSection.tsx:29-35`). No other client handles the code.
- Uniqueness of verified numbers is enforced in the DB by the partial unique index `users_phone_verified_key` (`V2__phase6_tokens_phone_otp.sql:12`), not by the 409.
- Every account has an email (`UserAccount.java:46`). Redis is already used fail-open (`UserSessionCache.java:46-53`).
- Password reset is the only way to set a password, and it creates a credential for Google-only users without telling them (`PasswordResetService.java:109-113`).

## Decision
- **Generic add.** Taken and free numbers run the same steps: store unverified, issue the code with the same 30 s cooldown, and return 202. Only the last step differs. A free number gets the SMS; a taken number gets no SMS and hands the owner notice off asynchronously.
- **Generic verify race.** It returns `AUTH_OTP_INVALID`, the same as a wrong code. The code is consumed in its own transaction before the re-check (`OneTimeTokenService.java:127-151`), so a refused code cannot be replayed. The DB re-check and the unique index remain the safety. `AUTH_PHONE_TAKEN` is deprecated and no longer emitted.
- **Owner notice.** The owner gets an email (`PHONE_CLAIM_ATTEMPT`) through the existing NotificationGateway. It is limited to one per owner per 24 h by Redis `SET NX PX` on `auth:phone-claim-notice:{ownerId}`. If Redis is unavailable, the notice is skipped (fail-closed for the notice only).
- **Reset (AC-12 option b).** Keep creating the credential. When the reset created it, send a `PASSWORD_SET` email to the account address. Document the behaviour in api.md and auth-service.md.

## Alternatives considered
- **Skip issuing a code for taken numbers:** the cooldown 429 then differs between taken and free numbers, which is an oracle. Rejected.
- **SMS notice to the number:** costs money and lets users trigger paid SMS to registered numbers. Rejected.
- **Fail-open notice when Redis is down:** unbounded email to the owner. Rejected.
- **AC-12 (a), gate the reset for Google-only users:** removes their only way to get a password. It adds no protection, because mailbox control already allows a reset on password accounts. Rejected.
- **Reuse F-06 `SharedWindowCounter`:** not on this branch, and a counter is the wrong primitive for a one-shot flag. Rejected.

## Consequences
- An honest user who mistypes a number gets no "already in use" message. A neutral hint in PhoneSection mitigates this.
- **Deploy order: notification-service deploys first, then auth-service.** If auth goes first, the new template values are rejected and the notices are lost; they never cause errors.
- Rollback is an image revert. Redis keys expire within 24 h, and there is no migration.

## Work id
F-20261119-07 (portfolio O-01 part 2, TD-P6-20). Design: `.openforge/work/F-20261119-07/design.md`.
