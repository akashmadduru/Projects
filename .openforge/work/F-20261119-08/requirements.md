# F-20261119-08 Requirements: access-token revocation on disable and demote (portfolio O-01, split 3 of 3)

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
- AC-13: Given user U with a valid access token, when an admin disables U, then within 5 s of the admin call returning 200, a request with U's existing token returns 401 at the point where access tokens are accepted, and the Redis deny entry for `sub` U holds a cutoff timestamp.
- AC-14: Given the same for demote (role removed), then the old token is rejected with 401 within 5 s; the user can log in again and the new token works (its `iat` is after the cutoff).
- AC-15: Given a token with `iat` later than the cutoff (issued after re-enable or re-login), then it is accepted (200).
- AC-16: Given a deny entry, then its Redis TTL is at least the access-token TTL (10 m) and no more than that plus a small margin, so entries expire on their own.
- AC-17: Given Redis is down when the check runs, then behaviour equals the chosen option below (see Decision), and a WARN log line plus a metric increment are emitted.

Decision (design, not decided here): deny-list when Redis is down.
- Option A, fail-open: accept the token, so a Redis outage never takes the platform down; the revocation gap is up to 10 m, as today. The existing `UserSessionCache` is fail-open.
- Option B, fail-closed: reject (401/503) every token, so no revoked token is ever accepted but a Redis outage causes a full outage for authenticated traffic.
- Option C (variant): fail-closed only for admin-role tokens, fail-open for customers.

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
1. Where access tokens are validated (gateway, auth-service, or each downstream service). The check must cover every acceptance point.
2. Whether the gateway and auth-service share the same Redis and key prefix.
5. The 5 s bound holds given any Redis or gateway local caching.
6. `iat` has one-second resolution; a token issued in the same second as the cutoff is treated as revoked.

## Open questions
None blocking. The AC-17 fail-open or fail-closed choice is a design decision, accepted by the CTO under the run charter's standing approval.
