# Backend Architecture

**Coverage:** the platform map below is current as of 2026-11-14 (Phases 1–10). The detailed sections
after it describe product, inventory and rating as designed in Phases 1–4. Later services are
described in their [phase pages](../phases/README.md), and every endpoint is listed in [endpoints.md](../reference/endpoints.md).

## Platform map (current)

```
 Browser: storefront (React :3000) / admin + seller portal (Vue :5173)
        |  Bearer access token (in memory) + HttpOnly refresh cookie
        v
 gateway :8080   JWT validation (JWKS), deny-by-default, role rules for /api/admin/**, CORS, circuit breakers
        |  lb:// via eureka-server :8761
        v
 13 domain services, each with its own Postgres database and role
        |  Feign: narrow synchronous calls, token relayed; /internal/** guarded by X-Internal-Token
        |  Kafka (KRaft): domain events, consumers idempotent, dead-letter handlers in common-web
        v
 Postgres 17 · Redis (auth user cache) · Mailpit (dev mail) · Jaeger (traces)
```

| Service | Port | Database | Owns | Since |
|---|---|---|---|---|
| gateway | 8080 | — | Edge routing and auth offload | 1D |
| eureka-server | 8761 | — | Service registry | 1B |
| auth-service | 8081 | auth_db | Credentials, JWT/JWKS, refresh rotation, Google OIDC, OTP, 2FA, sessions, admin users and roles | 1C |
| product-service | 8082 | product_db | Catalog, categories, variants, search and facets, offers and buy box, views, sitemap, FX rates | 1E |
| inventory-service | 8083 | inventory_db | Stock, reservations, warehouses, per-warehouse stock | 2 |
| rating-service | 8084 | rating_db | Reviews, votes, Q&A, review media, fraud flags | 4 |
| user-service | 8085 | user_db | Profile, addresses, membership, referrals, preferences, recently viewed, privacy export and deletion | 5 |
| cart-service | 8086 | cart_db | Cart, wishlist, save for later, lists, alerts, abandoned-cart reminders | 5 |
| order-service | 8087 | order_db | Checkout, lifecycle, shipments, returns, invoices, subscriptions, buy again, risk scoring, exchange | 5 |
| payment-service | 8088 | payment_db | Payments (Stripe / SIMULATED), COD, refunds, wallet, gift cards | 6 |
| promotion-service | 8089 | promotion_db | Coupons, deals, offers, coins, exchange quotes | 6 |
| notification-service | 8090 | notification_db | Email, SMS stub, in-app inbox, web push, campaigns | 6 |
| seller-service | 8091 | seller_db | Seller onboarding/KYC, feedback, ledger, payouts, sponsored ads | 9 |
| support-service | 8092 | support_db | Help centre, tickets, chatbot, audit log | 10 |

| Topic | Producer | Consumers |
|---|---|---|
| `product.events` | product-service | inventory-service, cart-service |
| `inventory.events` | inventory-service | product-service, cart-service |
| `rating.events` | rating-service | product-service |
| `order.events` | order-service | notification-service, promotion-service, seller-service |
| `user.events` | user-service | auth, cart, notification, order, payment, promotion, rating, seller, support |
| `audit.events` | auth-service and `common-web` `AuditPublisher` | support-service |

Phase 5 to 10 changes to the principles below: order-service orchestrates checkout synchronously
(Phase 5 decision D5); there is no transactional outbox yet, so post-commit events can be lost (TD-P6-01).


## Table of Contents

1. [System Overview](#system-overview)
2. [Service Architecture](#service-architecture)
3. [Data Flow](#data-flow)
4. [Consistency Model](#consistency-model)
5. [Deployment Topology](#deployment-topology)
6. [Cross-Service Communication](#cross-service-communication)

---

## System Overview

The e-commerce platform is built as a microservices architecture with three independent services orchestrated through event choreography and Kafka. Each service owns its database (no shared database), maintains its own schema via Flyway migrations, and communicates with other services through:

1. **Asynchronous events** (Kafka) — for service-to-service notifications and denormalization
2. **Synchronous reads** (Feign HTTP clients) — narrowly scoped, read-only cross-service calls
3. **No orchestration layer** — choreography only; services react to events independently

### Core Principles

- **Database-per-service:** Three independent Postgres databases, each owned by its service role
- **Eventual consistency:** Denormalized fields (e.g., product ratings on a product) are eventually consistent, not immediately consistent
- **No ping-pong:** When a service updates denormalized data from an event, it never publishes a reply event
- **Optimistic locking:** All mutable entities use JPA `@Version` for concurrency control
- **No lazy loading:** All domain entities are free of associations (no `@ManyToOne`, no risk of N+1)
- **TimeProvider abstraction:** No `Instant.now()` in domain code; timestamps are injected

---

## Service Architecture

### Product Service

**Port:** 8082  
**Database:** `product_db` (schema: `product`)  
**Responsibility:** Catalog management — products, categories, and metadata

#### Entities
- **Product** — merchandising item (sku, name, price, category, seller, media, ratings)
- **Category** — one level of product categorization (with optional parent)
- **ProductImage** — URLs for product images, ordered per-product

#### Key Features
- Catalog list/detail reads (paged)
- Product write endpoints (create, update, delete) with `@PreAuthorize("hasAnyRole('CATALOG_MANAGER','ADMIN')")`
- Denormalized inventory quantity (`last_known_quantity`) — updated from inventory.events
- Denormalized rating fields (`average_rating`, `rating_count`, `rating_distribution`) — updated from rating.events
- Aggregate read endpoint (`GET /api/products/catalog/products/{id}`) that synchronously joins inventory via Feign client (with fallback if inventory unreachable)
- Event publishing: `ProductEvent` (PRODUCT_CREATED, PRODUCT_UPDATED, PRODUCT_DELETED) to `product.events` Kafka topic
- Event consumption: listens to `inventory.events` and `rating.events` to update denormalized fields

#### Optimistic Locking
All writes enforce version matching. A client must echo the version it read; a stale version returns 409 RESOURCE_CONFLICT.

---

### Inventory Service

**Port:** 8083  
**Database:** `inventory_db` (schema: `inventory`)  
**Responsibility:** Stock-level management, warehouse allocation, and inventory state tracking

#### Entities
- **Inventory** (stock_levels table) — one row per product, tracking quantity on hand, reserved quantity, low-stock threshold, warehouse label, and denormalized product metadata

#### Key Features
- Stock-level list/detail reads (paged)
- Inventory write endpoints (create, update, delete) with role gating
- Denormalized product metadata (`product_name`, `product_sku`) — updated from product.events
- Auto-provisioning: when a PRODUCT_CREATED event arrives, automatically creates an inventory row with `initialQuantity`
- Inventory status tracking: IN_STOCK, LOW_STOCK, OUT_OF_STOCK (calculated/updated on writes)
- Reserved-quantity tracking: items allocated but not yet shipped
- Event publishing: `InventoryEvent` (INVENTORY_QUANTITY_CHANGED, INVENTORY_LOW_STOCK, INVENTORY_PROVISIONING_FAILED) to `inventory.events` Kafka topic
- Event consumption: listens to `product.events` (PRODUCT_CREATED, PRODUCT_UPDATED, PRODUCT_DELETED)

#### Unique Constraint
`stock_levels.product_id` is UNIQUE. This constraint is the idempotency mechanism for replayed PRODUCT_CREATED events: if the same product is provisioned twice, the second attempt is rejected by the database, not by application logic.

---

### Rating Service

**Port:** 8084  
**Database:** `rating_db` (schema: `rating`)  
**Responsibility:** Product ratings, reviews, and helpful-vote tracking

#### Entities
- **Rating** — one rating/review per user per product (rating_value 1–5, optional review text)
- **RatingVote** — helpful/unhelpful votes on a rating, with UNIQUE(rating_id, user_id) constraint

#### Key Features
- Rating list/detail reads (paged by product)
- Rating write endpoints (create, update, delete) with authentication
- Verified-purchase flag (placeholder for future order-service integration)
- Helpful/unhelpful vote tracking with idempotency via unique constraint
- Denormalized vote counts on Rating (`helpful_count`, `unhelpful_count`)
- Event publishing: `RatingEvent` (RATING_ADDED, RATING_UPDATED, RATING_DELETED) to `rating.events` Kafka topic
- Event consumption: none; rating-service is not a consumer

---

## Data Flow

### Product Creation Flow (Saga)

```
1. Client: POST /api/products {sku, name, price, ...}
           ↓
2. product-service write endpoint (secured: CATALOG_MANAGER/ADMIN)
           ↓
3. ProductCatalogService.create()
   - Insert Product into product_db with now()
   - Insert ProductImage rows if provided
   - Flush to get generated UUID
           ↓
4. ProductEventPublisher (fires AFTER @Transactional commit)
   - Publish ProductEvent(PRODUCT_CREATED, product, initialQuantity)
   - Publish to product.events Kafka topic
           ↓
5. inventory-service ProductEventListener (consumer group: inventory-service)
   - Receive PRODUCT_CREATED event
   - Call InventoryService.provisionWithQuantity(productId, initialQuantity, name, sku)
   - Insert Inventory row into inventory_db
   - No reply event (no ping-pong)
           ↓
6. Response to client: 201 CREATED with ProductResponse
```

### Inventory Update Flow

```
1. Client: PUT /api/inventory/{id} {quantity, version, ...}
           ↓
2. inventory-service write endpoint
           ↓
3. InventoryService.update()
   - Load Inventory by id
   - Check version matches request.version()
   - Call adjustQuantity(newQuantity, now())
   - Save to inventory_db
           ↓
4. InventoryEventPublisher (fires AFTER @Transactional commit)
   - Publish InventoryEvent(INVENTORY_QUANTITY_CHANGED, quantity, ...)
   - Publish to inventory.events Kafka topic
           ↓
5. product-service InventoryEventListener (consumer group: product-service-inventory-listener)
   - Receive INVENTORY_QUANTITY_CHANGED event
   - Call ProductCatalogService.applyInventorySignal(productId, newQuantity)
   - Update Product.lastKnownQuantity, updatedAt
   - No reply event
           ↓
6. Response to client: 200 OK with InventoryResponse
```

### Rating Creation Flow

```
1. Client: POST /api/ratings/products/{productId} {ratingValue, reviewText, ...}
           ↓
2. rating-service write endpoint (secured: isAuthenticated)
   - Extract userId from JWT token
           ↓
3. RatingService.create()
   - Insert Rating into rating_db with now()
   - Flush to get generated UUID
           ↓
4. RatingEventPublisher (fires AFTER @Transactional commit)
   - Publish RatingEvent(RATING_ADDED, ratingId, productId, rating)
   - Publish to rating.events Kafka topic
           ↓
5. product-service RatingEventListener (consumer group: product-service-rating-listener)
   - Receive RATING_ADDED event
   - Call ProductCatalogService.updateRatingDenormalization(productId)
   - Fetch rating summary from rating-service via Feign client (or cache)
   - Update Product.averageRating, ratingCount, ratingDistribution
   - No reply event
           ↓
6. Response to client: 201 CREATED with RatingResponse
```

### Aggregate Read (Product with Inventory)

```
Client: GET /api/products/catalog/products/{productId}
         ↓
product-service ProductController.getAggregate(id)
         ↓
CatalogAggregateService.getAggregate(id)
         ├─ 1. Load Product from product_db (simple SELECT by id)
         └─ 2. Call InventoryFeignClient.findByProductId(id) (cross-service Feign call)
              ├─ Success: include inventory in response
              ├─ Timeout (1.5s read-timeout):
              │  └─ Circuit breaker logs failure, returns inventory: null
              ├─ 404 (product not yet provisioned):
              │  └─ Circuit breaker ignores (normal case), returns inventory: null
              └─ 5xx error:
                 └─ Circuit breaker logs failure, may open if rate > 50%

Response: ProductCatalogAggregateResponse {product, inventory}
```

---

## Consistency Model

### Eventual Consistency

Denormalized fields are **eventually consistent**. The system operates with the following guarantees:

1. **Write consistency window:** When a product is updated, the update is persisted immediately. Dependent denormalized fields (e.g., inventory quantity shown on a product) reflect the state at the time of event publication, not the current state.

2. **Event ordering:** Events from a single source (e.g., inventory.events) are consumed in order per partition (single partition per topic in this prototype). A product's denormalized inventory quantity will always advance monotonically, never regress.

3. **Duplicate handling:** If the same event is delivered twice (Kafka's at-least-once guarantee), the UNIQUE constraint or idempotent field writes prevent corruption:
   - Inventory: `product_id` UNIQUE prevents duplicate provisions
   - Product denormalization: `last_known_quantity` is overwritten with the same value
   - Rating: `(rating_id, user_id)` UNIQUE prevents duplicate vote records

4. **Missing events:** If an event is never delivered (Kafka durability gaps, consumer failure), the denormalized field stays stale. Mitigation strategies (periodic cache refresh, explicit sync operations) are out of scope for this prototype.

### Transactional Boundaries

Each service's write operations are ACID within its own database:
- Product create/update/delete is atomic
- Inventory adjustment is atomic
- Rating creation and vote recording are independent transactions

**Cross-service writes are NOT atomic.** If a product creation publishes an event but the inventory-service consumer crashes before processing it, the product exists without an inventory row until the consumer restarts and reprocesses the event.

---

## Deployment Topology

### Containers

```
infra/docker-compose.yml

postgres:5432
├── auth_db (auth-service)
├── product_db (product-service)
├── inventory_db (inventory-service)
└── rating_db (rating-service)

kafka:9092
├── product.events (topic, single partition)
├── inventory.events (topic, single partition)
└── rating.events (topic, single partition)

redis:6379 (auth-service user-snapshot cache)

eureka-server:8761 (service registry)

product-service:8082 (localhost:8082)
inventory-service:8083 (localhost:8083)
rating-service:8084 (localhost:8084)
gateway:8080 (localhost:8080)
auth-service:8081 (localhost:8081)
```

### Network

- Services communicate over the `docker compose` network using internal DNS (e.g., `kafka:9092`, `postgres:5432`)
- No container publishes Kafka ports to the host (only Postgres, Eureka, Redis for developer inspection)
- The gateway routes external requests to services via Eureka discovery

### Health Checks

Each service exposes:
- `GET /actuator/health` — liveness + readiness probes (used by Eureka)
- `GET /actuator/info` — service version/build info
- `GET /actuator/prometheus` — Prometheus metrics

Eureka checks health status; if a service reports DOWN, it is removed from the registry.

---

## Cross-Service Communication

### Feign Client Pattern

The platform uses Feign for read-only, synchronous calls with explicit resilience.

**Example:** Product-service's aggregate read calls inventory-service.

```java
@FeignClient(name = "inventory-service", fallback = InventoryServiceClientFallback.class)
public interface InventoryFeignClient {
    @GetMapping("/api/inventory/by-product/{productId}")
    InventoryResponse findByProductId(@PathVariable UUID productId);
}
```

**Resilience:**
- Feign timeout: 1.5s read-timeout, 1s connect-timeout
- Circuit breaker (resilience4j) wraps the call
- Fallback returns `inventory: null` on failure (not an error)
- 404 (normal case: product not yet provisioned) does not trip the breaker

**Constraints:**
- Only reads; no writes via Feign
- Only product-service → inventory-service (for the aggregate read)
- No transitive calls (X → Y → Z chains)

### Kafka Event Choreography

All asynchronous, service-to-service updates flow through Kafka.

**Producers:**
- product-service publishes to `product.events`
- inventory-service publishes to `inventory.events`
- rating-service publishes to `rating.events`

**Consumers:**
- inventory-service consumes `product.events` (to auto-provision, denormalize product metadata)
- product-service consumes `inventory.events` (to denormalize quantity)
- product-service consumes `rating.events` (to denormalize ratings)

**Guarantees:**
- At-least-once delivery (Kafka semantics)
- Order preserved per partition (single partition per topic)
- Consumer-group offsets managed by Spring Kafka (auto-commit on successful consumption)
- No schema registry (event contract duplicated in each producer/consumer module)

---

## Observability

### Logging

All services log with correlation IDs (X-Correlation-Id header) in the MDC, enabling request tracing across services.

```
2026-11-09 10:23:45.123 INFO [a1b2c3d4-e5f6-7890-1234-567890abcdef] ProductCatalogService - Created product id=...
2026-11-09 10:23:45.456 INFO [a1b2c3d4-e5f6-7890-1234-567890abcdef] ProductEventPublisher - Published PRODUCT_CREATED event
2026-11-09 10:23:45.789 INFO [a1b2c3d4-e5f6-7890-1234-567890abcdef] InventoryEventListener - Received INVENTORY_QUANTITY_CHANGED event
```

Log levels:
- Development: INFO (product module), INFO (all others)
- Production: WARN (root), INFO (application modules)

### Metrics

Prometheus-compatible metrics exposed on `/actuator/prometheus`:
- HTTP request counts, latencies, status codes (Spring Actuator)
- Database connection pool usage (HikariCP)
- Kafka producer/consumer lag
- Circuit breaker state (open/closed/half-open)

### Health Checks

- **Liveness** (`/actuator/health/live`): process is running
- **Readiness** (`/actuator/health/ready`): process can handle traffic
  - DB connection succeeds
  - Eureka registration is active (for services)
  - Kafka is reachable (optional; non-blocking startup dependency)

---

## Known Limitations

1. **No schema registry:** Event contracts are duplicated (producer and consumer have identical DTOs). A second consumer would necessitate a shared schema registry.

2. **No dead-letter queue:** Events that fail deserialization are logged and skipped. Production must implement a DLQ.

3. **Single Kafka broker (KRaft):** No replication. A broker outage loses messages not yet consumed.

4. **No distributed tracing:** Correlation IDs are logged but not propagated to a centralized trace collector (e.g., Jaeger).

5. **Synchronous cross-service reads:** The aggregate read (product + inventory) blocks on the inventory-service call. A cache or eventual-consistency read would improve latency.

6. **No saga orchestrator:** Complex multi-service transactions (e.g., order creation spanning product, inventory, and payment) would require explicit compensation logic or a saga orchestrator.

---

## References

- [ADR 0002](adr/0002-event-driven-inventory-kafka-kraft-and-event-contract-duplication.md) — Why event choreography, KRaft, and no schema registry
- [ADR 0003](adr/0003-choreographed-catalog-saga-and-narrow-read-only-cross-service-call.md) — Why Feign clients only for reads, not writes
- [HLD: Catalog Enrichment & Saga](design/HLD-catalog-enrichment-and-saga.md) — Phase 3–4 design including event wiring
- [Backend-EventSystem.md](../reference/events.md) — Event types, Kafka topics, and choreography details
- [Backend-DataModel.md](../reference/data-model.md) — Database schemas and entity relationships
