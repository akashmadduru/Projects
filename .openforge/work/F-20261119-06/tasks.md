# F-20261119-06 Tasks: shared limiter, OTP caps, OTP lockout (Class M, one slice, about 230 production lines)

Owner of every task: forge-backend-engineer.
Prefix `A/` = `services/auth-service/auth-service-app/src/main/java/com/acme/ecommerce/auth/`.
Test prefix `T/` = `services/auth-service/auth-service-app/src/test/java/com/acme/ecommerce/auth/`.
Verification command for every task (docker, one build at a time, record `gradle_rc=`):
`bash .openforge/scripts/gradle.sh :services:auth-service:auth-service-app:test`
Final gate after T6: `node .claude/hooks/forge-verify.mjs --changed`.
Every task includes its own tests. All tasks are on one branch, one PR.

## T1. Config, Redis timeouts, Redis test container
- Goal: add `otp-per-phone-capacity` (3), `otp-per-phone-window` (15m), `otp-per-phone-daily` (10) to `AuthProperties.RateLimit` and application.yml with env overrides. Set `spring.data.redis.timeout` and `connect-timeout` to 250ms. Add the `RedisTestContainer` singleton (`redis:7-alpine`). Fix the two callers that construct `RateLimit` (`UserSessionCacheTest:61`, `RedirectTargetPolicyTest:83`). Wire `SecurityConfig.java:288-293` if the filter constructor changes (finish this in T3).
- Files: `A/config/AuthProperties.java`, `src/main/resources/application.yml`, `T/support/RedisTestContainer.java`, `T/.../UserSessionCacheTest.java`, `T/.../RedirectTargetPolicyTest.java`.
- ACs: none directly (prerequisite for AC-1, AC-2, AC-4, AC-5). CTO 5 (0 disables, config defaults).
- Tests: existing suite compiles and stays green (config binding covered by the existing context tests).
- Verify: the command above.

## T2. SharedWindowCounter and its unit test
- Goal: new `@Component` with `long increment(String key, Duration window)`. It uses a Lua INCR + PEXPIRE(window+30s) script, the key is `key:(now/window)` via `TimeProvider`, and any `RuntimeException` falls back to a per-instance map capped by `max-tracked-clients`. The WARN is throttled to once per 30 s, and Redis is skipped for 5 s after a failure.
- Files: `A/ratelimit/SharedWindowCounter.java`, `T/ratelimit/SharedWindowCounterTest.java`.
- Tests (AC-2, CTO 4), each asserting no exception, the fallback limit trips at C+1, and the WARN is logged:
  - closed port;
  - Lua script error (for example a script that raises, or a key of the wrong type);
  - 250 ms timeout (a Redis that accepts the connection but does not answer);
  - 50 concurrent increments give exactly 50 (atomic).
- ACs: AC-2. CTO: 4.
- Verify: the command above.

## T3. RateLimitFilter on the shared counter, drop bucket4j
- Goal: replace the bucket map and `newBucket` with `counter.increment("auth:rl:" + path + ":" + ip, window)` and allow while `count <= capacity`. The 429 path via `problems.write(AUTH_RATE_LIMITED)` stays unchanged, with no `Retry-After`. Remove `libs.bucket4j.core` from `build.gradle` and `libs.versions.toml`, after a repo-wide grep shows nothing else uses it. Update the `SecurityConfig` wiring.
- Files: `A/ratelimit/RateLimitFilter.java`, `A/config/SecurityConfig.java`, `services/auth-service/auth-service-app/build.gradle`, `gradle/libs.versions.toml`, `T/ratelimit/SharedRedisLimiterIT.java`, existing `RateLimitFilter` tests (update only if the constructor changed).
- Tests (`SharedRedisLimiterIT`):
  - two filters on two `LettuceConnectionFactory`s over one Redis, requests alternating, C+1 gives 429 `AUTH_RATE_LIMITED`;
  - the `auth:rl:*` key exists with a TTL;
  - the 429 status, code and header set are unchanged, with `Retry-After` absent as today;
  - existing 429 tests stay green.
- ACs: AC-1, AC-3. CTO: 3.
- Verify: the command above.

## T4. OTP per-phone and per-day caps
- Goal: in `OtpLoginService.request`, before the user lookup and for every valid E.164, increment `auth:otp:phone:{P}` (window cap), then `auth:otp:day:{P}` (24 h cap). Over the cap, return silently with no SMS and the metric `loginAttempt(OTP, "capped")`. P is the output of `PhoneNumbers.normalise`. A cap of 0 disables that cap.
- Files: `A/service/OtpLoginService.java`, `T/.../OtpCapsIT.java`.
- Tests (`OtpCapsIT`: Postgres + Redis, `@MockitoBean NotificationGateway`, `FixedTimeProvider`):
  - request cap+1 returns 202 with the same empty body as a normal request, `sendSms` is called `times(cap)`, and the `auth:otp:phone:{P}:*` counter is greater than the cap;
  - 3 phone formats map to one key;
  - D sends are made, send D+1 is skipped, and after `advanceBy(24h)` a request sends again;
  - an unknown phone gets the same 202;
  - a cap of 0 disables the cap.
- ACs: AC-4, AC-5. CTO: 5.
- Verify: the command above.

## T5. OTP failures toward lockout, including OTP-only users
- Goal: add `LoginAttemptService.recordFailure(UUID userId)` (REQUIRES_NEW, the email variant delegates to it) and `isLocked(UUID userId)`.
  - In `OtpLoginService.verify`, an unknown or unverified phone throws `AUTH_OTP_INVALID` and records nothing.
  - A known user with a wrong code gets `recordFailure(userId)`, then `AUTH_OTP_INVALID`.
  - A correct code on a disabled account gets `AUTH_ACCOUNT_DISABLED`; on a locked account, `AUTH_ACCOUNT_LOCKED`.
  - Success calls `recordSuccess(userId)`, then `completeLogin`.
  - A user with no `user_credentials` row is never locked and raises no error.
- Files: `A/service/LoginAttemptService.java`, `A/service/OtpLoginService.java`, `T/.../OtpLockoutIT.java`.
- Tests (`OtpLockoutIT`):
  - credential user: 5 wrong OTPs give `failed_attempts=5` and `locked_until` set; a fresh code issued via `issueCode(..., Duration.ZERO)` then returns `AUTH_ACCOUNT_LOCKED`, and so does the password login;
  - after `locked_until` passes, login succeeds and the count is 0 (AC-8);
  - unknown or unverified phone with 10 wrong OTPs: every response is `AUTH_OTP_INVALID` with the same body shape, and no credential row changes (AC-7);
  - OTP-only or social-only user (no `user_credentials` row) with repeated wrong OTPs: never locked, no exception beyond `AUTH_OTP_INVALID`, and AC-7 assertions still hold (AC-9, CTO 2).
- ACs: AC-6, AC-7, AC-8, AC-9. CTO: 2.
- Verify: the command above.

## T6. Docs and tech-debt register
- Goal:
  - document the three env vars in `docs/reference/environment-variables.md`;
  - document in `docs/services/auth-service.md` the shared limiter, the cap defaults and the 0-disables switch, the fail-open behaviour, and the fixed-window caveat (up to 2C across a boundary);
  - document the residual timing risk and the cross-channel risk (wrong OTPs to P, then `AUTH_ACCOUNT_LOCKED` on email E, link P and E);
  - document the OTP-only lockout deferral to backlog B-9 (at most 50 guesses a day against 10^6 codes);
  - remove TD-P6-11 from `docs/techdebt/phase6.md` and `docs/techdebt/README.md:26`;
  - add a `remediation-plan.md` row.
- Files: `docs/services/auth-service.md`, `docs/reference/environment-variables.md`, `docs/techdebt/phase6.md`, `docs/techdebt/README.md`, `docs/techdebt/remediation-plan.md`.
- ACs: none (documentation). CTO: 5 (document the switch), 6.
- Verify: the command above (nothing breaks), then `node .claude/hooks/forge-verify.mjs --changed`.

## Coverage table
| AC | Task(s) |
|---|---|
| AC-1 | T2, T3 |
| AC-2 | T1, T2 |
| AC-3 | T3 |
| AC-4 | T1, T4 |
| AC-5 | T1, T4 |
| AC-6 | T5 |
| AC-7 | T5 |
| AC-8 | T5 |
| AC-9 | T5 |

| CTO condition | Task(s) |
|---|---|
| 1. AC-6 amended, B-9 recorded | Already done before planning (requirements.md AC-6 and AC-9, backlog B-9). T6 references B-9 in the docs. |
| 2. OTP-only no-lock test, AC-7 green | T5 |
| 3. bucket4j unused elsewhere, build and 429 tests pass | T3 |
| 4. Redis-down test covers Lua error and 250 ms timeout | T2 (config of the timeout in T1) |
| 5. Defaults 3/15 min and 10/day, 0 disables, documented | T1, T4, T6 |
| 6. Residual timing and cross-channel risks documented | T6 |
