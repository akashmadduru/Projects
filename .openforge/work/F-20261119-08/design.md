# F-20261119-08 Design: access-token deny-list on disable and demote (AC-13..AC-17)

Class M. ADR: `.openforge/knowledge/decisions/0005-access-token-deny-list.md` (Proposed). Paths below are relative to the worktree.

## 1. Current behaviour (proven)
- Disable revokes refresh tokens only: `AdminUserService.java:132-133`. Demote does the same only when a role was removed (`:154-172`). Both run in `@Transactional` (`:123`, `:141`). `revokeAllSessions` is `REQUIRES_NEW` (`TokenService.java:214-221`). The access token lives `access-token-ttl: 10m` (auth `application.yml:123`).
- `iat` has 1 s resolution: `issueTime(Date.from(issuedAt))` (`AccessTokenIssuer.java:71`). Nimbus `JWTClaimsSet` serialises dates as whole epoch seconds (nimbus-jose-jwt-10.4.jar, `DateUtils.toSecondsSinceEpoch`). `issuedAt` = `timeProvider.now()` (`TokenService.java:89`). Assumption 6 holds.
- **Acceptance points (Assumption 1):**
  1. Gateway: `oauth2ResourceServer().jwt(jwtDecoder)` (`gateway SecurityConfig.java:199-217`). The decoder is built in `JwksReactiveJwtDecoderFactory.java:63-93`. It is the only public ingress: ALB Ingress → `gateway` only (`helm templates/network.yaml:109-155`). Only the gateway admits traffic from outside the namespace (`network.yaml:55-72`). In compose, every other service binds to 127.0.0.1 (`docker-compose.yml:259-262`, `:273-277`).
  2. auth-service's own resource server (`auth SecurityConfig.java:128-153`, decoder `:218-241`). It guards `/api/admin/**` (`:120-121`) and `/me`, and is reached via the gateway.
  3. Downstream services, which validate JWTs themselves (`order SecurityConfig.java:203-238`, and likewise in cart, inventory, notification, payment, product, promotion, rating, seller, support and user). They are reachable only from pods in the same namespace (`network.yaml:40-53`). No service relays a user bearer token to another (no `Authorization` set in any `src/main`; internal calls use `X-Internal-Token`). So every external request carrying a token passes the gateway check first.
- **Shared Redis (Assumption 2):** both services read `REDIS_HOST`/`REDIS_PORT` with db 0 and no prefix (gateway `application.yml:40-44`; auth `application.yml:79-82`). Helm injects the same `global.endpoints.redis` into both (`deployment.yaml:114-118`; `values.yaml:119`, `:129`). Compose uses `REDIS_HOST: redis` for both (`docker-compose.yml:247`, `:316`). Existing keys are `user-snapshot:` (`UserSessionCache.java:25`) and `rl:` (`RateLimitWebFilter.java:87`). Downstream services have no Redis (`values.yaml:88`, default `redis: false`).
- **Caching (Assumption 5):** the gateway caches JWKS keys only (`JwksReactiveJwtDecoderFactory.java:68-81`). auth uses a fixed `ImmutableJWKSet` (`SecurityConfig.java:220`). Neither caches decoded tokens or authentication (`NoOpServerSecurityContextRepository`, gateway `SecurityConfig.java:101`; auth is STATELESS, `:111-112`). The design adds no local cache, so the bound is Redis write-to-read latency (ms), well under 5 s.
- Redis timeouts: gateway 500 ms (`application.yml:44`). auth sets none, so it gets the Lettuce default of 60 s, which would stall a fail-open check.

## 2. Reuse before build
- `UserSessionCache` (auth): reuse its pattern (StringRedisTemplate, TTL = `properties.jwt().accessTokenTtl()`, never throws, WARN). It is not extended: its key is a JSON snapshot overwritten on every login, and the deny entry must only move forward.
- `RateLimitWebFilter` (gateway): reuse the `ReactiveStringRedisTemplate` bean and its `onErrorResume` fail-open pattern (`:98-102`).
- After-commit hook: reuse the `TransactionSynchronizationManager.registerSynchronization` pattern (`WarehouseService.java:145-152`, `AuditPublisher.java:57-60`). It is inlined because auth has no shared helper; a shared lib for one call site is not worth it.
- `AuthMetrics` (`metrics/AuthMetrics.java`): extend with two counters. The gateway has no metrics class, so it uses `MeterRegistry` directly (actuator + prometheus, `gateway build.gradle:43,52`).
- `libs/common-security` (already used by both services): gets the one shared definition of the key and the comparison.
- No existing deny-list, revocation key or Lua script exists anywhere in the repo (searched `deny|cutoff|RedisScript`).

## 3. Proposed change
**Shared (libs/common-security)** `AccessTokenDenyList` (final, framework-free, about 25 lines):
- `KEY_PREFIX = "access-deny:"` and `key(String sub)`;
- `boolean revoked(Instant iat, long cutoffEpochSec)`: true when `iat == null || iat.getEpochSecond() <= cutoff`.
- `MARGIN = 60s`, which covers the consumers' 30 s `exp` skew (`GatewayProperties.java:34`) plus write latency.

**Key and TTL:** the key is `access-deny:{sub}`. The value is the cutoff as epoch seconds in decimal. The TTL is `accessTokenTtl + 60s` = 660 s, so it is at least 600 s and no more than 660 s (AC-16). Every token issued before the cutoff has `exp` ≤ cutoff + 600 s, plus a 30 s skew.

**auth-service** `auth/cache/AccessTokenDenyStore` (@Component, about 90 lines):
- `revokeIssuedUpTo(UUID userId)` takes `cutoff = timeProvider.now().getEpochSecond()` and runs a Lua script through `StringRedisTemplate.execute(RedisScript)`:
  `cur=tonumber(GET k) or 0; v=math.max(cur, ARGV[1]); SET k v EX ARGV[2]; return v`
  This is atomic and **forward-only**: a second disable, demote-then-disable, or a replica with a lagging clock can never move the cutoff back. Enable never deletes the key; deleting it would bring back tokens issued before the disable.
  It never throws. On failure it logs WARN and increments `auth.access_deny.write.failed`.
- `OAuth2TokenValidator<Jwt>`: GET the key. If present and `revoked(iat, v)` is true, fail with `invalid_token` and increment `auth.access_deny.rejected`. On a Redis error it logs WARN, increments `auth.access_deny.check.unavailable` and returns success (fail-open).
- `AdminUserService`: after `revokeAllSessions` at `:133` and `:171`, call `afterCommit(() -> denyStore.revokeIssuedUpTo(id))`. The cutoff is taken after the commit, so a login that races the transaction either sees DISABLED or gets an `iat` at or before the cutoff. A rollback writes nothing. `afterCommit` runs on the request thread before the 200 is written.
- `SecurityConfig.jwtDecoder` (`:232-239`): add the validator to the list. A validator failure surfaces as `JwtValidationException` → `InvalidBearerTokenException` → the existing entry point → 401 `AUTH_TOKEN_INVALID`.
- `application.yml`: `spring.data.redis.timeout: 500ms` (same as the gateway). This only makes `UserSessionCache` fail faster.

**gateway** `security/DenyListReactiveJwtDecoder implements ReactiveJwtDecoder` (about 70 lines):
- It wraps the factory decoder: `delegate.decode(t).flatMap(jwt -> redis.opsForValue().get(key(sub)) ...)`.
- If revoked: `Mono.error(new BadJwtException("revoked"))` and increment `gateway.access_deny.rejected`. `JwtReactiveAuthenticationManager` maps `BadJwtException` to `InvalidBearerTokenException` → `ProblemAuthenticationEntryPoint` → 401. This mapping is library behaviour (spring-security-oauth2-resource-server 7.0.6) and is proven by test G2.
- If the key is absent: pass. On a Redis error: `onErrorResume` logs WARN, increments `gateway.access_deny.check.unavailable` and passes.
- The lookup is non-blocking, so it adds nothing on the event loop. `SecurityConfig.reactiveJwtDecoder` (`:259-263`) returns the wrapper.

There are no endpoints, DTOs, events, DB migrations or new config keys. The 10 m TTL is unchanged, and so is the client-IP logic.

## 4. AC mapping
- **AC-13 (disable):** the afterCommit write happens before the 200. The gateway (and auth) GET on every request finds `iat ≤ cutoff` and returns 401. The entry holds the epoch-seconds cutoff.
- **AC-14 (demote):** the same, via `:171` (only when a role was removed). Re-login gives `iat` > cutoff, so the token is accepted. A re-login in the same second is rejected once, and the client's refresh gives a later `iat` (Assumption 6, accepted).
- **AC-15:** `iat` > cutoff, so `revoked()` is false → 200.
- **AC-16:** `SET ... EX 660`. A test asserts `600 ≤ TTL ≤ 660`.
- **AC-17:** Option A, fail-open, with WARN plus a `*.access_deny.check.unavailable` counter at both check points.

## 5. Redis-down decision: Option A (fail-open)
- Redis is deliberately a soft dependency platform-wide:
  - health checks are disabled (gateway `application.yml:572-575`);
  - auth has no `depends_on: redis`, and `UserSessionCache` fails open (`application.yml:74-78`);
  - the edge limiter fails open (`RateLimitWebFilter.java:98-102`).
- Option B would turn a cache outage into a full authenticated-traffic outage at the single ingress. Option C would lock admins out during the incident in which they are most needed, and it adds a second policy path.
- With fail-open the exposure is bounded by today's behaviour: at most 10 m, and refresh-token revocation (DB) still holds.

## 6. Alternatives rejected
- **Short access TTL (1-2 m):** this is a non-goal (TTL is fixed) and it multiplies refresh load.
- **Deny by `jti`:** auth does not know the live `jti`s of a user, so every issued `jti` would need recording; a per-`sub` cutoff is O(1).
- **Check in all 13 services via common-security:** this needs Redis in 11 services that have none, which means 4+ services and a re-score to L. It is filed as backlog (below).
- **Local in-memory cache of cutoffs:** this breaks the 5 s bound unless its TTL is under 5 s, and it gains little.
- **Putting the write in `TokenService.revokeAllSessions`:** that would also cover PASSWORD_CHANGE, a behaviour change outside these ACs.

## 7. Risks, failure modes, rollback
- **Residual:** a pod inside the namespace calling a downstream service directly with a revoked token is accepted for up to 10 m. This is the same ClusterIP trust as ADR 0002. Backlog for the CEO: an optional deny validator in common-security for all resource servers (needs Redis per service, so an ADR is required).
- **Write fails while Redis is down:** the admin still gets 200, and the gap reverts to 10 m. The WARN and metric make it visible.
- **Clock skew:** the issuing replica running ahead of the revoking replica by d s leaves a d s window; NTP is assumed.
- **Latency:** one Redis GET per authenticated request, the same order as the limiter's INCR. If Redis is down, Lettuce's 500 ms timeout is added per request; the existing limiter already pays this.
- **Rollback:** revert the image; keys expire on their own within 11 m. There is no migration.

## 8. Test plan
- **common-security `AccessTokenDenyListTest`:**
  - `iat` < cutoff, = cutoff and > cutoff (the same second is treated as revoked);
  - null `iat`;
  - the key format.
- **auth `AccessTokenDenyStoreTest` (Testcontainers `redis:7`):**
  - TTL is in [600, 660] (AC-16);
  - a lower second write leaves the cutoff unchanged (forward-only);
  - disable then demote;
  - a Redis-down write never throws and increments the metric.
- **auth validator unit test (mocked template):** revoked → error; absent → ok; Redis exception → ok + WARN + counter (AC-17).
- **auth `AdminUserService` test:** the write is registered after commit for disable, and for demote-with-removal only; nothing is written on rollback, LAST_ADMIN or SELF_MODIFICATION; refresh revocation still happens (regression).
- **auth MockMvc:** `/api/auth/me` with a pre-cutoff token → 401 `AUTH_TOKEN_INVALID`; a post-cutoff token → 200.
- **gateway `DenyListReactiveJwtDecoderTest` (G1):** revoked → `BadJwtException`; absent → jwt; error → jwt + counter.
- **gateway edge (G2, `GatewayEdgeIntegrationTest` style, `@MockitoBean ReactiveStringRedisTemplate`):** a revoked token → 401 problem body; a token after the cutoff → 200; Redis throwing → 200 (AC-13/14/15/17).

## 9. Files expected to change (about 230 production lines; about 350 test lines)
- `libs/common-security/src/main/java/com/acme/ecommerce/common/security/AccessTokenDenyList.java` (new)
- `services/auth-service/auth-service-app/src/main/java/com/acme/ecommerce/auth/cache/AccessTokenDenyStore.java` (new)
- `.../auth/admin/AdminUserService.java`, `.../auth/config/SecurityConfig.java`, `.../auth/metrics/AuthMetrics.java`, `auth-service-app/src/main/resources/application.yml`
- `services/gateway/src/main/java/com/acme/ecommerce/gateway/security/DenyListReactiveJwtDecoder.java` (new), `.../gateway/config/SecurityConfig.java`
- Tests as listed in section 8. Docs: remove TD-ADM-02 from `docs/techdebt/` and add a row to `docs/techdebt/remediation-plan.md`; note the keys in `docs/architecture` where Redis keys are listed, if a list exists.

## CTO decision
Decided by forge-cto on 2026-11-20 and returned in its reply. forge-ceo transcribed it here verbatim.

ACCEPT (class M, no re-score needed).

Conditions:
1. The Lua script must coerce both values with tonumber before math.max. Keep the forward-only test.
2. The revoked path must throw a BadJwtException subtype in both services: BadJwtException at the gateway, JwtValidationException in auth. Test G2 must assert a 401 problem body.
3. A null sub must be rejected or skipped explicitly, never used to build the key. Add a test.
4. Both check points must use a 500 ms Redis timeout, and the fail-open path must log WARN and increment the counter (AC-17).
5. Keep the AC-16 assertion that TTL is between 600 and 660 s. Keep the afterCommit write, with no key deletion on enable.
6. Treat the downstream-replay gap (backlog B-8) as accepted residual risk. State it in the ADR, and any later fix needs a new ADR.
7. Record the forge-verify output before done.
