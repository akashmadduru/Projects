# E-commerce Platform

A Spring Boot microservices platform with a Vue admin SPA, built as a Gradle multi-project monorepo.

> **Current state: Phase 1 (1A–1F) complete, plus a Phase 2 event-driven inventory slice.**
> The build, shared libraries, local infrastructure, service registry, gateway, auth-service,
> product-service and the Vue admin SPA are all real and verified — `./gradlew build` passes
> whole-tree. On top of that, product-service write endpoints, a new `inventory-service`, a Kafka
> (KRaft) event backbone and a Redis-backed auth-service cache are also real and implemented — but
> that slice is **prototype-grade by explicit design** (no schema registry, no dead-letter queue, no
> exactly-once delivery — see [`docs/HLD-event-driven-inventory.md`](docs/HLD-event-driven-inventory.md)
> §2 Non-Goals), and the rest of Phase 2 (search, pricing, categories, product images) is still not
> started. See the [Roadmap](#roadmap) for what each phase delivered and
> [Future Work](#future-work-phase-2) for what is deliberately still out of scope.

---

## Stack

| Layer | Technology | Version |
|---|---|---|
| Language | Java (LTS) | **25** |
| Build | Gradle (Kotlin DSL, version catalog) | **9.6.1** |
| Framework | Spring Boot | **4.0.7** (Spring Framework 7.0.8) |
| Distributed | Spring Cloud | **2025.1.2** (modules 5.0.2) |
| Security | Spring Security / OAuth2 Resource Server, JWT (Nimbus) | 7.0.6 |
| Persistence | Spring Data JPA (Hibernate 7.2), Flyway 11 | PostgreSQL 17 |
| Discovery | Netflix Eureka | — |
| Edge | Spring Cloud Gateway (WebFlux server) | — |
| Coordination | ZooKeeper 3.9 (locks/leader election — **not** discovery) | — |
| Eventing | Apache Kafka (KRaft mode, single broker) — `product.events` topic | **3.9.0** |
| Caching | Redis (auth-service user-snapshot cache only) | **7 (alpine)** |
| Testing | JUnit 5, AssertJ, Mockito, Testcontainers 2.0 | — |
| Quality | Spotless (google-java-format), Checkstyle | — |
| Frontend | Vue (Phase 1F) | — |

These versions were verified by actually resolving and building them — see
[ADR 0001](docs/adr/0001-monorepo-and-build-tooling.md), which also records four real coordinate
defects the verification caught.

---

## Module map

```
system_design/
├── gradle/libs.versions.toml     Version catalog — single source of truth for ALL versions
├── docs/adr/                     Architecture decision records
├── infra/
│   ├── docker-compose.yml        postgres + zookeeper + kafka + redis (platform services added by phase)
│   ├── postgres/init/            First-start SQL: creates auth_db, product_db, inventory_db + owner roles
│   ├── zookeeper/                Notes — coordination only, NOT service discovery
│   └── scripts/                  generate-keystore.sh (dev RSA keystore for JWT signing)
├── libs/
│   ├── common-core/              ✅ Error envelope, correlation-id holder, clock abstraction
│   ├── common-web/               ✅ RFC 9457 problem+json advice, correlation filter, Feign relay
│   └── common-security/          ✅ JWT→authorities converter, JWKS decoder builder, role constants
├── services/
│   ├── eureka-server/            ✅ Netflix Eureka registry (:8761) — Phase 1B
│   ├── gateway/                  ✅ Spring Cloud Gateway edge (:8080) — Phase 1D
│   ├── auth-service/
│   │   ├── auth-service-api/     ✅ Published contract — Phase 1C
│   │   └── auth-service-app/     ✅ Login, JWT issuance, JWKS, refresh rotation — Phase 1C
│   ├── product-service/
│   │   ├── product-service-api/  ✅ Published contract — Phase 1E
│   │   └── product-service-app/  ✅ Catalog reads (Phase 1E) + role-gated writes and Kafka event
│   │                              publishing (Phase 2 slice) (:8082)
│   └── inventory-service/        ✅ NEW — event-driven inventory (Phase 2 slice, prototype-grade)
│       ├── inventory-service-api/  ✅ Published contract (`InventoryClient`, `InventoryResponse`)
│       └── inventory-service-app/  ✅ Stock-level CRUD + Kafka consumer for `product.events` (:8083)
└── apps/admin-product-catalog/   ✅ Vue 3 admin SPA — Phase 1F (not a Gradle module)
```

✅ = real implementation

### What the shared libraries give you

**`common-core`** — no Spring Web dependency, so non-web modules can use it.
- `ErrorCode` — stable machine-readable error catalogue (part of the public API contract)
- `ApplicationException` + `ResourceNotFoundException`, `ResourceConflictException`,
  `DomainRuleViolationException`, `ValidationException`, `ForbiddenException`,
  `UpstreamUnavailableException`
- `ProblemPayload` / `ProblemPayloadFactory` — framework-neutral RFC 9457 document.
  **5xx responses never echo exception messages** (they leak SQL, hostnames, class names).
- `CorrelationIdHolder` — request-scoped id, with `wrap(Runnable)` for executor hand-off
- `TimeProvider` / `SystemTimeProvider` / `FixedTimeProvider` — injectable clock; never call
  `Instant.now()` in domain code

**`common-web`** — auto-configured; just depend on it.
- `GlobalExceptionHandler` — the *only* place an exception becomes a response body
- `CorrelationIdFilter` — binds the id to MDC, echoes it on the response, and **rejects
  CRLF-injecting or over-long inbound values** (log forging / header splitting)
- `CorrelationAndAuthForwardingInterceptor` — relays `Authorization` + `X-Correlation-Id` on Feign
  calls (token relay: downstream sees the original end user)

**`common-security`**
- `RolesClaimAuthoritiesConverter` — maps the JWT `roles` claim to `ROLE_*` authorities; fails closed
- `PlatformJwtAuthenticationConverter` — principal = `sub` claim
- `JwtDecoderBuilder` — JWKS-backed decoder with **audience validation** (without it, a token minted
  for one service can be replayed against another)
- `Roles` — `ADMIN`, `CATALOG_MANAGER`, `VIEWER` in both prefixed and unprefixed form

---

## Backend Documentation

Complete documentation for the three-service backend architecture (product, inventory, rating):

| Document | Audience | Coverage |
|----------|----------|----------|
| **[Backend-Architecture.md](docs/Backend-Architecture.md)** | Architects, senior engineers | System overview, service responsibilities, data flow, consistency model, deployment topology |
| **[Backend-API.md](docs/Backend-API.md)** | API consumers, frontend engineers | All 20+ endpoints, request/response examples, authentication, error codes, pagination |
| **[Backend-EventSystem.md](docs/Backend-EventSystem.md)** | Event consumers, backend engineers | Event choreography, Kafka topics, event types, idempotency, failure scenarios |
| **[Backend-DataModel.md](docs/Backend-DataModel.md)** | Backend engineers, DBAs | Database schemas, entity relationships, denormalization strategy, indexes, seed data |
| **[Backend-Operations.md](docs/Backend-Operations.md)** | Ops engineers, SREs | Local dev setup, migrations, health checks, monitoring, troubleshooting |
| **[Backend-DecisionLog.md](docs/Backend-DecisionLog.md)** | Architects, future maintainers | Why optimistic locking, event choreography, eventual consistency, Feign for reads, no schema registry |

**Quick start for backend developers:**
1. Read [Backend-Architecture.md](docs/Backend-Architecture.md) for the big picture
2. Run `docker compose -f infra/docker-compose.yml up -d` to start infrastructure
3. Run `./gradlew build` to verify the build passes
4. Start services via `./gradlew :services:product-service:product-service-app:bootRun`, etc.
5. Test endpoints: `curl http://localhost:8082/api/products`

---

## Prerequisites

- **JDK 25** — required. Verify with `java -version`.
  (Gradle's toolchain support can download one if your default JDK differs.)
- **Docker** + Docker Compose — for local Postgres/ZooKeeper/Kafka/Redis.

---

## Build

```bash
# Whole-tree build: compile + test + Spotless check + Checkstyle
./gradlew build

# Auto-fix formatting (do this before committing)
./gradlew spotlessApply

# Tests only
./gradlew test

# Clean rebuild
./gradlew clean build

# See what a module resolves to
./gradlew :libs:common-web:dependencies --configuration compileClasspath
```

The build is a **whole-platform gate**: every module — including the empty Phase 1B–1E scaffolds — is
wired into `settings.gradle.kts`, so nothing can silently fall outside CI.

### Adding a dependency

Never inline a version. Add an alias to [`gradle/libs.versions.toml`](gradle/libs.versions.toml),
then reference it as `libs.some.alias`. Versions come from the Spring Boot / Spring Cloud BOMs
wherever those manage the library.

---

## Local infrastructure

```bash
# Start Postgres + ZooKeeper + Kafka + Redis
docker compose -f infra/docker-compose.yml up -d

# If ports 5432 / 2181 / 6379 are already taken locally
POSTGRES_PORT=15432 ZOOKEEPER_PORT=12181 REDIS_PORT=16379 docker compose -f infra/docker-compose.yml up -d

# Status / logs
docker compose -f infra/docker-compose.yml ps
docker compose -f infra/docker-compose.yml logs -f postgres

# Stop (keep data) / stop and wipe data
docker compose -f infra/docker-compose.yml down
docker compose -f infra/docker-compose.yml down -v
```

On **first** start, `infra/postgres/init/01-schemas.sql` creates:

| Database | Owner role | Local password |
|---|---|---|
| `auth_db` | `auth_service` | `auth_service_local_pwd` |
| `product_db` | `product_service` | `product_service_local_pwd` |
| `inventory_db` | `inventory_service` | `inventory_service_local_pwd` |

Those passwords are throwaway local values. The init script only runs when the data volume is empty —
after editing it, run `down -v` then `up -d`.

### Service registry (Phase 1B)

```bash
docker compose -f infra/docker-compose.yml up -d eureka-server
open http://localhost:8761/          # dashboard
curl http://localhost:8761/actuator/health
```

The port is published to **`127.0.0.1` only** — an unauthenticated Eureka is a live map of every
service instance in the platform *and* accepts registrations from anyone who can reach it. Services
talk to it over the compose network at `http://eureka-server:8761/eureka/`. Production needs HTTP
Basic auth (or mTLS) plus TLS in front of it; that gap is documented, not implemented, in
[`services/eureka-server/README.md`](services/eureka-server/README.md).

> Building the eureka image needs **~3GB available to the Docker engine**. Below that the Gradle
> build stage's daemon is OOM-killed and reports only "Gradle build daemon disappeared unexpectedly".

Every platform service (gateway, auth-service, product-service, inventory-service) now has a real
container block in `infra/docker-compose.yml` — none are placeholders any more.

### Kafka (Phase 2 slice) — the event backbone

```bash
docker compose -f infra/docker-compose.yml up -d kafka
```

`apache/kafka:3.9.0`, **KRaft mode, single broker** — this platform's Kafka has never needed
ZooKeeper for its own metadata, and is deliberately independent of the ZooKeeper container above
(that one stays reserved for future app-level coordination primitives; conflating the two would
have made "which ZooKeeper is this for" an ambiguous question forever).

**No host port is published.** Nothing outside the compose network talks to Kafka directly in this
design — only `product-service` (producer) and `inventory-service` (consumer), both reached over
the internal `kafka:9092` listener — so unlike Postgres/Eureka/Redis there is nothing here for a
developer's machine to need exposed. Replication factor is 1 (single broker): a documented
durability gap, not a scale gap — see
[`docs/HLD-event-driven-inventory.md`](docs/HLD-event-driven-inventory.md) §8.

### Redis (Phase 2 slice) — auth-service's user-snapshot cache

```bash
docker compose -f infra/docker-compose.yml up -d redis
redis-cli -p 6379 ping   # PONG
```

The port is published to **`127.0.0.1` only** — same rationale as every other backing service a
developer might want to inspect directly (`redis-cli`) but that has no business being reachable
from outside the host. It holds nothing that cannot be reconstructed from `auth_db`, so a single
instance with no HA/replication is correct for this prototype, not a corner cut: a Redis outage
degrades to a cache miss per lookup, never an outage. See
[`services/auth-service/README.md`](services/auth-service/README.md#user-snapshot-cache-phase-2-slice)
for the fail-open contract.

### Dev JWT signing keystore (needed from Phase 1C)

```bash
./infra/scripts/generate-keystore.sh
# → infra/secrets/jwt-keystore.p12 (RSA-2048, PKCS#12, mode 600)
```

`infra/secrets/` and all keystore extensions are gitignored. **Never commit a private key.** Real
environments must source signing keys from a managed secret store (AWS Secrets Manager/KMS, Vault),
never from a file on a developer machine.

---

## Conventions

- **Package root:** `com.acme.ecommerce.<module>`
- **Layering:** controller → service → repository. Business logic never lives in controllers or
  repositories.
- **Transactions:** `@Transactional` at the **service** layer, sized to one unit of work.
- **Errors:** throw a typed `ApplicationException` subclass; never build an error response by hand.
  Never catch-and-swallow.
- **Time:** inject `TimeProvider`; never call `Instant.now()` directly in domain code.
  `Product.java` violated this from Phase 1E (`@PrePersist`/`@PreUpdate` called `Instant.now()`
  directly) — fixed in the Phase 2 write-endpoints change alongside the new `update()` method:
  timestamps are now explicit `now` parameters supplied by the service layer's `TimeProvider`,
  matching the pattern `Inventory.java` used from day one.
- **Secrets:** never in `application.yml`. Use environment variables / a secret manager.
- **Schema changes:** every one ships with its Flyway migration in the same change set.
- **Formatting:** run `./gradlew spotlessApply` before committing; CI enforces it.

---

## Roadmap

| Phase | Scope | Status |
|---|---|---|
| 1A | Foundation, build tooling, shared libraries, local infra | ✅ Complete |
| 1B | Eureka service registry | ✅ Complete |
| 1C | auth-service — login, JWT issuance, JWKS endpoint | ✅ Complete |
| 1D | API gateway — routing, auth offload | ✅ Complete |
| 1E | product-service — catalog CRUD | ✅ Complete (read endpoints only — see [`services/product-service/README.md`](services/product-service/README.md) for what's deliberately deferred) |
| 1F | Vue admin SPA | ✅ Complete |
| 2 (slice) | Event-driven inventory: product-service write endpoints, `inventory-service`, Kafka (KRaft) `product.events` topic, Redis-backed auth-service user-snapshot cache | ✅ Implemented, **prototype-grade** — see [`docs/HLD-event-driven-inventory.md`](docs/HLD-event-driven-inventory.md) §2 Non-Goals |

## Future work (Phase 2)

Phase 1 proves one request path end to end (browser → gateway → Eureka resolution → independent
JWT validation → a real database read/write) across every layer of the platform. A Phase 2 slice
has since landed on top of it — product-service write endpoints (optimistic locking, RBAC),
`inventory-service`, an event-driven Kafka backbone, and a Redis-backed auth-service cache — but it
is **prototype-grade by explicit design decision**, not a finished Phase 2: no schema registry, no
dead-letter queue, no exactly-once delivery, and no cross-service synchronous call between
product-service and inventory-service (see
[`docs/HLD-event-driven-inventory.md`](docs/HLD-event-driven-inventory.md) §2 Non-Goals and §7 for
the accepted consistency/availability tradeoffs, and
[ADR 0002](docs/adr/0002-event-driven-inventory-kafka-kraft-and-event-contract-duplication.md) for
why Kafka runs in KRaft mode and why the event contract is duplicated rather than shared). Still
deliberately out of scope: product
search/filtering, pricing/discount rules, categories/taxonomy, product images, a schema registry
for the Kafka event contract, and a shared `libs/common-events` library (the event DTO is
duplicated once, in producer and consumer, until a second consumer exists). See each service's
README for the specifics of what it defers.
