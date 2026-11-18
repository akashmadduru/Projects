# inventory-service (Phase 2 slice — prototype-grade)

The platform's **stock-level service**, standing entirely apart from the catalog: it owns its own
database, is reachable only through Kafka and its own HTTP CRUD, and has no synchronous dependency
on product-service in either direction.

- Stack: Spring MVC (**blocking, on virtual threads**), Spring Data JPA + Flyway, Spring Security
  resource server, Spring Kafka consumer — mirrors product-service's shape exactly, only the
  package root and the table differ.
- Port: `8083` (dev compose), `SERVER_PORT` to override.
- Registers with Eureka as **`INVENTORY-SERVICE`**; reachable publicly only through the gateway
  (`lb://INVENTORY-SERVICE`, `inventoryCb` circuit breaker, `Path=/api/inventory/**`).
- Database: `inventory_db`, schema `inventory`, role `inventory_service`, table `stock_levels`.

This service and its event stream are documented in full in
[`docs/HLD-event-driven-inventory.md`](../architecture/design/HLD-event-driven-inventory.md) and
[`docs/LLD-event-driven-inventory.md`](../architecture/design/LLD-event-driven-inventory.md). This README is the
short version for someone running or touching the service, not the design record.

---

## Current state (verified 2026-11-14)

This page was written for the Phase 2 slice. The consumer design and security sections still hold;
the "Scope" and "Deliberately absent" sections are marked where later phases changed them.
What the service does **now**:

| Area | Since | Endpoints |
|---|---|---|
| Stock rows per product, CRUD (`CATALOG_MANAGER`/`ADMIN` writes), provisioned from `product.events` | 2 | `/api/inventory/**` |
| Product name and SKU copied onto stock rows from `PRODUCT_CREATED`/`PRODUCT_UPDATED`; publishes `inventory.events` | 3 | — |
| Read-only lookup called synchronously by product-service's aggregate read | 3 | `GET /api/inventory/by-product/{productId}` |
| Stock reservations at checkout, released on cancel, payment failure or return | 6 | `/internal/inventory/reservations/**` (order-service only) |
| Per-variant stock | 7 | `/api/inventory/variants/**` |
| Warehouses, per-warehouse and per-seller stock, nearest-warehouse reservation | 9 | `/api/admin/warehouses`, `/api/admin/stock`, `/api/seller/{stock,warehouses}` |

All 23 endpoints: [endpoints.md](../reference/endpoints.md#inventory-service-23).
Stock rows: an order line with no stock row is **out of stock** (`INVENTORY_MISSING_STOCK_ROWS=REJECT`,
the default). product-service's catalog sync (every 15 minutes) calls `POST /internal/inventory/stock/ensure`
so every active product and variant has a platform row; a new row starts at `INVENTORY_DEFAULT_STOCK_QUANTITY`
(0 in production, 100 in the local stack). Known gaps: a new variant waits for the next sync
(TD-P7-22 (fixed 2026-11-18)); legacy `stock_levels` and `warehouse_stock` are both
written ([TD-P9-02](../techdebt/phase9.md#td-p9-02)).

## Scope, and what this service is not

`inventory.stock_levels` holds a `quantity` per `product_id`. It has **no** foreign key to
`product.products` (a different Postgres database). *In Phase 2* it held no product fields;
since Phase 3 it keeps a copy of the product name and SKU, updated from `product.events`.

| Method | Path | Auth | Notes |
|---|---|---|---|
| `GET` | `/api/inventory` | Any signed-in user | Paged. Default `size=20`, sort `productId ASC`, cap `size<=100` — `product_id` is `UNIQUE`, so pages have a stable, deterministic boundary across concurrent writes, mirroring product-service's `sku ASC` default for the same reason. |
| `GET` | `/api/inventory/{id}` | Any signed-in user | 404 as RFC 9457 problem+json when unknown. |
| `POST` | `/api/inventory` | `CATALOG_MANAGER` or `ADMIN` | `{productId, quantity}`. `201` / `409` if `productId` already has a row. |
| `PUT` | `/api/inventory/{id}` | `CATALOG_MANAGER` or `ADMIN` | `{quantity, version}`. `200` / `404` / `409` on a stale `version`. |
| `DELETE` | `/api/inventory/{id}` | `CATALOG_MANAGER` or `ADMIN` | `204` / `404`. |

Writes reuse the **same** `CATALOG_MANAGER`/`ADMIN` gate as product writes — no `INVENTORY_MANAGER`
role was introduced; confirmed unnecessary for this prototype's scope. `@PreAuthorize` sits on the
three write methods only (not on reads), matching product-service's posture that a rule should live
in exactly one place unless it genuinely differs by HTTP method on the same resource.

`quantity` has a DB `CHECK (quantity >= 0)` plus an application-level check in
`Inventory.adjustQuantity` — defense in depth, not redundancy for its own sake: the domain method
throws a typed `DomainRuleViolationException` (422) *before* a negative value ever reaches the
database, giving a caller a specific, structured error instead of a raw constraint-violation
translation.

Direct writes via this API (`POST`/`PUT`/`DELETE /api/inventory`) are independent of the Kafka event
stream below — an operator can adjust `quantity` after a stock count at any time. The event stream
only provisions/retires rows in lockstep with product lifecycle; it never owns `quantity`.

---

## The Kafka consumer

`ProductEventListener` (`@KafkaListener(topics = "product.events", groupId = "inventory-service")`,
`auto-offset-reset=earliest`) is the only caller of `InventoryService.provisionIfAbsent`/
`removeIfPresent` — no HTTP path reaches either method.

| Event | Effect |
|---|---|
| `PRODUCT_CREATED` | Provisions a `stock_levels` row with `quantity=0`, if one does not already exist for that `product_id`. |
| `PRODUCT_UPDATED` | Logged only. Name/price/active do not affect `quantity` — flagged in the HLD as an open question whether this event type is load-bearing at all in this consumer. |
| `PRODUCT_DELETED` | Removes the `stock_levels` row if present. |

**Idempotency, not exactly-once.** Kafka delivers at-least-once; this consumer absorbs redelivery
itself rather than relying on Kafka transactions. `provisionIfAbsent` does an existence check first
(cheap, avoids a noisy constraint-violation log line on the common sequential-redelivery case), and
falls back to catching `DataIntegrityViolationException` from the `UNIQUE(product_id)` constraint
for the rare case of two near-simultaneous deliveries racing past that check. Both paths converge on
the same outcome: a second `PRODUCT_CREATED` for a `product_id` that already has a row is a no-op,
never an error. `removeIfPresent` is symmetric — deleting an already-absent row is a no-op.

**Retries and dead-lettering.** *In Phase 2* there was no DLQ. Phase 10 wired every listener
factory to `DlqErrorHandlers` (3 retries, then `product.events.DLT`). The listener skips only an event
for a product that no longer exists; any other failure is retried and then dead-lettered. A consumer that is down resumes from its committed
offset; Kafka keeps the topic for the broker default retention (no override). There is no
consumer-lag alerting.

**`ProductEvent` is duplicated, not shared.** This service has its own `ProductEvent`/
`ProductEventType` in `com.acme.ecommerce.inventory.event`, structurally identical to
product-service's producer-side copy but compiled independently, with `payload` typed loosely
(untyped/`Object`, deserializable by Jackson) rather than as `ProductResponse` — inventory-service
has **no dependency on `product-service-api`**, deliberately, since the event contract is the only
coupling between the two services. One producer, one consumer: extracting a shared
`libs/common-events` module is a one-time, reviewable cost that isn't worth paying until a second
consumer of `product.events` exists.

---

## Deliberately absent

| Item | Status on 2026-11-14 |
|---|---|
| Schema registry; event class duplicated by hand | Still absent |
| Dead-letter queue | **Added in Phase 10** (`<topic>.DLT`); failures reach it since 2026-11-17 |
| Exactly-once delivery | Still absent; at-least-once, made safe by `UNIQUE(product_id)` |
| Cross-database foreign key to `product.products` | Still absent (Postgres cannot enforce one). A lost `PRODUCT_DELETED` leaves an orphan stock row. |
| Product fields on stock rows | **Added in Phase 3** (name and SKU) |
| Synchronous call between product- and inventory-service | **Added in Phase 3**, one direction only: product-service reads `GET /api/inventory/by-product/{id}` ([ADR 0003](../architecture/adr/0003-choreographed-catalog-saga-and-narrow-read-only-cross-service-call.md)). order-service also calls the reservation endpoints since Phase 6. |
| `INVENTORY_MANAGER` role | Still absent; writes use `CATALOG_MANAGER`/`ADMIN`, sellers manage only their own warehouses |

## Security — this service does not trust the gateway

Same posture as product-service and auth-service: the gateway already enforces
`authenticated()` for `/api/inventory/**`, but this service validates the bearer token again
anyway, independently, against the same JWKS, issuer and audience (`SecurityConfig` mirrors
product-service's byte for byte). The `X-User-*` headers the gateway injects are for logging and
correlation only and are never consulted for an authorization decision.

### JWKS resolution

Resolved through Eureka at fetch time against `lb://AUTH-SERVICE/.well-known/jwks.json`, not
captured at startup — no eager fetch, no `depends_on: auth-service`. `INVENTORY_JWKS_URI` is the
escape hatch for a registry-less deployment or the test suite.

---

## Configuration

| Variable | Default | Notes |
|---|---|---|
| `SERVER_PORT` | `8083` | |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/inventory_db` | Use `15432` if the compose stack runs with `POSTGRES_PORT=15432`. |
| `SPRING_DATASOURCE_USERNAME` | `inventory_service` | |
| `SPRING_DATASOURCE_PASSWORD` | *(local throwaway)* | No default under the `prod` profile. |
| `AUTH_JWT_ISSUER` | `https://auth.acme-ecommerce.internal` | **Must equal** auth-service's, the gateway's and product-service's issuer values. |
| `AUTH_JWT_AUDIENCES` | `ecommerce-platform` | **Must equal** the other three. |
| `INVENTORY_JWKS_SERVICE_ID` | `AUTH-SERVICE` | Eureka id whose JWKS is trusted. |
| `INVENTORY_JWKS_URI` | *(blank)* | Absolute URL; non-blank bypasses discovery. |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | The `product.events` consumer's broker list. Not a startup gate — see below. |
| `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE` | `http://localhost:8761/eureka/` | |

`spring.application.name` is **`inventory-service`**, a contract like product-service's: Eureka
uppercases it to `INVENTORY-SERVICE`, which is what the gateway's route table already targets.
Rename it and the route stops resolving silently — a 503 from `/__fallback/inventory` with nothing
pointing at the cause.

Kafka connectivity is **not** a startup dependency. Spring Kafka's listener container retries
connecting in the background; this service starts and serves CRUD reads/writes normally even if
Kafka is down, and catches up from its last committed offset once Kafka is reachable again. In
`deploy/local/docker-compose.yml`, `inventory-service`'s `depends_on: kafka` uses `service_started` (not
`service_healthy`) purely to order container creation for a cleaner `docker compose up` — it is a
developer convenience, not a correctness requirement.

---

## Schema

This section describes the Phase 2 baseline. Seven migrations exist now; the full table list is in
[data-model.md](../reference/data-model.md#all-databases).

`V1__inventory_baseline.sql` creates `inventory.stock_levels`: `id` (uuid PK), `product_id` (uuid,
`UNIQUE NOT NULL`, no FK), `quantity` (integer, `NOT NULL DEFAULT 0`, `CHECK (quantity >= 0)`),
`version` (bigint, JPA `@Version` — the same optimistic-concurrency mechanism `product.products`
uses), `created_at`/`updated_at` (timestamptz). Forward-only, like every other service's Flyway
history in this platform. No down migrations.

`Inventory.java` sets `created_at`/`updated_at` explicitly from a `TimeProvider`-supplied `now`
passed into `provision()`/`adjustQuantity()` — no `@PrePersist`/`@PreUpdate` at all, and no
`Instant.now()` anywhere in the class. This was built correctly from day one specifically so the
anti-pattern `Product.java` had (until the same changeset that added this service) was not
propagated into new code.

---

## Running locally

```bash
# 1. registry, 2. auth-service, 3. product-service — see services/product-service/README.md

# 4. kafka + redis (backing infra)
docker compose -f deploy/local/docker-compose.yml up -d kafka redis

# 5. this service
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15432/inventory_db \
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
java -jar services/inventory-service/inventory-service-app/build/libs/inventory-service-app-0.1.0-SNAPSHOT.jar

# 6. the edge
java -jar services/gateway/build/libs/gateway-0.1.0-SNAPSHOT.jar
```

Or `docker compose -f deploy/local/docker-compose.yml up -d inventory-service` (brings up its `postgres`,
`eureka-server` and `kafka` dependencies via compose's own dependency graph).

Through the gateway, with a real token:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@acme-ecommerce.internal","password":"..."}' | jq -r .accessToken)

curl -s http://localhost:8080/api/inventory -H "Authorization: Bearer $TOKEN"

curl -s -X POST http://localhost:8080/api/inventory \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"productId":"11111111-1111-4111-8111-111111111111","quantity":50}'
```

---

## Tests

`./gradlew :services:inventory-service:inventory-service-app:build`

- `InventoryServiceTest` — unit-level, mocked repository, fixed clock. Covers `provisionIfAbsent`/
  `removeIfPresent` idempotency directly, without a running Kafka broker.
- `ProductEventListenerTest` / `ProductEventListenerIntegrationTest` — the consumer's dispatch logic
  and its wiring against a real broker (Testcontainers Kafka), respectively.
- `InventoryEndpointsIntegrationTest` — `@SpringBootTest` + MockMvc over the real filter chain
  against a Testcontainers Postgres, mirroring `ProductEndpointsIntegrationTest`'s shape: anonymous
  → 401, valid token → 200, missing role on a write → 403, pagination, stale-version → 409.
