# Phase 1: Platform foundation (1A–1F)

**Status:** Built, with tests (auth, product, gateway, libs).
**Decisions:** [ADR 0001: monorepo and build tooling](../architecture/adr/0001-monorepo-and-build-tooling.md).

Phase 1 proves one request path end to end: browser → gateway → Eureka resolution → independent
JWT validation in each service → a real database read and write.

| Sub-phase | Scope | Where it is documented |
|---|---|---|
| 1A | Gradle multi-project monorepo, version catalog (`gradle/libs.versions.toml`), shared libraries `common-core` / `common-web` / `common-security`, local infra in `infra/docker-compose.yml` | [ADR 0001](../architecture/adr/0001-monorepo-and-build-tooling.md), [getting started](../getting-started.md) |
| 1B | Eureka service registry (:8761), loopback-only | [eureka-server](../services/eureka-server.md) |
| 1C | auth-service (:8081): login, RS256 JWT issuance, JWKS, rotating refresh tokens with reuse detection, Google OIDC | [auth-service](../services/auth-service.md) |
| 1D | API gateway (:8080): routing, JWT validation, deny-by-default, CORS, Resilience4j circuit breakers | [gateway](../services/gateway.md) |
| 1E | product-service (:8082): catalog reads | [product-service](../services/product-service.md) |
| 1F | Vue 3 admin SPA (`apps/admin-product-catalog`, now branded "Cartwise Admin") | [admin app](../apps/admin.md) |

## What the shared libraries provide

**`common-core`** has no Spring Web dependency, so non-web modules can use it.
- `ErrorCode`: the stable, machine-readable error catalogue (part of the public API contract).
- `ApplicationException` and its subclasses (`ResourceNotFoundException`, `ResourceConflictException`,
  `DomainRuleViolationException`, `ValidationException`, `ForbiddenException`, `UpstreamUnavailableException`).
- `ProblemPayload` / `ProblemPayloadFactory`: a framework-neutral RFC 9457 document. 5xx responses never
  echo exception messages.
- `CorrelationIdHolder`, and the `TimeProvider` clock abstraction (domain code never calls `Instant.now()`).

**`common-web`** is auto-configured.
- `GlobalExceptionHandler`: the only place an exception becomes a response body.
- `CorrelationIdFilter`: binds the id to MDC and rejects CRLF-injecting or over-long inbound values.
- `CorrelationAndAuthForwardingInterceptor`: relays `Authorization` and `X-Correlation-Id` on Feign calls.
- Since Phase 10: Kafka dead-letter error handlers (`DlqErrorHandlers`).

**`common-security`**
- `RolesClaimAuthoritiesConverter`: maps the JWT `roles` claim to `ROLE_*` authorities and fails closed.
- `PlatformJwtAuthenticationConverter`: principal = `sub` claim.
- `JwtDecoderBuilder`: JWKS-backed decoder with audience validation.
- `Roles`: `ADMIN`, `CATALOG_MANAGER`, `VIEWER`, plus `SELLER` (Phase 9) and `SUPPORT`, `FINANCE` (Phase 10).

## Changed since

- ZooKeeper was provisioned in 1A for "future coordination". Nothing ever used it, and it was removed
  on 2026-11-14. Scheduled jobs use a Postgres advisory lock (`JobLock`) instead.
- product-service gained write endpoints in [Phase 2](phase-02-event-driven-inventory.md).
