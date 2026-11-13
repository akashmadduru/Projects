# E-commerce Platform

A Spring Boot microservices platform with a Vue admin SPA, built as a Gradle multi-project monorepo.

> **Current state: Phases 1–5 implemented.**
> Foundation, Eureka, gateway, auth-service, product-service and the Vue admin SPA (Phase 1);
> event-driven inventory over Kafka (Phase 2); catalog enrichment and a choreographed saga
> (Phase 3); rating-service (Phase 4); and **customer accounts (Phase 5)** — email/password and
> Google sign-in, user/cart/order services, and a React storefront with checkout.
> Phases 2–5 are **prototype-grade by explicit design** (no schema registry, dead-letter queue,
> payment, or inventory reservation), and Phase 5 shipped **without automated tests** by decision.
> See the [Roadmap](#roadmap), [Known gaps](#known-gaps) and the
> [Tech Debt Register](techdebt/README.md).

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
| Admin frontend | Vue 3 (`apps/admin-product-catalog`) | — |
| Storefront | React 19, Vite 6, TypeScript 5, Tailwind 4.1, Zustand 5, React Router 7, Vitest + MSW (`apps/product-catalog`) | — |

These versions were verified by actually resolving and building them — see
[ADR 0001](docs/adr/0001-monorepo-and-build-tooling.md), which also records four real coordinate
defects the verification caught.

---

## Module map

```
system_design/
├── gradle/libs.versions.toml     Version catalog — single source of truth for ALL versions
├── docs/adr/                     Architecture decision records
├── techdebt/                     Tech debt register (TD-<AREA>-NN ids)
├── infra/
│   ├── docker-compose.yml        postgres + zookeeper + kafka + redis + all platform services
│   ├── postgres/init/            First-start SQL: per-service databases + owner roles
│   ├── zookeeper/                Notes — coordination only, NOT service discovery
│   └── scripts/                  generate-keystore.sh, create-customer-dbs.sql (existing volumes)
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
│   ├── inventory-service/        ✅ Event-driven inventory (Phase 2, prototype-grade)
│   │   ├── inventory-service-api/  ✅ Published contract (`InventoryClient`, `InventoryResponse`)
│   │   └── inventory-service-app/  ✅ Stock-level CRUD + Kafka consumer for `product.events` (:8083)
│   ├── rating-service/           ✅ Phase 4 — reviews and rating summaries (:8084)
│   ├── user-service/             ✅ Phase 5 — profile, addresses (:8085)
│   ├── cart-service/             ✅ Phase 5 — cart, wishlist, anonymous merge (:8086)
│   └── order-service/            ✅ Phase 5 — idempotent checkout, history, cancel (:8087)
│       (each of the three Phase 5 services and rating-service has -api and -app modules)
└── apps/                         (not Gradle modules)
    ├── product-catalog/          ✅ React storefront (:3000) — browse, auth, cart, checkout, account
    └── admin-product-catalog/    ✅ Vue 3 admin SPA — Phase 1F
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

## Architecture

```
 Browser (React storefront :3000 / Vue admin)
        |  Bearer access token (in memory) + HttpOnly refresh cookie
        v
 Gateway :8080 -- JWT validation (JWKS) · deny-by-default · circuit breakers · CORS
        |  lb:// via Eureka
        |-> auth-service :8081       users, JWT issuance, Google OIDC, refresh rotation -> auth_db
        |-> product-service :8082    catalog                                            -> product_db
        |-> inventory-service :8083  stock                                              -> inventory_db
        |-> rating-service :8084     reviews                                            -> rating_db
        |-> user-service :8085       profile, addresses                                 -> user_db
        |-> cart-service :8086       cart, wishlist                                     -> cart_db
        `-> order-service :8087      checkout, history, cancel                          -> order_db
                 |  Feign (read-only, 1s connect / 3s read, no retries)
                 `-> cart-service · user-service · product-service
 product-service --product.events (Kafka)--> inventory-service
```

Key patterns:

- **Database per service**, owned by its own Postgres role; schema managed by Flyway.
- **Choreography over orchestration** — async Kafka events for denormalization; synchronous calls are
  narrow and read-only. Consistency is eventual and prototype-grade (no schema registry or DLQ).
- **Stateless identity** — every customer endpoint takes the user from the JWT `sub`; resources owned
  by someone else return 404. Services validate tokens independently (audience-checked).
- **Idempotent checkout** — `POST /api/orders {addressId}` with an `Idempotency-Key`. The server
  re-prices every line from product-service (sale price if > 0, else list price); client cart
  prices are display-only. Reusing a key with a different body returns 422 `IDEMPOTENCY_KEY_REUSED`.
- **Errors** — RFC 9457 problem+json with stable `ErrorCode`s; 5xx never echoes exception messages.

### Customer accounts (Phase 5)

- **Session:** access token in memory only; refresh token is an HttpOnly cookie. On a 401 the client
  makes one single-flight `POST /api/auth/refresh` and replays the request once; failure redirects
  to `/login?next=...`. The session is restored on page load via the same refresh call.
- **Google SSO:** reuses auth-service's OIDC leg (no auth-service code change). The button goes to
  `http://localhost:8081/oauth2/authorization/google?redirect_uri=<origin>/auth/callback`, then
  `/auth/callback` completes the normal bootstrap. Allowed redirects are set by
  `AUTH_ALLOWED_REDIRECT_URIS`.
- **Anonymous shopping:** cart and wishlist are local until login, then merged server-side and the
  local copy cleared; logout clears them.
- **Storefront routes:** `/login`, `/register`, `/auth/callback`, `/checkout`, `/orders/:id`,
  `/account`, `/account/addresses`, `/account/orders`.
- **Not included:** payment, inventory reservation at checkout, shipping/tax, order events.

Details: [Phase5-Feature](docs/Phase5-Feature.md) · [Phase5-Changes](docs/Phase5-Changes.md) ·
[Phase5-DecisionLog](docs/Phase5-DecisionLog.md) · diagrams
[13](docs/diagrams/13-auth-login-refresh-google-sequence.md),
[14](docs/diagrams/14-checkout-sequence.md),
[15](docs/diagrams/15-customer-data-er.md),
[16](docs/diagrams/16-customer-components.md).

---

## Backend Documentation

Complete documentation for the backend architecture (product, inventory, rating, plus the Phase 5 customer services):

| Document | Audience | Coverage |
|----------|----------|----------|
| **[Backend-Architecture.md](docs/Backend-Architecture.md)** | Architects, senior engineers | System overview, service responsibilities, data flow, consistency model, deployment topology |
| **[Backend-API.md](docs/Backend-API.md)** | API consumers, frontend engineers | All 20+ endpoints, request/response examples, authentication, error codes, pagination |
| **[Backend-EventSystem.md](docs/Backend-EventSystem.md)** | Event consumers, backend engineers | Event choreography, Kafka topics, event types, idempotency, failure scenarios |
| **[Backend-DataModel.md](docs/Backend-DataModel.md)** | Backend engineers, DBAs | Database schemas, entity relationships, denormalization strategy, indexes, seed data |
| **[Backend-Operations.md](docs/Backend-Operations.md)** | Ops engineers, SREs | Local dev setup, migrations, health checks, monitoring, troubleshooting |
| **[Phase 5 docs](docs/Phase5-Feature.md)** | All | Customer accounts: feature overview, changes, decision log, run instructions |
| **[techdebt/](techdebt/README.md)** | All | Tech debt register with severity, effort and stable IDs |
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
| `user_db` / `cart_db` / `order_db` | `user_service` / `cart_service` / `order_service` | `USER_DB_PASSWORD` / `CART_DB_PASSWORD` / `ORDER_DB_PASSWORD` from `infra/.env` |

Those passwords are throwaway local values. The init script only runs when the data volume is empty.
On an **existing** volume, create the Phase 5 databases without wiping data (re-runnable):

```bash
docker exec -i ecommerce-postgres psql -U postgres < infra/scripts/create-customer-dbs.sql
```

### Running the customer stack (Phase 5)

```bash
# infra/.env: USER_DB_PASSWORD, CART_DB_PASSWORD, ORDER_DB_PASSWORD (see infra/.env.example);
# for Google sign-in also GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET
docker compose -f infra/docker-compose.yml up -d --build auth-service user-service cart-service order-service
cd apps/product-catalog && npm install && npm run dev      # http://localhost:3000 (strictPort)
```

Allow ~30–40 s after a restart before the gateway stops answering 503 (TD-BE-08). The Google
console redirect URI must be `http://localhost:8081/login/oauth2/code/google`. Knobs:
`VITE_AUTH_BASE_URL`, `VITE_GOOGLE_SSO_ENABLED=false`, `AUTH_REGISTRATION_ENABLED`.

### Phase 6 run notes

- New services: payment-service `8088`, promotion-service `8089`, notification-service `8090`; Mailpit (dev mail catcher) UI at http://127.0.0.1:8025.
- New env vars in `infra/.env`: `INTERNAL_API_TOKEN` (shared service-to-service secret), `PAYMENT_DB_PASSWORD`, `PROMOTION_DB_PASSWORD`, `STRIPE_SECRET_KEY` / `STRIPE_PUBLISHABLE_KEY` / `STRIPE_WEBHOOK_SECRET`, `STOREFRONT_BASE_URL`.
- Existing Postgres volume: create the new databases with `docker exec -i ecommerce-postgres psql -U postgres < infra/scripts/create-customer-dbs.sql`.
- Payments run in **SIMULATED** mode until Stripe keys are set; the Stripe path has never been exercised ([TD-P6-02](techdebt/phase6.md#td-p6-02)).
- Seeded coupons: `WELCOME10`, `FLAT100`, `SAVE5`, `FESTIVE20`.
- Integration run: 98 of 99 e2e checks passed (the failure was a script bug). Images were built offline because Maven Central returned 403; run `docker compose -f infra/docker-compose.yml up --build` once it is reachable ([TD-P6-06](techdebt/phase6.md#td-p6-06)).
- The dev DB still holds test data (an admin user, `p6e2e-*` users, a deactivated test product and coupons); see [TD-P6-25](techdebt/phase6.md#td-p6-25).

### Phase 7 notes

- No new services. New endpoint families: product-service `/api/products/suggest`, `/facets`, `/batch`, `/slug/{slug}`, `/{id}/variants` (admin PUT), `/{id}/views`, `/{id}/also-viewed`, and public `/sitemap.xml`; promotion-service `/api/promotions/offers`, `/deals/active` and admin `/api/admin/deals` (plus `/internal/promotions/deals/*`); rating-service questions/answers/upvotes, review media (`/api/ratings/{id}/media`) and review filters (`stars`, `verified`, `withMedia`); cart-service `/api/alerts`; order-service `/api/recommendations/products/{id}/bought-together`; user-service `/api/users/me/recently-viewed`; inventory-service variant stock (`/api/inventory/variants/{id}`, internal `/internal/inventory/variants`).
- Seeded demo data: 3 products with 4-6 variants each, 4 active deals ending in 1-3 days, 3 bank offers (HDFC, ICICI, Axis) and EMI plans (display-only).
- Sitemap: `GET http://localhost:8080/sitemap.xml`; its URLs use `product.storefront-base-url` (default `http://localhost:3000`).
- Review photos are stored on the `rating-media` docker volume (`/data/media`); rating-service runs as root so the fresh volume is writable ([TD-P7-15](techdebt/phase7.md#td-p7-15)). `INTERNAL_API_TOKEN` is now also set for product-service.
- Not built: SSR/prerender and 360-degree images ([TD-P7-16](techdebt/phase7.md#td-p7-16), [TD-P7-17](techdebt/phase7.md#td-p7-17)). Search, facets and relevance run in memory over capped id lists ([TD-P7-03](techdebt/phase7.md#td-p7-03)); storefront main chunk is 584 kB ([TD-P7-18](techdebt/phase7.md#td-p7-18)).
- Integration run: 68 of 68 Phase 7 e2e checks and 99 of 99 Phase 6 regression checks passed. The dev DB still holds test data ([TD-P7-20](techdebt/phase7.md#td-p7-20)).
- Process risk: a `git stash` by an unknown actor swept uncommitted work and was restored by a 3-way merge; `stash@{0}` is kept as a backup ([TD-P7-02](techdebt/phase7.md#td-p7-02)). Much of the Phase 6-7 work is still uncommitted, so avoid state-changing git commands until it is.

### Phase 8 notes

- No new services. payment-service: wallet (`/api/wallet`), gift cards (`/api/gift-cards`, redeem), payment purposes `ORDER|MEMBERSHIP|GIFT_CARD`, internal `/internal/wallet/debit|credit`. promotion-service: reward coins ledger (`/api/coins`, earned on DELIVERED from `order.events`, 20% of subtotal redeemable at checkout, members earn 2x), members-only deals. order-service: checkout `useWallet`/`coinsToRedeem` and `WALLET` method, member free shipping, `/api/orders/buy-again`, Subscribe & Save (`/api/subscriptions`, 5% off, daily job).
- user-service: membership plans (`PLUS_MONTHLY`, `PLUS_YEARLY`; `/api/membership`), notification preferences, referrals (`/api/referrals`, claim with `?ref=`). cart-service: lists with share links (`/api/lists`, public `/api/lists/shared/{token}`), hourly abandoned-cart reminders. notification-service: in-app inbox (`/api/notifications`), web push (`/api/push/**`), admin campaigns (`/api/admin/campaigns`); it now has its own database.
- Dev triggers (need `X-Internal-Token`): `POST /internal/subscriptions/run-due` (accepts `?date=` to simulate days) and `POST /internal/cart/reminders/run`.
- New env vars in `infra/.env`: `NOTIFICATION_DB_PASSWORD`, and optional `VAPID_PUBLIC_KEY` / `VAPID_PRIVATE_KEY` / `VAPID_SUBJECT` (VAPID keys are generated and stored in `notification_db` on first start if unset). Existing Postgres volume: re-run `infra/scripts/create-customer-dbs.sql` to create `notification_db`.
- Storefront: Account has Wallet, Gift cards, Coins, Membership, Referrals, Subscriptions, Lists, Notifications and preferences, Buy again; header bell; checkout wallet/coins controls; shared list page `/lists/shared/:token`. Admin: Campaigns view.
- Known gaps: web push never reached a real push service ([TD-P8-04](techdebt/phase8.md#td-p8-04)); campaign audience covers only users with a profile row and sends in-process ([TD-P8-02](techdebt/phase8.md#td-p8-02), [TD-P8-03](techdebt/phase8.md#td-p8-03)); gift card codes are plaintext with no redeem rate limit ([TD-P8-06](techdebt/phase8.md#td-p8-06)); wallet credit-back is best effort ([TD-P8-07](techdebt/phase8.md#td-p8-07)); coins never actually expire ([TD-P8-09](techdebt/phase8.md#td-p8-09)).
- Integration run: 78 of 78 Phase 8 e2e checks, 99 of 99 Phase 6 and 68 of 68 Phase 7 regression checks passed. The dev DB still holds `p8-*` test data and about 230 Mailpit messages ([TD-P8-19](techdebt/phase8.md#td-p8-19)).

### Phase 9 notes

- New service: **seller-service** on `:8091` (Eureka `seller-service`, database `seller_db`, role `seller_service`, schema `seller`). It owns seller onboarding/KYC, seller feedback, the settlement ledger, payouts and sponsored-ad campaigns. Offers and the buy box live in product-service; warehouses and per-warehouse stock in inventory-service; shipments in order-service; seller portal and admin seller/payout/warehouse screens in the admin SPA.
- Onboarding flow: a signed-in user applies (`POST /api/sellers/apply`: legal/display name, GSTIN, PAN, bank account, IFSC, pickup address) -> `SUBMITTED` -> an admin approves or rejects (`POST /api/admin/sellers/{id}/decision`) -> on approval auth-service grants `ROLE_SELLER` and the user opens the seller portal (refresh the token or sign in again to pick up the role). Admins can also suspend. Public profile: `GET /api/sellers/{slug}`.
- Platform seller: the fixed id `00000000-0000-0000-0000-00000000a11e` ("Acme Retail", 0% commission) owns every pre-existing product, offer and stock row. Seeded platform warehouses: `WH-BLR` (560001), `WH-DEL` (110001), `WH-MUM` (400001). Sellers may create their own warehouses and can only stock those.
- Buy box: among active offers with stock, lowest `effective price * (1 + 0.05 * (5 - sellerRating)) + 0.5% per dispatch day` wins; ties go to the platform. It follows the selected variant. Reservation picks the nearest warehouse with stock (same 3-digit pincode prefix, then same first digit, then any) and may split a line; each warehouse becomes its own shipment per seller.
- Money: on a shipment's `DELIVERED` the ledger books the sale, a commission (default 10%) and a fixed fee (10 INR / 0.25 other); returns reverse sale and commission once per return. Payouts are a simulated bank transfer triggered by an admin (`POST /api/admin/payouts/run`); sums entries older than 7 days (config). For dev, pass `minAgeDays=0` to include fresh entries.
- Sponsored ads: sellers create keyword campaigns (`/api/seller/ads`); product-service injects at most 2 sponsored rows on the first page of search/category results; the storefront sends impression and click beacons. Bulk upload: `POST /api/seller/products/bulk` (CSV, up to 1000 rows / 2 MB).
- New env vars in `infra/.env`: `SELLER_DB_PASSWORD` (throwaway dev value in `.env.example`) and `SELLER_DATA_KEY` (base64, 32 bytes; encrypts bank account numbers with AES-GCM; compose refuses to start seller-service if unset). Existing Postgres volume: re-run `infra/scripts/create-customer-dbs.sql` to create `seller_db`.
- Dev seller account: the integration run used throwaway `p9e-*` sellers (see [TD-P9-19](techdebt/phase9.md#td-p9-19)); no persistent dev seller login is documented. Create one by registering, applying as a seller and approving it as an admin. No passwords are recorded here.
- Known gaps: platform stock still has two sources of truth ([TD-P9-02](techdebt/phase9.md#td-p9-02)); seller products go live without moderation ([TD-P9-03](techdebt/phase9.md#td-p9-03)); the ledger consumer starts at `latest` and the delivery fee is not reversed on returns ([TD-P9-07](techdebt/phase9.md#td-p9-07)); KYC is format-only ([TD-P9-09](techdebt/phase9.md#td-p9-09)); ad clicks are unauthenticated ([TD-P9-10](techdebt/phase9.md#td-p9-10)); wishlist/saved items are not offer-keyed ([TD-P9-15](techdebt/phase9.md#td-p9-15)).
- Integration run: 62 of 62 Phase 9 e2e checks, 99 of 99 Phase 6, 68 of 68 Phase 7 and all Phase 8 regression checks passed. The dev DB still holds test data ([TD-P9-19](techdebt/phase9.md#td-p9-19)).

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

Every platform service (gateway, auth, product, inventory, rating, user, cart, order) has a real
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
| 3 | Catalog enrichment and choreographed saga | ✅ Implemented — [HLD](docs/HLD-catalog-enrichment-and-saga.md), [ADR 0003](docs/adr/0003-choreographed-catalog-saga-and-narrow-read-only-cross-service-call.md) |
| 4 | rating-service (reviews, summaries; storefront review UI) | ✅ Implemented |
| 5 | Customer accounts: login/register, Google SSO, user/cart/order services, storefront checkout | ✅ Implemented, **no automated tests** — see [Phase5-Feature](docs/Phase5-Feature.md) |
| 6 | Sell for real: payments + COD, stock reservation, order lifecycle & tracking, admin orders, shipping, GST invoice, notifications, returns, coupons, OTP/password reset | ✅ Implemented (prototype-grade, no tests) — [Product Roadmap](docs/Product-Roadmap.md#phase-6-sell-for-real-must-have-before-any-real-customer), debt in [techdebt/phase6.md](techdebt/phase6.md) |
| 7 | Discovery & conversion: autocomplete, typo-tolerant search, variants, facets, offers, deals, recommendations, Q&A, alerts | ✅ Implemented (prototype-grade, no tests) — [Product Roadmap](docs/Product-Roadmap.md#phase-7-discovery-and-conversion), debt in [techdebt/phase7.md](techdebt/phase7.md) |
| 8 | Retention & loyalty: membership, coins, wallet/gift cards, subscriptions, referrals, notification centre + push + campaigns, lists, abandoned-cart reminders, personalised home | ✅ Implemented (prototype-grade, no tests) — [Product Roadmap](docs/Product-Roadmap.md#phase-8-retention-and-loyalty), debt in [techdebt/phase8.md](techdebt/phase8.md) |
| 9 | Marketplace & sellers: onboarding/KYC, seller portal, buy box, payouts, ads, warehouses | ✅ Implemented (prototype-grade, no tests) — [Product Roadmap](docs/Product-Roadmap.md#phase-9-marketplace-and-sellers), debt in [techdebt/phase9.md](techdebt/phase9.md) |
| 10 | Trust, support & scale: help desk, 2FA, fraud checks, privacy controls, i18n, PWA, observability | ⏳ Planned |

The full gap analysis against Amazon / Flipkart, ranked by importance with at least 10 features per
phase, is in [docs/Product-Roadmap.md](docs/Product-Roadmap.md). From Phase 6 on, features ship
without tests or per-feature HLD/LLD; every shortcut is logged in the
[Tech Debt Register](techdebt/README.md).

## Known gaps

Highest-severity items from the [Tech Debt Register](techdebt/README.md): no outbox, so stock/coupon/event
side effects after an order commit can be lost (TD-P6-01); Stripe live mode never exercised, only
SIMULATED (TD-P6-02); a late payment on a cancelled order is not reliably refunded (TD-P6-03); products
without an inventory row are treated as unlimited (TD-P6-04); no tests for Phase 5/6 services and
storefront flows, and existing auth-service tests no longer compile (TD-P6-05, TD-TEST-01/02); a real
Google client secret in local `infra/.env` to rotate (TD-SEC-01); admin SPA Google login broken
(TD-SEC-02). Phase 5/6 backend builds use `-x test`.

## Future work

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
