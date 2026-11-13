# Phase 2: Event-driven inventory (slice)

**Status:** Built, prototype-grade by explicit design.
**Design:** [HLD](../architecture/design/HLD-event-driven-inventory.md) · [LLD](../architecture/design/LLD-event-driven-inventory.md) · [ADR 0002](../architecture/adr/0002-event-driven-inventory-kafka-kraft-and-event-contract-duplication.md)
**Service docs:** [inventory-service](../services/inventory-service.md), [product-service](../services/product-service.md), [auth-service user-snapshot cache](../services/auth-service.md#user-snapshot-cache-phase-2-slice)

## What was built

- product-service write endpoints (`POST`/`PUT`/`DELETE /api/products/{id}`) with optimistic locking
  (`version` column) and role-gated writes.
- inventory-service (:8083) with its own `inventory_db`, stock-level CRUD, and an idempotent consumer
  of `product.events`.
- Kafka 3.9 in KRaft mode, single broker. `product.events` has 3 partitions keyed by `productId` and is
  published only after the write transaction commits.
- A Redis cache of a minimal user snapshot in auth-service, written on every token issuance. It is a
  read-performance aid only and is never consulted for an authorization decision.
- Gateway route `/api/inventory/**` with the `inventoryCb` circuit breaker.

## Deliberately left out (non-goals)

No schema registry (the event DTO is duplicated in producer and consumer), no dead-letter queue, no
exactly-once delivery (idempotency comes from the consumer's `product_id` unique constraint), no
synchronous call between product-service and inventory-service, and no cross-database foreign keys.

## Changed since

- [Phase 3](phase-03-catalog-enrichment-and-saga.md) made the flow bidirectional (`inventory.events`).
- Phase 6 added stock reservation; Phase 9 added warehouses and per-warehouse stock.
- Phase 10 added dead-letter handling in `common-web`, so the "no DLQ" non-goal no longer holds everywhere.
