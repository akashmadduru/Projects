# F-20261119-06 Design: shared limiter, OTP caps, OTP lockout (AC-1..AC-8). Class M. ADR 0006.
All paths are under `services/auth-service/auth-service-app/src/main/java/com/acme/ecommerce/auth/` unless they say otherwise.

## 1. Current behaviour (proof)
- Per-IP limiter: a bucket4j `Bucket` per `path|ip` kept in a `ConcurrentHashMap` (ratelimit/RateLimitFilter.java:79, :112, :122). The map is cleared when it holds more than `max-tracked-clients` (:114-120). On a trip it calls `problems.write(..., AUTH_RATE_LIMITED)` (:130). ProblemResponseWriter.java:48 calls `response.reset()`, so **today's 429 has no `Retry-After` header**. The status is 429 (AuthErrorCode.java:75).
- Only `bucket4j-core` is on the classpath: build.gradle:62 and libs.versions.toml:53, :139. No bucket4j Redis, Lettuce or JCache module is present (toml:46-49 notes that the newer artifacts moved to `bucket4j_jdk17-*` coordinates). Redis comes from `spring-boot-starter-data-redis` (build.gradle:38). application.yml:79-82 sets no command timeout, so Lettuce uses its 60 s default.
- OTP request: `OtpLoginService.request` normalises the phone, looks up a verified user, and calls `issueCode` with a 30 s cooldown (service/OtpLoginService.java:59-81). It never throws, and the controller always answers 202 (web/AuthController.java:287-292). There is no per-phone or per-day cap.
- OTP verify: a wrong code or unknown phone returns `AUTH_OTP_INVALID` (OtpLoginService.java:92-97). It makes **no LoginAttemptService call** and does no lock check (:88-106). Each code allows 5 attempts (PhoneVerificationService.java:30; OneTimeTokenService.java:143). Codes have 6 digits (OneTimeTokenService.java:113).
- **Assumption 3 is FALSE as written.** `LoginAttemptService.recordFailure(email)` resolves `email -> user -> user_credentials` and does nothing when either is missing (LoginAttemptService.java:59-67). The counters `failed_attempts` and `locked_until` live on `UserCredential` (domain/UserCredential.java:42-46, :109-123). "Social-only users simply have no row here" (:15-17). So OTP-only users (Google plus phone) cannot hold lockout state. Users with a password do get the lock, keyed by `userId` (the PK). The password login rejects a locked account before it checks the hash: AuthUserDetails.java:76 and LoginService.java:79-84.
- Prior art: gateway `RateLimitWebFilter` uses a fixed window with INCR and then EXPIRE as two separate calls, and fails open (services/gateway/.../filter/RateLimitWebFilter.java:86-102).

## 2. Proposed change
**C1. `ratelimit/SharedWindowCounter` (new, @Component, about 90 lines).** It exposes `long increment(String key, Duration window)`.
- Full key: `key + ":" + (now / window)`, using `TimeProvider`.
- A Lua `DefaultRedisScript<Long>` runs `INCR` and then `PEXPIRE window+30s` when the count is 1. Both commands run in one atomic call, so a key always gets its TTL and concurrent requests at the boundary cannot overshoot.
- On any `RuntimeException`, the counter fails open to a per-instance `ConcurrentHashMap<String, AtomicLong>` with the same keys. The map is cleared when it exceeds `max-tracked-clients`, as today.
- It logs one WARN `"Rate limiter Redis unavailable, using per-instance fallback"`, and repeats it at most every 30 s. After a failure it skips Redis for 5 s, so an outage does not add Redis latency to every request.

**C2. RateLimitFilter.** The bucket map and `newBucket` are removed. `count = counter.increment("auth:rl:" + path + ":" + ip, config.window())`, and the request is allowed if `count <= capacity`. The 429 path is unchanged. The IP still comes from `deviceContexts.clientIp(request)` (:112), and the client-IP logic is untouched. The `libs.bucket4j.core` dependency is dropped (build.gradle:62; toml:53, :139).

**C3. OTP send caps (in `OtpLoginService.request`, before the user lookup, for every valid E.164).**
- Two counters:
  - `w = counter.increment("auth:otp:phone:" + P, otpPerPhoneWindow)`. If `w > otpPerPhoneCapacity`, return.
  - Otherwise `d = counter.increment("auth:otp:day:" + P, 24h)`. If `d > otpPerPhoneDaily`, return.
- The caps run before the user lookup, so known and unknown numbers follow the same path and the response stays 202 with no body.
- P is the output of `PhoneNumbers.normalise`, so "+91 98...", "+91-98..." and "(+91)98..." map to one key. A cap of 0 disables that cap.
- The day window is the epoch-aligned UTC day. Metric: `metrics.loginAttempt(OTP, "capped")`.

**C4. OTP lockout.**
- `LoginAttemptService` gains `recordFailure(UUID userId)` (REQUIRES_NEW). The email variant delegates to it. It also gains `isLocked(UUID userId)` and keeps using `recordSuccess(UUID)`.
- `OtpLoginService.verify`:
  - unknown or unverified phone: throw `AUTH_OTP_INVALID` and record nothing (no row to write; see AC-7);
  - known user with a wrong code: `recordFailure(user.getId())`, then throw `AUTH_OTP_INVALID`;
  - correct code but disabled: `AUTH_ACCOUNT_DISABLED` (existing);
  - correct code but `isLocked`: `AUTH_ACCOUNT_LOCKED`;
  - otherwise `recordSuccess(user.getId())`, then `completeLogin`. The order mirrors LoginService.java:104 and :115.
- `AUTH_ACCOUNT_LOCKED` therefore reaches only someone who holds a valid code, which is the phone's owner. Wrong codes always get `AUTH_OTP_INVALID`, so the lock is never an oracle.

**Config** (`AuthProperties.RateLimit`, plus 3 components; application.yml:157-162):
- `otp-per-phone-capacity: ${AUTH_OTP_PER_PHONE_CAPACITY:3}`
- `otp-per-phone-window: 15m`
- `otp-per-phone-daily: ${AUTH_OTP_PER_PHONE_DAILY:10}`
- `spring.data.redis.timeout: 250ms` and `connect-timeout: 250ms`, so the Redis-down path fails fast. This also benefits UserSessionCache, which already fails open.

**Keys and TTLs.** No DB migration and no new datastore.
| Key | TTL |
|---|---|
| `auth:rl:{path}:{ip}:{idx}` | window+30s (1m30s) |
| `auth:otp:phone:{E164}:{idx}` | 15m30s |
| `auth:otp:day:{E164}:{idx}` | 24h0m30s |

The E.164 number is plaintext in the key. A hash adds no protection over a phone space this small, and the TTL bounds how long it is kept. A spoofed XFF cannot create buckets, because the IP is the already-resolved one (ADR 0002).

## 3. AC mapping
- AC-1: C1 and C2. The counter is a Redis key (`auth:rl:...`), and both instances INCR the same key.
- AC-2: C1 fallback. The per-instance limit equals the configured capacity, the WARN line is logged, and the request is never 5xx. The 250 ms timeout prevents hangs.
- AC-3: C2 still calls `problems.write(AUTH_RATE_LIMITED)`. Status 429, same code, and still no `Retry-After` (ProblemResponseWriter.java:48 resets headers). A regression test pins the header as absent.
- AC-4: C3 window cap. 202 either way, no `sendSms`, and the key `auth:otp:phone:{P}:{idx}` is greater than the cap.
- AC-5: C3 day cap. The day index changes after 24 h, so a new key is used and sending works again.
- AC-6: C4. 5 wrong codes give `failed_attempts=5` and `locked_until` set (UserCredential.java:109-123). After that, a correct OTP returns `AUTH_ACCOUNT_LOCKED` and the password login returns `AUTH_ACCOUNT_LOCKED` (AuthUserDetails.java:76) until expiry. **Scope note:** this holds for accounts with a `user_credentials` row. For OTP-only accounts no row exists (section 1), and the bound comes from 5 attempts per code times the daily cap D=10, which is at most 50 guesses a day against 10^6 codes (about 5e-5 per day). Giving them a lock would need a migration or a second lock store. Flagged to the CEO as a backlog item.
- AC-7: C4. Unknown or unverified phone gets the same exception and code, `AUTH_OTP_INVALID`, and `recordFailure` is never called. The lock response appears only after a correct code, so known and unknown phones look the same to anyone without a code.
- AC-8: C4 calls `recordSuccess(user.getId())` on a successful OTP login.

## 4. Reuse and alternatives
- **Reused:**
  - `PhoneNumbers.normalise` (key normalisation);
  - `ProblemResponseWriter` (429 contract);
  - `LoginAttemptService` and `UserCredential.recordFailure` (lock policy, extended by `userId`);
  - `StringRedisTemplate` (as UserSessionCache does);
  - `FixedTimeProvider.advanceBy` (libs/common-core, :37) for window tests;
  - Testcontainers core `GenericContainer` (toml:207, :257), so no new test dependency.
- **Not reused:** gateway `RateLimitWebFilter`. It is reactive, whereas auth uses the blocking template. Its INCR and EXPIRE are not atomic, so a failed EXPIRE leaves a key with no TTL. A shared Redis counter in `libs/common-web` would put spring-data-redis on every servlet service, which is out of scope. **Backlog for the CEO:** make the gateway INCR+EXPIRE atomic, and consider a shared counter later.
- **Rejected:**
  - **bucket4j Redis (`bucket4j_jdk17-lettuce`):** a new dependency and a new coordinate family (toml:46-49) for a soft control. It is about the same line count as a 3-line Lua script. Rejected.
  - **Redis lock store for OTP-only users:** a second lockout store beside the DB one, with two sources of truth. Deferred.
  - **Counting OTP caps only for known phones:** this creates a timing and behaviour difference between known and unknown phones, so it would be an oracle. Rejected.
  - **Fail-closed when Redis is down:** this would turn a cache outage into a login outage, contrary to AC-2. Rejected.

## 5. Risks, failure modes, rollback
- **Fixed window vs greedy refill:** up to 2C requests can pass across a window boundary, versus a smooth refill today. This is accepted for a soft control, and the per-account lock remains the real control.
- **Redis down:** the limits fall back to per instance (N x C), which is today's behaviour. The OTP caps also become per instance. WARN logs are throttled.
- **Victim DoS:** an attacker can burn a victim's OTP caps (at most 15 min or one UTC day) and lock their password login through wrong OTPs. The lock is capped at 15 min and is never permanent, the same as the email path today. Password and Google login are unaffected by OTP caps.
- **Cross-channel link:** wrong OTPs to P followed by a password attempt on E that returns `AUTH_ACCOUNT_LOCKED` reveal that P and E are one account. The attacker needs both candidates. This is accepted under AuthErrorCode.java:23-27 and documented.
- **Timing:** the known-phone path does more DB work than the unknown one. This gap exists today (verifyCode) and grows by one REQUIRES_NEW write. It is a residual risk, and the tests compare status, body and headers, not latency.
- **Deploy:** old and new pods can run side by side. Old pods keep in-memory buckets and new pods use Redis. There is no migration.
- **Rollback:** revert the image. The Redis keys expire within 24 h. Kill switches are `auth.rate-limit.enabled=false` and caps set to 0.

**Tests** (`bash .openforge/scripts/gradle.sh :services:auth-service:auth-service-app:test`):
- `support/RedisTestContainer`: a singleton `redis:7-alpine`, the same image as deploy/local/docker-compose.yml:119.
- `SharedRedisLimiterIT` (AC-1, AC-3): two `LettuceConnectionFactory`, two `StringRedisTemplate`, two counters and two `RateLimitFilter`s on one Redis. C requests alternate between the filters and request C+1 returns 429 with `AUTH_RATE_LIMITED` and no `Retry-After`. The test also asserts the `auth:rl:*` key and its TTL.
- `SharedWindowCounterTest` (AC-2): the factory points at a closed port. The test asserts no exception, the fallback limit trips at C+1, and the WARN appears (OutputCaptureExtension). A second test fires 50 concurrent increments and expects exactly 50.
- `OtpCapsIT` (AC-4, AC-5, Postgres + Redis, `@MockitoBean NotificationGateway`, `FixedTimeProvider`):
  - a cap+1 request returns 202 with the same empty body, and `sendSms` is called `times(cap)`;
  - 3 formats of P map to one key;
  - after D sends the next one is skipped, and after `advanceBy(24h)` a request sends again;
  - an unknown phone gets the same 202.
- `OtpLockoutIT` (AC-6, AC-7, AC-8):
  - 5 wrong codes give `failed_attempts=5`;
  - a fresh code via `issueCode(..., Duration.ZERO)` then returns `AUTH_ACCOUNT_LOCKED`, and so does the password login;
  - after `locked_until` passes, the login succeeds and the count is 0;
  - an unknown or unverified phone with 10 wrong codes returns `AUTH_OTP_INVALID` with the same body shape, and no credential row changes.
- Existing suites must stay green. application-test.yml:42-46 keeps the IP limiter off.

## 6. Files expected to change (estimate about 230 production lines, about 400 test lines)
- New: `ratelimit/SharedWindowCounter.java` (~90).
- Changed:
  - `ratelimit/RateLimitFilter.java` (~-40/+20);
  - `service/OtpLoginService.java` (+35);
  - `service/LoginAttemptService.java` (+30);
  - `config/AuthProperties.java` (+12);
  - `config/SecurityConfig.java:288-293` (+2);
  - `src/main/resources/application.yml` (+8);
  - `auth-service-app/build.gradle` (-2);
  - `gradle/libs.versions.toml` (-10).
- Tests:
  - new `support/RedisTestContainer`, `SharedWindowCounterTest`, `SharedRedisLimiterIT`, `OtpCapsIT`, `OtpLockoutIT`;
  - edited `UserSessionCacheTest.java:61` and `RedirectTargetPolicyTest.java:83` (new RateLimit args).
- Docs:
  - `docs/services/auth-service.md`;
  - `docs/reference/environment-variables.md` (3 env vars);
  - `docs/techdebt/phase6.md` and `README.md:26` (remove TD-P6-11);
  - a `remediation-plan.md` row.
- No endpoint is added, so `endpoints.md` is unchanged.

## CTO decision
Recorded by forge-cto on 2026-11-20 in its reply; forge-ceo transcribed it here verbatim.

ACCEPT, conditional on a CPO change to AC-6.

AC-6 ruling: (a). Narrow AC-6 to accounts with a user_credentials row. Route the change to the CPO and move OTP-only lockout to the backlog. I reject (b) because it adds a second lock store with two sources of truth, which the design already rejected. Per-phone caps bound OTP-only guessing to at most 50 a day against 10^6 codes.

Conditions:
1. The CPO amends AC-6 and records the backlog item before the build starts.
2. Add a test that OTP-only or social-only users get no lock and no error, with AC-7 still green.
3. Dropping bucket4j is fine. Verify that nothing else uses it, and that the build and the existing 429 tests pass.
4. Fallback: the Redis-down test must cover Lua script errors and the 250 ms timeout, not only a closed port.
5. Cap defaults of 3 per 15 min and 10 per day are fine. Keep the 0-disables switch and document it.
6. Document the residual timing and cross-channel risks in the docs.
