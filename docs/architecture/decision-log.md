# Backend Decision Log

**Coverage:** cross-cutting backend decisions from Phases 1–4. Phase-specific decisions live in the [phase pages](../phases/README.md); foundational ones are in [adr/](adr/).  
**Last Updated:** November 2026

## Purpose

This document records architectural decisions made during backend development. Each decision includes the context, alternatives considered, the chosen approach, and implications for future work.

---

## Decision 5: Optimistic Locking via JPA @Version

**Phase:** 2 (write endpoints)  
**Status:** Implemented  
**Date:** November 2026

### Context

Concurrent writes to the same entity (e.g., product update by two users simultaneously) must not result in lost updates. Two approaches exist: optimistic locking and pessimistic locking.

### Alternatives Considered

1. **Pessimistic locking** — lock the row before reading:
   - `SELECT * FROM products WHERE id = ? FOR UPDATE;`
   - Guarantees conflict-free writes
   - Blocks concurrent readers on the same row
   - Unsuitable for high-concurrency read-heavy services

2. **Optimistic locking** — detect conflicts at commit time:
   - Read a version field alongside the data
   - Caller echoes the version on update
   - Check version matches before applying changes
   - If mismatch: reject with 409 CONFLICT, client retries
   - Suitable for low-conflict scenarios

### Decision

**Optimistic locking via JPA `@Version`** on Product, Inventory, and Rating entities.

```java
@Version
@Column(name = "version", nullable = false)
private Long version;
```

**Explicit version checking before writes:**
```java
if (!Objects.equals(product.getVersion(), request.version())) {
    throw new ResourceConflictException(
        "Product ... has version " + product.getVersion() + ", not " + request.version());
}
```

### Rationale

- **Correctness:** JPA handles version increment and checking automatically (Hibernate manages @Version)
- **Simplicity:** No application code for conflict detection; declarative via annotation
- **API clarity:** Version is part of the wire contract; clients know they must read and re-submit it
- **Performance:** No locks held during I/O; concurrent readers aren't blocked

### Implications

- **Client responsibility:** Client must echo the version on every update. Clients can show a version number or timestamp for UX.
- **Conflict handling:** 409 responses require client retry logic (exponential backoff recommended)
- **Testing:** Concurrent write tests must verify that one write succeeds and the other fails with 409

### References

- [Product domain class](../../services/product-service/product-service-app/src/main/java/com/acme/ecommerce/product/domain/Product.java)
- [ProductCatalogService.update()](../../services/product-service/product-service-app/src/main/java/com/acme/ecommerce/product/service/ProductCatalogService.java#L177)

---

## Decision 4: Event Choreography, Not Orchestration

**Phase:** 2 (event-driven inventory)  
**Status:** Implemented  
**Date:** November 2026

### Context

Multiple services must coordinate state changes (product created → inventory provisioned; inventory updated → product denormalized). Two patterns exist: choreography and orchestration.

### Alternatives Considered

1. **Orchestration** — a coordinator service directs the workflow:
   - Order service: "create inventory, then denormalize product"
   - Central workflow definition and visibility
   - Requires all services to expose compensating transactions for rollback
   - Single coordinator is a bottleneck and SPOF

2. **Choreography** — services react to events independently:
   - Product service publishes PRODUCT_CREATED
   - Inventory service consumes, provisions inventory
   - No central coordinator; decoupled services
   - Harder to debug (no central trace of the flow)
   - Services must be idempotent

### Decision

**Event choreography** via Kafka topics and @KafkaListener consumers.

Each service publishes domain events; other services consume and react. No central orchestrator.

```java
// product-service publishes
events.publishAfterCommit(ProductEventType.PRODUCT_CREATED, response, initialQuantity);

// inventory-service consumes
@KafkaListener(topics = "product.events", groupId = "inventory-service-product-listener")
public void onProductEvent(ProductEvent event) { ... }
```

### Rationale

- **Loose coupling:** Services don't know about each other; they only know event schemas
- **Scalability:** Adding a new consumer (e.g., notification service) doesn't require changes to the producer
- **Autonomy:** Each service owns its write path and publish decision
- **Resilience:** Services can be down independently; events accumulate in Kafka until consumed

### Implications

- **Debugging:** Complex multi-service workflows require correlating logs across services (handled by correlation IDs)
- **Idempotency:** Services must handle duplicate events gracefully (UNIQUE constraints, event ID dedup)
- **Consistency:** Not immediate; eventual consistency windows can be seconds in high-latency environments
- **Schema evolution:** Changing an event schema requires coordinating producer and all consumers

### References

- [ADR 0002](adr/0002-event-driven-inventory-kafka-kraft-and-event-contract-duplication.md) — full decision
- [Backend-EventSystem.md](events.md) — event choreography details

---

## Decision 3: Eventual Consistency Across Services

**Phase:** 2–3 (inventory + catalog enrichment)  
**Status:** Implemented  
**Date:** November 2026

### Context

Denormalized fields (e.g., product.lastKnownQuantity) must stay synchronized with their source of truth (inventory.quantity). Immediate strong consistency would require distributed transactions; eventual consistency accepts stale data for bounded windows.

### Alternatives Considered

1. **Strong consistency** — synchronous cross-service calls for every write:
   - Product update → wait for inventory service confirmation
   - Reduces latency for reads (always fresh)
   - Increases latency for writes (cross-service RPC)
   - Ties together service availability (product unavailable if inventory unavailable)
   - Distributed transactions have no atomic rollback

2. **Eventual consistency** — asynchronous event-based updates:
   - Product publishes denormalized snapshot
   - Inventory service consumes, updates locally
   - Reduces write latency (no cross-service wait)
   - Reads may see stale data for <1s (typical event lag)
   - Services are independent (one can be down, the other continues)

### Decision

**Eventual consistency** with event-based denormalization and <1s consistency window (typical).

```java
// Inventory update publishes event
InventoryEventPublisher.publishAfterCommit(INVENTORY_QUANTITY_CHANGED, quantity);

// Product-service eventually updates denormalized field
InventoryEventListener.onInventoryEvent(event) {
    ProductCatalogService.applyInventorySignal(productId, event.quantity());
}
```

### Rationale

- **Write latency:** Inventory updates don't block waiting for product-service
- **Service independence:** Product and inventory services can fail independently
- **Scalability:** No distributed transaction overhead (2-phase commit, etc.)
- **Simplicity:** Event-based updates are easier to reason about than orchestrated transactions

### Tradeoffs

- **Read staleness:** Product.lastKnownQuantity may lag actual inventory by <1s
- **Partial failures:** If event is lost, denormalized field stays stale (requires manual reconciliation)
- **Debugging:** Multi-service state requires tracing correlation IDs across services

### Acceptable Because

- Use case is non-financial (e-commerce catalog, not payments)
- Staleness window is small enough for UX (users don't refresh that frequently)
- Eventual consistency is standard for event-driven systems

### References

- [Backend-Architecture.md](overview.md) — consistency model section
- [Backend-EventSystem.md](events.md) — event choreography and failure scenarios

---

## Decision 2: Feign Clients Only for Reads, Not Writes

**Phase:** 3 (catalog enrichment aggregate read)  
**Status:** Implemented  
**Date:** November 2026

### Context

The product-service aggregate read endpoint needs to join product data with inventory data (cross-service). Should this be a Feign HTTP call or Kafka event?

### Alternatives Considered

1. **Synchronous Feign call** — HTTP request to inventory-service:
   - GET /api/products/catalog/products/{id} calls GET /api/inventory/by-product/{id} internally
   - Immediate response (no event lag)
   - Adds RPC latency to product reads
   - Inventory-service outage breaks aggregate reads
   - Simple for clients (one endpoint)

2. **Kafka event** — publish product request, listen for inventory response:
   - Product service publishes ProductAggregate.NeedInventory event
   - Inventory service consumes, publishes ProductAggregate.InventoryLoaded
   - Asynchronous; eventual consistency
   - Complex choreography (request-response pattern)
   - Decoupled but higher latency and complexity

3. **No aggregate** — let client join by calling both endpoints:
   - GET /api/products/{id} then GET /api/inventory/by-product/{id}
   - Client-side composition
   - Simple backend; client complexity
   - Possible inconsistency between two calls (race condition)

### Decision

**Synchronous Feign call for reads only, with circuit breaker fallback.**

```java
@FeignClient(name = "inventory-service", fallback = InventoryServiceClientFallback.class)
public interface InventoryFeignClient {
    @GetMapping("/api/inventory/by-product/{productId}")
    InventoryResponse findByProductId(@PathVariable UUID productId);
}

public ProductCatalogAggregateResponse getAggregate(UUID id) {
    Product product = productRepository.findById(id).orElseThrow(...);
    InventoryResponse inventory = inventoryFeignClient.findByProductId(id); // May timeout/404
    return new ProductCatalogAggregateResponse(product, inventory);
}
```

**Fallback when inventory is unreachable:**
```java
public class InventoryServiceClientFallback implements InventoryFeignClient {
    @Override
    public InventoryResponse findByProductId(UUID productId) {
        return null; // aggregate response includes inventory: null
    }
}
```

### Rationale

- **Simplicity:** Synchronous read is easier to reason about than async choreography
- **No writes:** Feign is only for reads; transactional safety isn't a concern
- **Fallback safety:** If inventory is unreachable, clients see inventory: null (not an error)
- **Timeouts:** Feign has explicit timeout configuration; slow calls don't hang indefinitely

### Constraints

- **No transitive calls:** Product-service calls inventory-service, but inventory should not call product-service
- **Timeouts:** 1.5s read-timeout matches gateway's 1s slow-call threshold
- **Not for writes:** Never use Feign for writes (distributed transaction problems)

### Circuit Breaker

Resilience4j circuit breaker wraps the Feign call:
- Sliding window: 20 calls
- Failure threshold: 50% (10 failures)
- Slow-call threshold: 50% (calls > 1.5s)
- Open → Half-open after 10s; if 3/3 calls succeed, close

### References

- [ADR 0003](adr/0003-choreographed-catalog-saga-and-narrow-read-only-cross-service-call.md) — full decision
- [CatalogAggregateService](../../services/product-service/product-service-app/src/main/java/com/acme/ecommerce/product/service/CatalogAggregateService.java)
- [resilience4j configuration](../../services/product-service/product-service-app/src/main/resources/application.yml#L171)

---

## Decision 1: No Schema Registry; Event Contracts Duplicated

**Phase:** 2 (event-driven inventory)  
**Status:** Implemented  
**Date:** November 2026

### Context

Events are published to Kafka topics (ProductEvent, InventoryEvent) and consumed by other services. Should the schema be shared via a schema registry, or duplicated in each service?

### Alternatives Considered

1. **Shared schema registry** (Confluent Schema Registry, AWS Glue):
   - Single source of truth for event schemas
   - Automatic schema validation and versioning
   - Clients register schemas before producing/consuming
   - Operational overhead (running a registry, managing credentials)
   - Coupling between services (must go through registry)

2. **Duplicated schemas** — each service has its own copy:
   - Product-service publishes ProductEvent (in product-service-app/src/main)
   - Inventory-service has identical ProductEvent class (in inventory-service-app/src/main)
   - No registry to operate
   - Strict coupling at evolution time (schema changes require coordinating producer and all consumers)
   - Simpler for 2–3 services; scales poorly to 10+ services

### Decision

**Duplicated event contracts** (short-term; re-evaluated when consumer count > 3).

```
product-service-app/src/main/java/com/acme/ecommerce/product/event/ProductEvent.java
inventory-service-app/src/main/java/com/acme/ecommerce/inventory/event/ProductEvent.java
        (identical content)
```

### Rationale

- **Simplicity:** No schema registry to run, no credentials to manage
- **Small scope:** Only 2–3 consumers per event type; coordinating changes is feasible
- **Clarity:** Copies make it explicit that the boundary is between services; no hidden registry dependency
- **Development speed:** No operational setup for a prototype

### Tradeoffs

- **Evolution cost:** Changing an event schema requires coordinating producer and all consumers
- **Sync risk:** Accidental drift between copies (mitigated by test contracts)
- **Scalability:** Doesn't scale beyond ~3 consumers; future phases need schema registry

### Future Improvement

When a fourth consumer is added (e.g., notification service), migration to a schema registry is justified:
1. Define schemas in registry
2. Generate classes via Avro/Protobuf tools
3. Producers validate before publishing
4. Consumers resolve schema at deserialization time

### Test Coverage

Contract tests ensure copies stay in sync:
```java
// ProductContractTest
@Test
void eventShapeMatchesExpectation() {
    // Verify ProductEvent can be serialized/deserialized identically
    // across producer and consumer modules
}
```

### References

- [ADR 0002](adr/0002-event-driven-inventory-kafka-kraft-and-event-contract-duplication.md) — full decision
- [Backend-EventSystem.md](events.md) — event contracts

---

## Future Decisions (Placeholder)

### To Be Decided

1. **Rating aggregation strategy** — When product.averageRating is denormalized from rating.events, should the product-service fetch a summary from rating-service (slow) or calculate locally from events (error-prone)?

2. **Dead-letter queue strategy** — Where should failed event deserialization messages go? DLQ, S3, or retry topic?

3. **Distributed tracing** — Should correlation IDs be integrated with Jaeger or similar, or is log-based tracing sufficient?

4. **Cache strategy for rating summary** — Should rating aggregates be cached in Redis, or fetched fresh on each product read?

5. **Schema registry migration** — At what consumer count (2, 3, 4+?) should we migrate to Confluent Schema Registry?

---

## References

- [Backend-Architecture.md](overview.md) — System design context
- [ADR 0002](adr/0002-event-driven-inventory-kafka-kraft-and-event-contract-duplication.md) — ADR for event choreography
- [ADR 0003](adr/0003-choreographed-catalog-saga-and-narrow-read-only-cross-service-call.md) — ADR for Feign usage
