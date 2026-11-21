# F-20261119-07 Requirements: add-phone enumeration and reset side effect (portfolio O-01, split 2 of 3)

> Split by the CEO on 2026-11-20 from the CPO's single O-01 requirements, following the CTO's 3-PR split. Each part is its own objective, so the PR gate checks each one on its own (see G-8). The AC text below is the CPO's, copied verbatim. Sibling parts: F-20261119-06 (limiter and OTP), F-20261119-07 (add-phone enumeration), F-20261119-08 (token deny-list).

## Problem
Auth abuse limits are in-memory and per replica, so N replicas multiply every limit (RateLimitFilter.java:79). OTP login failures never feed the account lockout (OtpLoginService.verify has no LoginAttemptService call), and SMS cost is bounded only by a 30 s cooldown. Add-phone returns 409 `AUTH_PHONE_TAKEN`, revealing registered numbers. A disabled or demoted user keeps a working access token for up to 10 min (TD-P6-11, TD-P6-20, TD-ADM-02).

## Users affected
Customers using OTP login or add-phone; admins who disable or demote users; the Owner (SMS cost, security exposure); Google-only users whose password reset creates a credential.

## Scope
TD-P6-11 (shared limiter, per-phone and per-day OTP caps, OTP failures toward lockout), TD-P6-20 (generic add-phone response, out-of-band notice, reset-creates-credential documented or gated), TD-ADM-02 (deny-list by `sub` with `iat` cutoff). Uses existing Redis only.

## Non-goals
- TD-P6-10 (unverified-email order policy, captcha/proof-of-work): deferred to the Owner.
- TD-SEC-08 (account-linking UX): deferred to the Owner.
- Client-IP logic (ClientIp, ClientAddress, ForwardedHeaderConfig, gateway IP filters) stays untouched; the limiter only consumes the resolved IP.
- No new service, datastore or vendor; no change to the 10 m access-token TTL; no captcha.

## Acceptance criteria
- AC-9: Given phone P is VERIFIED on another account, when user A calls add-phone with P, then the response status and body are identical to those for an unused number (no 409, no `AUTH_PHONE_TAKEN` on add, resend or verify paths: PhoneVerificationService :57, :91, :96), and no code is delivered to A.
- AC-10: Given the same call, then the owner of P receives one notice (SMS or email via the existing notification path) that someone tried to add their number, and the notice is rate-limited to at most one per owner per day.
- AC-11: Given P is unused, when A completes verification, then behaviour is unchanged (phone becomes VERIFIED).
- AC-12: Given a Google-only user completes password reset, then either (a) no credential row is created and the user gets a defined response, or (b) the behaviour is documented in the techdebt entry and API docs, and a notice is sent to the account email. The design picks one; the test asserts the chosen outcome (DB row and email).

## Edge cases and abuse cases
- Concurrent requests at the cap boundary must not exceed the cap (atomic increment).
- Phone formats ("+91 98...", "98...") must map to one counter key (same normalisation as PhoneNumbers).
- Spoofed `X-Forwarded-For` must not create new buckets (uses already-resolved IP; not changed here).
- Cap bypass by varying case or whitespace in phone; counter-key growth must be TTL-bounded.
- Attacker locking out a victim by wrong OTPs: lockout is the existing 1 m to 15 m backoff, no permanent lock.
- Timing and body differences between taken and free numbers must not leak (compare status, body, headers).
- Admin disables a user twice, or demotes then disables: cutoff only moves forward.
- Clock skew between replicas: `iat` compared in seconds, cutoff set at or after the revoke time.
- Refresh tokens remain revoked as today (regression).

## Assumptions to verify

None.

## Open questions
None blocking. AC-12 (a) or (b) is a design decision; the design picks one and states why.
