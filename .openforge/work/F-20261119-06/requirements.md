# F-20261119-06 Requirements: shared limiter, OTP caps and OTP lockout (portfolio O-01, split 1 of 3)

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
- Lockout for OTP-only users (accounts with no password credential) is deferred to backlog B-9.

## Acceptance criteria
- AC-1: Given two auth-service instances on one Redis and a limited endpoint with capacity C per IP, when C+1 requests from one IP are spread across both instances inside one window, then request C+1 returns 429 (`AUTH_RATE_LIMITED`), and the counter lives in a Redis key, not instance memory.
- AC-2: Given the limiter's Redis is unreachable, when a request arrives, then it is not 5xx; it follows the one stated behaviour (fail-open to a per-instance fallback limit) and a WARN log line is emitted.
- AC-3: Given the existing 429 contract, when the limit trips, then status, error code and `Retry-After` header are unchanged from today.
- AC-4: Given phone P, when OTP is requested more than the per-phone cap (value in config, default stated by design) within its window from any IP or instance, then the extra request returns the same HTTP status and body as a normal request (no oracle) but no SMS is sent and a Redis counter keyed by P exceeds the cap.
- AC-5: Given a per-day cap D per phone, when request D+1 arrives in the same 24 h window, then no SMS is sent (assert on the notification stub), and after the window expires a request sends again.
- AC-6: Given a verified phone's account that has a `user_credentials` row (a password credential), when 5 consecutive wrong OTPs (`max-failed-attempts`) are submitted to verify, then the account's failure count is 5 and a further correct OTP and a correct password both fail with the existing locked response until the lockout expires.
- AC-7: Given an unknown or unverified phone, when wrong OTPs are submitted repeatedly, then responses stay `AUTH_OTP_INVALID` identical to the known-phone case and no lockout state is created for any account.
- AC-8: Given a successful OTP login, then the failure count resets, as a successful password login does today.
- AC-9: Given an OTP-only or social-only user (no `user_credentials` row), when wrong OTPs are submitted repeatedly, then the account is never locked and no error is raised; every response stays `AUTH_OTP_INVALID`, and AC-7 still holds.

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
3. LoginAttemptService is keyed by email and works for OTP users (users with no password credential).
4. OTP verify with an unknown phone cannot be locked out without creating an oracle (AC-7).

## Open questions
None blocking.
