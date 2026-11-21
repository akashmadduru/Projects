# F-20261119-08 Tasks: access-token deny-list (AC-13..AC-17), class M, single slice

Owner of every task: forge-backend-engineer. Paths are relative to the worktree `.openforge/worktrees/F-20261119-08`.
Java builds: `bash .openforge/scripts/gradle.sh <task>`, one at a time, run in the background. Check for `gradle_rc=0`.
Gradle path for the gateway is `:services:gateway:test` (it has no `-app` module); confirm against `settings.gradle` if it fails to resolve.
CTO conditions are numbered C1..C7 as in design.md "CTO decision".

## T1. Shared deny-list definition (common-security)
- Goal: add the final, framework-free `AccessTokenDenyList` with `KEY_PREFIX="access-deny:"`, `key(String sub)`, `revoked(Instant iat, long cutoff)` (true when `iat == null || iat.epochSecond <= cutoff`) and `MARGIN=60s`.
  `key` must reject a null or blank `sub` explicitly (throws IllegalArgumentException) so it is never used to build a key.
- Files: `libs/common-security/src/main/java/com/acme/ecommerce/common/security/AccessTokenDenyList.java` (new);
  `libs/common-security/src/test/java/com/acme/ecommerce/common/security/AccessTokenDenyListTest.java` (new).
- Tests: iat before, equal to and after the cutoff (equal is revoked); null iat; key format; null and blank sub rejected.
- ACs: AC-15 (comparison). CTO: C3 (null sub in the shared key).
- Verify: `bash .openforge/scripts/gradle.sh :libs:common-security:test`

## T2. auth: deny store with forward-only Lua write, metrics, Redis timeout
- Goal: add `AccessTokenDenyStore` (@Component).
  `revokeIssuedUpTo(UUID userId)` takes `cutoff = timeProvider.now().getEpochSecond()` and runs the Lua script
  `cur=tonumber(redis.call('GET',KEYS[1])) or 0; v=math.max(cur, tonumber(ARGV[1])); redis.call('SET',KEYS[1],v,'EX',tonumber(ARGV[2])); return v`.
  Both values are coerced with `tonumber`. TTL = `accessTokenTtl + MARGIN` = 660 s. It never throws; on failure it logs WARN and increments `auth.access_deny.write.failed`.
  Add `isRevoked(jwt)` / a read method for T3 that returns "unavailable" on error.
  Add the two counters to `AuthMetrics` (`auth.access_deny.write.failed`, `auth.access_deny.check.unavailable`, plus `auth.access_deny.rejected`).
  Set `spring.data.redis.timeout: 500ms` in auth `application.yml`.
  Enable never deletes the key.
- Files: `services/auth-service/auth-service-app/src/main/java/com/acme/ecommerce/auth/cache/AccessTokenDenyStore.java` (new);
  `.../auth/metrics/AuthMetrics.java`; `services/auth-service/auth-service-app/src/main/resources/application.yml`;
  `.../src/test/java/com/acme/ecommerce/auth/cache/AccessTokenDenyStoreTest.java` (new, Testcontainers `redis:7`).
- Tests: TTL in [600, 660]; a second write with a lower cutoff leaves the value unchanged (forward-only); a higher cutoff advances it;
  disable then demote; the stored value is epoch seconds; a Redis-down write does not throw and increments the counter.
- ACs: AC-13 (entry holds a cutoff), AC-16, AC-17 (write side). CTO: C1, C4 (auth timeout), C5 (TTL assertion, no deletion on enable).
- Verify: `bash .openforge/scripts/gradle.sh :services:auth-service:auth-service-app:test --tests "*AccessTokenDenyStoreTest"`

## T3. auth: JWT validator wired into the decoder
- Goal: add an `OAuth2TokenValidator<Jwt>` (inner class or separate bean in the store's package) that:
  - skips explicitly with a failure result when `sub` is null or blank (never builds a key from it);
  - GETs `AccessTokenDenyList.key(sub)`;
  - returns failure `invalid_token` through `JwtValidationException` (a BadJwtException subtype) when `revoked(iat, v)`, and increments `auth.access_deny.rejected`;
  - on a Redis error logs WARN, increments `auth.access_deny.check.unavailable` and returns success (fail-open, Option A).
  Add it to the validator list in `SecurityConfig.jwtDecoder`.
- Files: `.../auth/cache/AccessTokenDenyValidator.java` (new, or inside `AccessTokenDenyStore`); `.../auth/config/SecurityConfig.java`;
  `.../src/test/java/.../auth/cache/AccessTokenDenyValidatorTest.java` (new, mocked template).
- Tests: revoked gives a failure; absent gives success; iat after the cutoff gives success; Redis exception gives success plus WARN plus counter; null sub gives a failure and no Redis call.
- ACs: AC-13, AC-15, AC-17. CTO: C2 (auth side), C3, C4.
- Verify: `bash .openforge/scripts/gradle.sh :services:auth-service:auth-service-app:test --tests "*AccessTokenDenyValidatorTest"`

## T4. auth: write the cutoff after commit on disable and demote
- Goal: in `AdminUserService`, after `revokeAllSessions` at the disable path (about `:133`) and the demote-with-removal path (about `:171`), call
  `afterCommit(() -> denyStore.revokeIssuedUpTo(id))` using `TransactionSynchronizationManager.registerSynchronization` (inline, as in `WarehouseService`).
  Nothing is written on rollback, LAST_ADMIN or SELF_MODIFICATION. Enable does not touch the key. Refresh-token revocation is unchanged.
- Files: `.../auth/admin/AdminUserService.java`; `.../src/test/java/.../auth/admin/AdminUserServiceDenyListTest.java` (new, or extend the existing service test).
- Tests: the write is registered after commit for disable, and for demote only when a role was removed; no write on rollback, LAST_ADMIN or SELF_MODIFICATION;
  no write on a demote with no removal; enable makes no deny-store call; `revokeAllSessions` is still called (regression, refresh tokens stay revoked).
- ACs: AC-13, AC-14. CTO: C5 (afterCommit, no deletion on enable).
- Verify: `bash .openforge/scripts/gradle.sh :services:auth-service:auth-service-app:test --tests "*AdminUserService*"`

## T5. auth: end-to-end MockMvc check of the resource server
- Goal: prove the 401 and 200 behaviour at auth's own acceptance point, including the 401 problem body and re-login after demote.
- Files: `.../src/test/java/.../auth/admin/AccessTokenRevocationMvcTest.java` (new; use the existing Testcontainers or mock-Redis style in the module).
- Tests: `/api/auth/me` with a pre-cutoff token gives 401 `AUTH_TOKEN_INVALID`; a post-cutoff token gives 200 (AC-15);
  after demote a new token with a later `iat` works (AC-14); the cutoff only moves forward across disable then demote.
- ACs: AC-13, AC-14, AC-15. CTO: C2 (auth 401 mapping).
- Verify: `bash .openforge/scripts/gradle.sh :services:auth-service:auth-service-app:test --tests "*AccessTokenRevocationMvcTest"`

## T6. gateway: deny-list decoder wrapper
- Goal: add `DenyListReactiveJwtDecoder implements ReactiveJwtDecoder`. It wraps the factory decoder:
  `delegate.decode(t).flatMap(jwt -> ...)`.
  - null or blank `sub`: `Mono.error(new BadJwtException(...))`, no Redis call.
  - revoked: `Mono.error(new BadJwtException("revoked"))` plus `gateway.access_deny.rejected`.
  - absent: pass the jwt.
  - Redis error: `onErrorResume` logs WARN, increments `gateway.access_deny.check.unavailable` and passes the jwt (fail-open).
  Use `MeterRegistry` directly. Use the existing `ReactiveStringRedisTemplate` bean. `SecurityConfig.reactiveJwtDecoder` returns the wrapper.
  Confirm the gateway Redis timeout is still 500 ms in `application.yml` (it is today, `:44`); change nothing if so.
- Files: `services/gateway/src/main/java/com/acme/ecommerce/gateway/security/DenyListReactiveJwtDecoder.java` (new);
  `.../gateway/config/SecurityConfig.java`; `services/gateway/src/test/java/com/acme/ecommerce/gateway/security/DenyListReactiveJwtDecoderTest.java` (new, test G1).
- Tests (G1): revoked gives `BadJwtException`; absent gives the jwt; iat after the cutoff gives the jwt; Redis error gives the jwt plus the counter; null sub gives `BadJwtException` and no lookup.
- ACs: AC-13, AC-15, AC-17. CTO: C2 (gateway), C3, C4.
- Verify: `bash .openforge/scripts/gradle.sh :services:gateway:test --tests "*DenyListReactiveJwtDecoderTest"`

## T7. gateway: edge integration test (G2)
- Goal: prove the real filter chain returns 401 with the problem body for a revoked token, 200 after the cutoff, and 200 when Redis throws.
- Files: `services/gateway/src/test/java/com/acme/ecommerce/gateway/GatewayDenyListEdgeIntegrationTest.java` (new, `GatewayEdgeIntegrationTest` style, `@MockitoBean ReactiveStringRedisTemplate`).
- Tests (G2): revoked token gives 401 and the problem body (asserts the BadJwtException to InvalidBearerTokenException to entry point mapping);
  token with iat after the cutoff gives 200 (AC-15); Redis throwing gives 200 (AC-17); the deny entry read is the epoch-seconds cutoff (AC-13).
- ACs: AC-13, AC-14, AC-15, AC-17. CTO: C2 (401 problem body).
- Verify: `bash .openforge/scripts/gradle.sh :services:gateway:test --tests "*GatewayDenyListEdgeIntegrationTest"`

## T8. Docs, ADR and tech-debt register
- Goal:
  - Update ADR `.openforge/knowledge/decisions/0005-access-token-deny-list.md`: state the downstream-replay gap (backlog B-8) as accepted residual risk, and that any later fix needs a new ADR (C6).
  - Remove TD-ADM-02 from `docs/techdebt/` and add a row to `docs/techdebt/remediation-plan.md`.
  - Note the `access-deny:` key in `docs/architecture` where Redis keys are listed, if such a list exists.
  - Check `docs/reference/environment-variables.md`; no new env vars are expected, so no change unless the Redis timeout is documented there.
  No endpoints are added, so `endpoints.md` is untouched.
- Files: `.openforge/knowledge/decisions/0005-access-token-deny-list.md`; `docs/techdebt/README.md` (and the TD file if it is separate); `docs/techdebt/remediation-plan.md`; `docs/architecture/*` (only if a Redis key list exists).
- ACs: none directly (documentation). CTO: C6.
- Verify: `Grep "TD-ADM-02" docs/techdebt` shows only the remediation-plan row, and `Grep "B-8" .openforge/knowledge/decisions/0005-access-token-deny-list.md` matches.

## T9. Full verification and record
- Goal: run the full gate for the changed modules and record its output in the work folder (CTO C7), covering the common-security, auth and gateway tests (T1..T7 together, to catch regressions in the existing suites such as `UserSessionCache` and the limiter).
- Files: none changed; output is appended to `.openforge/work/F-20261119-08/verify.md` by the engineer.
- ACs: AC-13..AC-17 (regression). CTO: C7.
- Verify: `node .claude/hooks/forge-verify.mjs --changed` (record the full output). If it does not run the Java suites, also run, one at a time:
  `bash .openforge/scripts/gradle.sh :libs:common-security:test`, then
  `bash .openforge/scripts/gradle.sh :services:auth-service:auth-service-app:test`, then
  `bash .openforge/scripts/gradle.sh :services:gateway:test`.

## Coverage table

| AC | Tasks |
|---|---|
| AC-13 | T2, T3, T4, T5, T6, T7, T9 |
| AC-14 | T4, T5, T7, T9 |
| AC-15 | T1, T3, T5, T6, T7, T9 |
| AC-16 | T2, T9 |
| AC-17 | T2, T3, T6, T7, T9 |

| CTO condition | Tasks |
|---|---|
| C1 (Lua tonumber, forward-only test) | T2 |
| C2 (BadJwtException subtype in both services, G2 asserts 401 problem body) | T3, T5, T6, T7 |
| C3 (null sub rejected or skipped, with a test) | T1, T3, T6 |
| C4 (500 ms timeout at both points, WARN plus counter on fail-open) | T2, T3, T6 |
| C5 (TTL 600..660 assertion, afterCommit write, no deletion on enable) | T2, T4 |
| C6 (downstream-replay gap B-8 accepted in the ADR) | T8 |
| C7 (record forge-verify output) | T9 |
