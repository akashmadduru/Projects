# Evidence F-20261119-08
- Design proof, checked by the CEO (G-1), for the architect's unproven assumption about BadJwtException→401. Source: spring-security-oauth2-resource-server-7.0.6-sources.jar.
  - Reactive (gateway): `JwtReactiveAuthenticationManager.java:77-81` maps `BadJwtException` to `InvalidBearerTokenException`, which gives 401. Any other `JwtException` becomes `AuthenticationServiceException`.
  - Servlet (auth): `JwtAuthenticationProvider.java:102-104` does the same.
  - Consequence for the design: a revoked token must throw a `BadJwtException` subtype. The Redis-down path must not throw a plain `JwtException`, which would not give 401.
- @ 48847f8f (CEO-run, 2026-11-20): forge-verify --changed --base a04ad610: env reference PASS; gradle common-security, auth-service-app, gateway PASS (gradle_rc=0). JUnit: 41 suites, 185 tests, 0 skipped, 0 failures, 0 errors. Dirty after build: 0. Prod lines 301.

## QA (step 7)
Head SHA 48847f8f759572b273ded0411d22c7425e484ea9 (worktree HEAD confirmed). No code or tests added or changed. Verdicts rest on the JUnit XML in each module's `build/test-results/test` (from the CEO verify run); each mapped suite shows 0 failures, 0 errors, 0 skipped. QA did not re-run Gradle.

| AC | Test (class::method) | Asserts | Verdict |
|---|---|---|---|
| AC-13 | AccessTokenDenyStoreTest::"stores the cutoff as epoch seconds with a TTL in [600, 660] (AC-13, AC-16)" (5/5) | entry holds epoch-seconds cutoff (real Redis) | PASS |
| AC-13 | AdminUserServiceDenyListTest::"disable writes the deny entry after commit and still revokes refresh tokens" (7/7) | disable writes entry after commit; refresh revoked | PASS |
| AC-13 | AccessTokenRevocationMvcTest::"disable then enable: the pre-disable token stays dead, the cutoff only advances" (2/2) | old token 401 at auth resource server, real Redis | PASS |
| AC-13 | AccessTokenDenyValidatorTest::"a token issued at or before the cutoff fails with invalid_token" (5/5) | revoked gives failure | PASS |
| AC-13 | DenyListReactiveJwtDecoderTest::"a token issued at or before the cutoff fails with BadJwtException" (5/5) | gateway rejects | PASS |
| AC-13 | GatewayDenyListEdgeIntegrationTest::"a token issued before the cutoff is 401 with the problem body (AC-13, AC-14)" (3/3) | 401 plus problem body through the real filter chain | PASS |
| AC-14 | AdminUserServiceDenyListTest::"demote with a role removed writes the deny entry and revokes refresh tokens"; "a role change that removes nothing writes nothing" | demote writes only when a role was removed | PASS |
| AC-14 | AccessTokenRevocationMvcTest::"demote: the old token is 401 AUTH_TOKEN_INVALID, a later login works (AC-13..15)" | old token 401; new token with later iat accepted | PASS |
| AC-14 | GatewayDenyListEdgeIntegrationTest::"a token issued before the cutoff is 401 ..." | gateway 401 | PASS |
| AC-15 | AccessTokenDenyListTest::iatAfterCutoffIsAccepted, iatEqualToCutoffIsRevoked, iatBeforeCutoffIsRevoked, nullIatIsRevoked (6/6) | comparison, equal second is revoked | PASS |
| AC-15 | AccessTokenDenyValidatorTest::"an iat after the cutoff means success"; DenyListReactiveJwtDecoderTest::"an iat after the cutoff passes"; GatewayDenyListEdgeIntegrationTest::"a token issued after the cutoff is 200 (AC-15)" | accepted (200) | PASS |
| AC-16 | AccessTokenDenyStoreTest::"stores the cutoff as epoch seconds with a TTL in [600, 660] (AC-13, AC-16)" | Redis TTL within 600..660 s | PASS |
| AC-16 | AccessTokenDenyStoreTest::"a second write with a lower cutoff leaves the value unchanged (forward-only)", "a higher cutoff advances the value: disable then demote" | forward-only cutoff | PASS |
| AC-17 | AccessTokenDenyValidatorTest::"a Redis failure fails open and counts it (AC-17)"; DenyListReactiveJwtDecoderTest::"a Redis error fails open and counts it (AC-17)"; GatewayDenyListEdgeIntegrationTest::"Redis throwing fails open with 200 (AC-17)"; AccessTokenDenyStoreTest::"a write with Redis down does not throw and counts the failure (AC-17)" | Option A fail-open; counter incremented; write never throws | PASS |

NOT RUN rows: none.

### Gaps (recorded, not fixed)
1. AC-17 WARN log line: no test asserts the WARN output (no log capture in any deny-list test). The metric is asserted; the WARN is not.
2. AC-13 and AC-14 "within 5 s of the admin call": no timing test. Redis is read on every request with no local cache, so it holds by design, but it is unproven. The real-Redis MVC test drives the revoke through the service, not the admin HTTP endpoint.
3. Gateway tests (G1, G2) use a mocked ReactiveStringRedisTemplate: the gateway's real Redis key and TTL are not exercised. The shared `AccessTokenDenyList.key` ties the two services together.
4. AC-14 re-login through the gateway is not covered; only auth's MVC test proves it.
