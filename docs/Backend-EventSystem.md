# Backend Event System

**Status:** Complete (Phases 2–4 slice, prototype-grade)  
**Last Updated:** November 2026

## Table of Contents

1. [Overview](#overview)
2. [Event Architecture](#event-architecture)
3. [Event Types](#event-types)
4. [Choreography Flow](#choreography-flow)
5. [Idempotency & Durability](#idempotency--durability)
6. [Failure Scenarios](#failure-scenarios)
7. [Monitoring & Debugging](#monitoring--debugging)

---

## Overview

The platform uses **event choreography** (not orchestration) to coordinate asynchronous updates across services. Services publish domain events to Kafka topics when state changes; other services consume those events and react independently.

### Why Choreography, Not Orchestration?

- **Loose coupling:** Services don't know about each other; they only listen to events
- **Scalability:** Adding a new consumer doesn't require changes to the producer
- **Autonomy:** Each service owns its write path and publish decision
- **Tradeoff:** No central visibility into multi-step workflows; debugging requires correlating logs across services

See [ADR 0002](adr/0002-event-driven-inventory-kafka-kraft-and-event-contract-duplication.md) for the full decision rationale.

### No Schema Registry

Event contracts are duplicated: the producer's `ProductEvent` class is mirrored in the consumer's module. This avoids the operational complexity of a schema registry at the cost of stricter coupling during evolution (both sides must be updated together).

---

## Event Architecture

### Kafka Topology

```
Kafka (KRaft mode, single broker)
├── product.events (single partition)
│   └── Producer: product-service
│       Consumers: inventory-service, product-service
├── inventory.events (single partition)
│   └── Producer: inventory-service
│       Consumer: product-service
└── rating.events (single partition)
    └── Producer: rating-service
    └── Consumer: product-service
```

### Single Partition Design

Each topic has **one partition**. This ensures:
- **Order preservation:** All events from a single source are consumed in the order they were published
- **Simplified state management:** No need to merge state from multiple partitions
- **Scalability limit:** A single partition cannot be consumed by multiple instances in parallel (one active consumer per partition)

For a production system, partition count should be driven by throughput requirements.

### Consumer Groups

| Topic | Consumer Group | Consumers |
|-------|---|---|
| product.events | inventory-service | inventory-service (1 instance) |
| product.events | product-service-rating-listener | product-service (1 instance) |
| inventory.events | product-service-inventory-listener | product-service (1 instance) |
| rating.events | product-service-rating-listener | product-service (1 instance) |

Each consumer group maintains its own offset, so a new consumer can replay the entire topic history on startup.

---

## Event Types

### ProductEvent

**Topic:** `product.events`  
**Source:** product-service (ProductEventPublisher)

```java
public record ProductEvent(
    UUID eventId,           // unique identifier for this event publication
    ProductEventType eventType,  // PRODUCT_CREATED, PRODUCT_UPDATED, PRODUCT_DELETED
    UUID productId,         // the product's UUID
    Instant publishedAt,    // when the event was published
    ProductResponse payload,// snapshot of the product state (null for PRODUCT_DELETED)
    Integer initialQuantity // only set for PRODUCT_CREATED
) {}

public enum ProductEventType {
    PRODUCT_CREATED,
    PRODUCT_UPDATED,
    PRODUCT_DELETED
}
```

**Publishing Points:**
- `PRODUCT_CREATED` — after `ProductCatalogService.create()` commits
- `PRODUCT_UPDATED` — after `ProductCatalogService.update()` commits
- `PRODUCT_DELETED` — after `ProductCatalogService.delete()` commits

**Consumers:**
1. **inventory-service's ProductEventListener**
   - `PRODUCT_CREATED` → provisions inventory row with `initialQuantity`
   - `PRODUCT_UPDATED` → denormalizes product name/SKU
   - `PRODUCT_DELETED` → removes inventory row if present
2. **product-service's internal listener** (future; not yet implemented)

---

### InventoryEvent

**Topic:** `inventory.events`  
**Source:** inventory-service (InventoryEventPublisher)

```java
public record InventoryEvent(
    UUID eventId,           // unique event identifier
    InventoryEventType eventType,  // INVENTORY_QUANTITY_CHANGED, INVENTORY_LOW_STOCK, ...
    UUID productId,         // the product's UUID
    Instant publishedAt,    // when the event was published
    Integer quantity,       // total quantity on hand
    Integer reservedQuantity, // allocated but not shipped
    String status           // IN_STOCK, LOW_STOCK, OUT_OF_STOCK
) {}

public enum InventoryEventType {
    INVENTORY_QUANTITY_CHANGED,
    INVENTORY_LOW_STOCK,
    INVENTORY_PROVISIONING_FAILED
}
```

**Publishing Points:**
- `INVENTORY_QUANTITY_CHANGED` — after quantity is adjusted in `InventoryService.update()`
- `INVENTORY_LOW_STOCK` — when quantity falls at or below `lowStockThreshold`
- `INVENTORY_PROVISIONING_FAILED` — when auto-provision from `PRODUCT_CREATED` fails

**Consumers:**
1. **product-service's InventoryEventListener**
   - `INVENTORY_QUANTITY_CHANGED` → updates Product.lastKnownQuantity (denormalization)

---

### RatingEvent

**Topic:** `rating.events`  
**Source:** rating-service (RatingEventPublisher)

```java
public record RatingEvent(
    UUID eventId,           // unique event identifier
    RatingEventType eventType,  // RATING_ADDED, RATING_UPDATED, RATING_DELETED
    UUID ratingId,          // the rating's UUID
    UUID productId,         // the product being rated
    Instant publishedAt,    // when the event was published
    RatingResponse payload  // snapshot of the rating (null for RATING_DELETED)
) {}

public enum RatingEventType {
    RATING_ADDED,
    RATING_UPDATED,
    RATING_DELETED
}
```

**Publishing Points:**
- `RATING_ADDED` — after `RatingService.create()` commits
- `RATING_UPDATED` — after `RatingService.update()` commits
- `RATING_DELETED` — after `RatingService.delete()` commits

**Consumers:**
1. **product-service's RatingEventListener**
   - `RATING_ADDED`, `RATING_UPDATED`, `RATING_DELETED` → recalculates and updates Product.averageRating, ratingCount, ratingDistribution (denormalization)

---

## Choreography Flow

### Scenario 1: Product Creation with Auto-Provisioning

```
Step 1. Client POST /api/products {sku, name, price, initialQuantity}
         → product-service ProductController.create()

Step 2. ProductCatalogService.create()
         - INSERT into product.products
         - INSERT into product.product_images (if any)
         - saveAndFlush() to get generated UUID
         - Return ProductResponse

Step 3. @Transactional commit
         ↓
Step 4. ProductEventPublisher.publishAfterCommit(PRODUCT_CREATED)
         - Publish ProductEvent {eventId, PRODUCT_CREATED, productId, payload, initialQuantity}
         - Send to product.events Kafka topic (non-blocking, best-effort)
         
Step 5. Kafka: product.events
         ↓
Step 6. inventory-service ProductEventListener
         - Consume ProductEvent(PRODUCT_CREATED)
         - Call InventoryService.provisionWithQuantity(productId, initialQuantity, name, sku)
         - INSERT into inventory.stock_levels
         - Log success
         - **NEVER** publish a reply event (no ping-pong)

Result: Product exists in product_db, inventory row exists in inventory_db
        Both created without a synchronous orchestrator or distributed transaction
```

### Scenario 2: Inventory Update with Product Denormalization

```
Step 1. Client PUT /api/inventory/{id} {quantity: 95, version: 3, ...}
         → inventory-service InventoryController.update()

Step 2. InventoryService.update()
         - SELECT stock_levels by id (load current version)
         - Check request.version() == current.version() → must match
         - Call adjustQuantity(95, now())
         - saveAndFlush() to increment version

Step 3. @Transactional commit
         ↓
Step 4. InventoryEventPublisher.publishAfterCommit(INVENTORY_QUANTITY_CHANGED)
         - Publish InventoryEvent {eventId, INVENTORY_QUANTITY_CHANGED, productId, quantity: 95, ...}
         - Send to inventory.events Kafka topic

Step 5. Kafka: inventory.events
         ↓
Step 6. product-service InventoryEventListener
         - Consume InventoryEvent(INVENTORY_QUANTITY_CHANGED)
         - Call ProductCatalogService.applyInventorySignal(productId, 95)
         - SELECT products by id
         - Call product.applyInventorySignal(95, now())
         - Save product (no explicit flush; commits with @Transactional)
         - **NEVER** publish a reply event to inventory.events (would create ping-pong)

Result: Inventory quantity is authoritative in inventory_db
        Product.lastKnownQuantity is a non-authoritative, eventual-consistent snapshot
```

### Scenario 3: Rating Creation with Product Aggregation

```
Step 1. Client POST /api/ratings/products/{productId} {ratingValue: 5, reviewText: "...", ...}
         → rating-service RatingController.create()

Step 2. RatingService.create()
         - INSERT into rating.ratings
         - saveAndFlush() to get generated UUID
         - Return RatingResponse

Step 3. @Transactional commit
         ↓
Step 4. RatingEventPublisher.publishAfterCommit(RATING_ADDED)
         - Publish RatingEvent {eventId, RATING_ADDED, ratingId, productId, payload, ...}
         - Send to rating.events Kafka topic

Step 5. Kafka: rating.events
         ↓
Step 6. product-service RatingEventListener
         - Consume RatingEvent(RATING_ADDED)
         - Call ProductCatalogService.updateRatingDenormalization(productId)
         - (Future: call ratingServiceClient.getSummary(productId) via Feign)
         - (Future: UPDATE products SET averageRating, ratingCount, ratingDistribution)
         - (Currently: placeholder method)
         - **NEVER** publish a reply event

Result: Rating stored in rating_db
        Product.averageRating, etc., will eventually be updated in product_db
```

---

## Idempotency & Durability

### Event IDs

Each event has a stable `eventId` (UUID). If the same event is redelivered (at-least-once Kafka guarantee), consumers should recognize it by ID and skip reprocessing.

**Current Implementation:** Event IDs are generated but not actively deduplicated. A future enhancement should store processed `(topicPartition, eventId)` in a database to prevent re-execution of already-handled events.

### Unique Constraints (Natural Idempotency)

Instead of explicit deduplication, the system relies on database constraints:

**inventory.stock_levels.product_id UNIQUE:**
- If `PRODUCT_CREATED` event is delivered twice, the second `INSERT` fails with a unique constraint violation
- The consumer logs the error and continues; the state is correct despite the duplicate

**rating_votes(rating_id, user_id) UNIQUE:**
- If a vote is recorded twice, the second attempt fails
- The consumer logs the error; the state is correct

**Product updates (optimistic locking):**
- If `PRODUCT_UPDATED` event is processed twice with the same version, the second update uses stale data
- Currently not protected; a future enhancement could store processed event IDs

### Consumer Group Offsets

Spring Kafka automatically manages consumer-group offsets. After a message is successfully processed (listener method completes without exception), the offset is committed.

**At-least-once guarantee:**
- If a consumer crashes during processing, the offset is not committed
- On restart, the message is reprocessed
- Idempotency guards (UNIQUE constraints, event ID dedup) prevent corruption

**Auto-commit timing:**
- Default: `spring.kafka.consumer.auto-commit-interval-ms = 5000` (5 seconds)
- If service crashes, up to 5 seconds of unprocessed messages may be replayed on restart

---

## Failure Scenarios

### Scenario 1: Event Publishing Fails

**What happens:**
```java
try {
    events.publishAfterCommit(ProductEventType.PRODUCT_CREATED, response, request.initialQuantity());
} catch (Exception e) {
    log.error("Failed to publish event", e);
    // Do NOT throw; the product has already been created and committed
}
```

- Product is created and committed to the database
- Kafka send fails (unreachable broker, timeout, etc.)
- Exception is logged; the endpoint still returns 201 CREATED
- inventory-service never receives the event
- **Impact:** Inventory row is never provisioned; product exists without inventory (eventual consistency gap)
- **Recovery:** Manual re-send of the event, or a batch job to reconcile missing inventory rows

### Scenario 2: Consumer Processing Fails

**What happens:**
```java
@KafkaListener(topics = "product.events", groupId = "inventory-service-product-listener")
public void onProductEvent(ProductEvent event) {
    try {
        // process event
    } catch (Exception e) {
        log.error("Error processing event", e);
        // Do NOT throw; offset is already committed
    }
}
```

- Consumer receives ProductEvent
- Error during processing (e.g., database error, network error)
- Exception is logged; **offset is still committed** (Spring Kafka default)
- **Impact:** Event is skipped; the service's state diverges (no inventory row provisioned)
- **Recovery:** Manual re-send of the event, or replay from topic beginning (requires resetting consumer group offset)

### Scenario 3: Message Deserialization Fails

**What happens:**
- Event is published with a schema that the consumer cannot deserialize
- Spring Kafka's `JsonDeserializer` throws `JsonMappingException`
- Message is skipped; log shows deserialization error
- **Impact:** Event is lost; no recovery without manual intervention
- **Current limitation:** No dead-letter queue (DLQ) to redirect failed messages

### Scenario 4: Kafka Broker Outage

**What happens:**
- Producer attempts to publish an event; broker is unreachable
- `KafkaProducerException` is caught, logged, not rethrown
- Product is created; event is not published
- inventory-service never receives the event
- **Impact:** Same as Scenario 1: eventual consistency gap

- Consumer's `@KafkaListener` container enters a retry loop
- Spring Kafka attempts reconnection in the background (default: 300s backoff, configurable via `retry.topic.*`)
- Once Kafka is reachable, consumer resumes from its last committed offset
- **Recovery:** Automatic once Kafka is restored

### Scenario 5: Service Crashes Mid-Event Processing

**What happens:**
- Consumer is processing ProductEvent
- Service crashes before the method completes (e.g., OOM, SIGKILL)
- Offset is NOT committed (no explicit commit before crash)
- Consumer group records the consumer as down
- New instance starts or rebalancing happens
- Consumer rejoins; last unprocessed message is replayed
- **Recovery:** Automatic with at-least-once redelivery

---

## No Ping-Pong Guarantee

A critical architectural constraint: **when a service updates denormalized data from an event, it must never publish a reply event.**

### Why?

```
Ping-Pong Example (Bad):

Step 1. product-service publishes PRODUCT_UPDATED → product.events
Step 2. inventory-service consumes PRODUCT_UPDATED, updates product name
Step 3. inventory-service publishes PRODUCT_METADATA_CHANGED → inventory.events (BAD!)
Step 4. product-service consumes PRODUCT_METADATA_CHANGED, updates something
Step 5. ... infinite loop of events, no convergence
```

### Implementation

- Domain entities (Product, Inventory, Rating) have **zero Kafka dependencies**
- Denormalization methods (e.g., `Product.applyInventorySignal()`) are in the domain layer and **cannot** publish events
- Event publishing lives in the service layer and is **only** called for the service's own write operations, never for denormalization

```java
// Product.java (domain layer)
public void applyInventorySignal(Integer quantity, Instant now) {
    this.lastKnownQuantity = quantity;
    this.updatedAt = now;
    // No access to ProductEventPublisher; cannot publish an event
}

// ProductCatalogService.java (service layer)
@Transactional
public void applyInventorySignal(UUID productId, int quantity) {
    products.findById(productId).ifPresent(p -> {
        p.applyInventorySignal(quantity, timeProvider.now());
        // ProductEventPublisher is never called here
    });
}
```

---

## Monitoring & Debugging

### Consumer Lag

Track how far behind each consumer group is from the latest published events.

```bash
# Using Kafka CLI (from inside the kafka container)
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group product-service-inventory-listener --describe

# Output:
# TOPIC      PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
# inventory.events 0     1234            1250           16
```

Lag of 16 means the consumer is 16 events behind.

### Topic Inspection

```bash
# List topics
kafka-topics.sh --bootstrap-server localhost:9092 --list

# Describe a topic
kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic product.events

# Read messages from a topic (from the beginning)
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic product.events --from-beginning \
  --property print.key=true --property key.separator=":"
```

### Log Tracing

All event publishing and consumption is logged with correlation IDs:

```
2026-11-09 10:23:45.123 INFO [corr-id-123] ProductEventPublisher - Published PRODUCT_CREATED eventId=... productId=...
2026-11-09 10:23:45.456 INFO [corr-id-123] ProductEventListener - Consumed InventoryEvent productId=...
```

Use the correlation ID to trace a request across all services.

### Metrics

- `kafka.producer.record.send.total` — number of records sent
- `kafka.producer.record.error.total` — number of send failures
- `kafka.consumer.records.lag.max` — max consumer lag per group
- `kafka.consumer.commit.latency.avg` — average offset commit time

---

## Known Limitations

1. **No dead-letter queue:** Failed message deserialization is logged but not retained for later processing
2. **No event ID deduplication:** Duplicate events are reprocessed; UNIQUE constraints prevent corruption but add latency
3. **No schema evolution support:** If a producer's event schema changes, all consumers must be updated simultaneously
4. **Single partition per topic:** Throughput is limited to a single broker's processing capacity for that partition
5. **No exactly-once semantics:** At-least-once delivery means duplicate processing is possible
6. **No distributed tracing:** Correlation IDs are logged but not integrated with a trace collector (e.g., Jaeger)

---

## References

- [Backend-Architecture.md](Backend-Architecture.md) — System design and deployment
- [ADR 0002](adr/0002-event-driven-inventory-kafka-kraft-and-event-contract-duplication.md) — Why choreography and no schema registry
- [HLD: Catalog Enrichment & Saga](HLD-catalog-enrichment-and-saga.md) — Phase 3–4 event wiring
