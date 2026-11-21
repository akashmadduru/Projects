# F-20261119-06 Objective (portfolio O-01, run R-20261119-01)

## Objective (from portfolio.md:141)
"Auth hardening: login/OTP/enumeration/token revocation. Security-first. Do not touch IP logic (F-03)."
Items: TD-P6-10, TD-P6-11, TD-P6-20, TD-ADM-02, TD-SEC-08.

## Goal
Close the auth-service abuse and revocation gaps that need no product-policy decision: OTP and login abuse limits that hold across replicas, no phone enumeration, and immediate loss of access when an admin disables or demotes a user.

## Base
Stacked on F-20261119-03 head a04ad610, because the auth files F-03 touched (RequestDeviceContext, AdminUserController, ForwardedHeaderConfig) must not conflict. Merge order: F-01, then F-03, then this branch.

## Facts (each with proof)
1. The per-IP limiter is in memory and per replica: `Map<String, Bucket> buckets = new ConcurrentHashMap<>()` at services/auth-service/auth-service-app/src/main/java/com/acme/ecommerce/auth/ratelimit/RateLimitFilter.java:79.
2. Redis is already a dependency of auth (auth-service-app/build.gradle:38, spring-boot-starter-data-redis) and of the gateway (services/gateway/build.gradle:54; application.yml:41). auth already writes per-user state to Redis through `UserSessionCache` (auth/cache/UserSessionCache.java:23-33, StringRedisTemplate). So no new datastore is needed.
3. The access-token TTL is 10m (auth-service application.yml:123). Disable and demote revoke refresh tokens only: `tokens.revokeAllSessions(user.getId(), RevokeReason.ADMIN)` at auth/admin/AdminUserService.java:133 and :171. Access tokens stay valid for up to 10 min (TD-ADM-02).
4. Phone enumeration: `AUTH_PHONE_TAKEN` is thrown at auth/service/PhoneVerificationService.java:57, :91 and :96 (TD-P6-20).
5. TD-P6-11 says OTP failures do not count toward the password lockout (phase6.md:54). The implementer must confirm this in OtpLoginService and LoginAttemptService before relying on it.

## Scope decision (CEO, under charter R-20261119-01)
- **In scope:**
  - TD-P6-11: a Redis-backed limiter; per-phone and per-day OTP caps; OTP failures count toward lockout.
  - TD-P6-20: a generic response for add-phone, plus an out-of-band notice to the owner of the number. The reset-creates-credential behaviour is documented or gated.
  - TD-ADM-02: a deny-list keyed by `sub` with an `iat` cutoff, written on disable and demote and checked where access tokens are accepted.
- **Deferred as Owner decisions, not guessed:**
  - TD-P6-10: whether unverified email should block order placement (a business policy), and captcha (a new vendor, which needs an ADR and Owner approval). Proof-of-work is an option the Owner can choose.
  - TD-SEC-08: the account-linking UX (a product and UX decision).
  These two stay open in the register and are listed in the Owner package.

## Constraints
- Do not touch the client-IP logic (ClientIp, ClientAddress, ForwardedHeaderConfig or the gateway IP filters). Use ClientIp only as a consumer.
- No new service, datastore or vendor.
- The deny-list check must fail safe if Redis is unavailable. The design must choose fail-open or fail-closed and justify it.

## Blocking questions
None for the in-scope items. The deferred items are recorded for the Owner and do not block this objective.
