# gateway (Phase 1D)

The platform's **single public edge**. Everything a browser or external client can reach comes
through this process; every other service listens only on the internal network.

- Stack: Spring Cloud Gateway (**reactive / WebFlux**), Spring Security resource server, Resilience4j.
- Port: `8080` (dev compose), `SERVER_PORT` to override.
- Registers with Eureka as `GATEWAY`; resolves `lb://` targets from the registry.

---

## Why reactive here and blocking-on-virtual-threads in auth-service

This is the one module where the choice is not a matter of taste. A gateway does almost no CPU work
and spends its whole life waiting on sockets it does not own, and Spring Cloud Gateway's routing,
load-balancing and proxying filters are reactive-only in the 2025.1.x train. auth-service is the
opposite workload — short, blocking, database-backed request/response — which is what virtual
threads are for. See each module's `build.gradle` for the full reasoning.

**No Feign anywhere in this module.** One blocking call on a Netty event-loop thread stalls every
other request that loop is multiplexing.

---

## Filter chain order

The ordering IS the design. Several of the security properties below are emergent from *relative*
order, not from any single component.

| Order | Component | Why there |
|---|---|---|
| `-300` | `InboundHeaderSanitizationWebFilter` (`WebFilter`) | Strips **all** inbound `X-User-*`, `X-Auth-*`, `X-Internal-*` headers. First in the process, so nothing — not security, not access logging, not a future filter — can observe a client's forged version. |
| `-250` | `CorrelationIdWebFilter` (`WebFilter`) | Accepts a well-formed inbound UUID or generates one. Before security, so 401s are correlated too. |
| `-100` | Spring Security `WebFilterChainProxy` (framework) | CORS preflight → JWT validation (sig / iss / aud / exp / nbf, 30s skew) → `authorizeExchange`. |
| `0` | `IdentityPropagationGatewayFilter` (`GlobalFilter`) | Injects `X-User-Id`, `X-User-Roles`, `X-User-Email`, `X-Auth-Method`, `X-Correlation-Id` from **validated token claims only**. A `GlobalFilter`, not a `WebFilter`: gateway filters run inside `FilteringWebHandler`, which is where the request that actually gets proxied is assembled. |
| route-scoped | Resilience4j `CircuitBreaker` | `authCb` / `productCb`, fallback → `/__fallback/{service}`. |
| `10000` / `10150` / lowest | `RouteToRequestUrlFilter` / `ReactiveLoadBalancerClientFilter` / `NettyRoutingFilter` (framework) | Route resolution, Eureka `lb://` resolution, proxying. Nothing in this module is ordered near these. |

`X-User-Roles` carries the **unprefixed** role names, exactly as the token's `roles` claim spells
them (`VIEWER`, not `ROLE_VIEWER`). The `ROLE_` prefix is a Spring `GrantedAuthority` convention,
added by `common-security`'s `RolesClaimAuthoritiesConverter`; propagating it would give a downstream
two different spellings of the same fact.

---

## Route table

`spring.cloud.gateway.server.webflux.discovery.locator.enabled` is **`false` and must stay false** —
the auto-locator publishes *every* registered service at the public edge, including eureka-server,
with no deliberate authorization rule.

| Route id | Predicate | Target | Auth |
|---|---|---|---|
| `auth-public` | `/api/auth/{login,register,refresh,logout}` | `lb://AUTH-SERVICE` | permitAll |
| `auth-secured` | `/api/auth/me`, `/api/auth/validate` | `lb://AUTH-SERVICE` | authenticated (Bearer) |
| `auth-jwks` | `/.well-known/jwks.json` | `lb://AUTH-SERVICE` | permitAll |
| `auth-oauth2` | `/oauth2/**`, `/login/oauth2/**` | `lb://AUTH-SERVICE` | permitAll (state + PKCE enforced upstream) |
| `product` | `/api/products/**` | `lb://PRODUCT-SERVICE` | authenticated (Bearer) |
| — | `/actuator/health/**` | local | permitAll (see below) |
| — | `/actuator/**` (all else) | local | **denyAll** |
| — | anything else | — | `anyExchange().authenticated()` — deny by default |

The route table and `SecurityConfig`'s `authorizeExchange` rules are two halves of one statement, and
`GatewayEdgeIntegrationTest.RouteAuthorizationContract` asserts them against each other. When they
drift nothing fails loudly — a route keeps working, it just stops being protected.

### Actuator, dev vs prod

In dev `/actuator/health` is public because there is no separate internal interface to bind to. The
production intent (`prod` profile in `application.yml`) is `management.server.port` + `address:
127.0.0.1`, so `/actuator` is not reachable from the internet at all and the `denyAll` rule becomes a
second line of defence rather than the only one.

---

## JWKS caching contract

Built explicitly from Nimbus' `JWKSourceBuilder` rather than accepting
`NimbusReactiveJwtDecoder.withJwkSetUri(...)` defaults, which are wrong for an edge in three ways.
Configured in `gateway.jwks.*`:

| Behaviour | Setting | Why |
|---|---|---|
| Zero network calls per request | `ttl: 15m` + `refresh-ahead: 2m` (async) | The renewal happens on a background thread, so no request ever pays for it. |
| One fetch per key rotation, not one per request | `rate-limit-interval: 30s` | A burst carrying an unknown `kid` collapses into a single upstream fetch. |
| auth-service outage ≠ platform outage | `outage-ttl: 1h` | Keeps verifying against the last known key set. |

**Nimbus enforces `rateLimit < ttl` and `refreshAhead + refreshTimeout <= ttl`** at build time; a bad
combination fails startup rather than degrading silently.

> **Trap, found the hard way:** Nimbus' outage-tolerant and retrying wrappers catch exactly
> `JWKSetUnavailableException`. A plain `KeySourceException` propagates straight through both, so the
> outage tolerance is configured, reads as configured, and does nothing. `HttpJwkSetSource` therefore
> throws `JWKSetUnavailableException` for **every** failure mode, and there is a test asserting the
> end-to-end behaviour.

**No eager fetch at startup**, and the compose service deliberately has **no `depends_on:
auth-service`**. The edge must start while its identity provider is down, or every auth-service
rollout becomes a platform-wide outage.

The JWKS URL is resolved through Eureka (`gateway.jwt.jwks-service-id: AUTH-SERVICE`) at fetch time,
not captured once at startup. `gateway.jwt.jwks-uri` bypasses discovery for tests or registry-less
deployments.

---

## CORS — exactly one configuration mechanism

CORS is configured **only** in the Spring Security chain (`SecurityConfig#corsConfigurationSource`).
`spring.cloud.gateway.server.webflux.globalcors` is deliberately unset: configuring both makes a
preflight pass through two handlers and emit two `Access-Control-Allow-Origin` values, which browsers
reject outright with a message that names neither configuration.

That is only half the problem, and the other half **only shows up against a real downstream**:
auth-service has its own `CorsConfigurationSource` (it has to — it is directly reachable in dev), so
its `Access-Control-*` headers arrive on the proxied response and get appended to the edge's. The fix
is a `DedupeResponseHeader` default filter (`RETAIN_FIRST` for the `Access-Control-*` family,
`RETAIN_UNIQUE` for `Vary`). This was found by curling the live stack; the test stub now emits CORS
headers like a real service so it cannot regress.

`allowCredentials: true` is required for the refresh cookie, which is why the origin list is exact
and can never be `*`.

---

## Resilience4j sizing

| | `authCb` | `productCb` |
|---|---|---|
| slow-call threshold | **4s** | **1s** |
| route response-timeout | 10s | 5s (global) |
| time limiter | 11s | 6s |

Two instances with different slow-call thresholds is the point: Argon2id login is *legitimately*
slow, and a single platform-wide 1s threshold would classify every normal login as a slow call and
trip the auth circuit under ordinary load — taking down login because login is working as designed.

Shared: `sliding-window-size=20`, `minimum-number-of-calls=10`, `failure-rate-threshold=50%`,
`wait-duration-in-open-state=10s`, `permitted-number-of-calls-in-half-open-state=3`.

> **Trap:** Spring Cloud CircuitBreaker's reactive Resilience4J implementation applies
> `TimeLimiterConfig.timeoutDuration` as a Reactor timeout, and its **default is 1 second**. Leaving
> the time limiter unconfigured caps every proxied request at 1s regardless of the httpclient
> `response-timeout`, and the symptom (sporadic 503s on slow-but-healthy calls) points nowhere near
> the circuit breaker.

`management.health.circuitbreakers.enabled: false` — an open breaker on the product route is the
gateway working correctly, and letting it fail the readiness probe would have the orchestrator
restart a healthy edge during a downstream incident.

---

## Error handling

Every response the gateway itself produces is RFC 9457 `application/problem+json` with the same
members auth-service emits (`type`, `title`, `status`, `detail`, `instance`, `code`,
`correlationId`, `timestamp`), and a downstream body is **never** forwarded on a gateway-generated
failure.

| Source | Produces |
|---|---|
| `ProblemAuthenticationEntryPoint` | 401 `UNAUTHENTICATED` |
| `ProblemAccessDeniedHandler` | 403 `FORBIDDEN` |
| `CircuitBreakerFallbackController` (`/__fallback/{service}`) | 503 `UPSTREAM_UNAVAILABLE` |
| `GatewayProblemExceptionHandler` (`@Order(-2)`) | anything else; transport failures → 503, not 500 |

`/__fallback/**` is reached by an internal `forward:`, which re-dispatches past the security chain.
A client that calls it directly hits `anyExchange().authenticated()` and gets a 401 — deliberate, and
why it is not in the permit-all list.

---

## Running locally

```bash
# 1. registry
java -jar services/eureka-server/build/libs/eureka-server-0.1.0-SNAPSHOT.jar

# 2. auth-service (needs the dev keystore and Postgres)
PLATFORM_JWT_KEYSTORE_PATH=infra/secrets/jwt-keystore.p12 \
PLATFORM_JWT_KEYSTORE_PASSWORD=devkeystorepw \
PLATFORM_JWT_KEYSTORE_ALIAS=jwt-signing \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15432/auth_db \
java -jar services/auth-service/auth-service-app/build/libs/auth-service-app-0.1.0-SNAPSHOT.jar

# 3. the edge
java -jar services/gateway/build/libs/gateway-0.1.0-SNAPSHOT.jar
```

Or `docker compose -f infra/docker-compose.yml up -d gateway`.

### Environment variables

| Variable | Default | Notes |
|---|---|---|
| `AUTH_JWT_ISSUER` | `https://auth.acme-ecommerce.internal` | **Must equal auth-service's** `auth.jwt.issuer`. |
| `AUTH_JWT_AUDIENCES` | `ecommerce-platform` | **Must equal auth-service's** `auth.jwt.audiences`. |
| `GATEWAY_CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Exact origins, comma-separated. No default under the `prod` profile. |
| `GATEWAY_JWKS_SERVICE_ID` | `AUTH-SERVICE` | Eureka id whose JWKS document is trusted. |
| `GATEWAY_JWKS_URI` | *(blank)* | Absolute URL; a non-blank value bypasses discovery. |
| `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE` | `http://localhost:8761/eureka/` | |

A mismatch on either of the first two produces `invalid_token` on every authenticated request, with
nothing in either service's log pointing at the cause.

---

## Not implemented (tracked, out of Phase 1 scope)

- **Per-client rate limiting at the edge** (`RequestRateLimiter` + Redis). Today the only throttle in
  the platform is auth-service's per-instance, in-memory one, which weakens with every replica added.
- **TLS termination**, WAF / IP reputation ahead of the gateway.
- **Distributed tracing** (`micrometer-tracing` + OTel). The correlation id is propagated end to end,
  but there are no spans yet.
- **auth-service's own CORS config** could arguably be removed once everything goes through the edge.
  It is kept for now because auth-service is directly reachable in dev and in its own tests; the
  gateway's `DedupeResponseHeader` makes the overlap harmless either way.
