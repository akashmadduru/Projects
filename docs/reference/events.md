# Event reference (Kafka)

**Verified against the code:** 2026-11-14. Sources: `@KafkaListener` declarations, `KafkaTopicConfig`
classes, `libs/common-web/.../kafka/DlqErrorHandlers.java`.
**Why events and not calls:** [ADR 0002](../architecture/adr/0002-event-driven-inventory-kafka-kraft-and-event-contract-duplication.md), [ADR 0003](../architecture/adr/0003-choreographed-catalog-saga-and-narrow-read-only-cross-service-call.md).

## Broker

Apache Kafka 3.9 in KRaft mode, **one broker**, replication factor 1. It is reachable only inside the
compose network at `kafka:9092`; no host port is published. On AWS it is MSK
([deploy-aws](../guides/deploy-aws.md)).

## Topics

| Topic | Partitions | Key | Producer | Event types |
|---|---|---|---|---|
| `product.events` | 3 | productId | product-service | `PRODUCT_CREATED`, `PRODUCT_UPDATED`, `PRODUCT_DELETED` |
| `inventory.events` | 3 | productId | inventory-service | `INVENTORY_QUANTITY_CHANGED`, `INVENTORY_LOW_STOCK`, `INVENTORY_PROVISIONING_FAILED` |
| `order.events` | 3 | orderId | order-service | `ORDER_PLACED`, `ORDER_STATUS_CHANGED`, `SHIPMENT_STATUS_CHANGED`, `ORDER_CANCELLED`, `RETURN_REQUESTED`, `RETURN_REFUNDED`, `SUBSCRIPTION_ORDER_PLACED` |
| `rating.events` | broker default (1) | ratingId | rating-service | `RATING_ADDED`, `RATING_UPDATED`, `RATING_DELETED` |
| `user.events` | broker default (1) | userId | user-service | `USER_DELETED` |
| `audit.events` | broker default (1) | resourceId | auth-service, and every service using `common-web`'s `AuditPublisher` | audit record: actor (user id, roles), action, resource type and id, before/after JSON, IP |

`RETURN_REFUNDED` is published (through the outbox, in the same transaction as the REFUNDED status and the stock release) only after the refund money has settled, so a PENDING card refund publishes nothing until it settles.

Topics without a `KafkaTopicConfig` bean are auto-created with the broker default of 1 partition.
Each topic `X` also has a dead-letter topic `X.DLT` (see [failure handling](#failure-handling)).

## Consumers

| Topic | Consumer group | Service and class | Starts at (new group) |
|---|---|---|---|
| `product.events` | `inventory-service-product-listener` | inventory-service `ProductEventListener` | earliest |
| `product.events` | `cart-alerts-product` | cart-service `AlertEventConsumers` (price-drop alerts) | **latest** ([TD-P7-11](../techdebt/phase7.md#td-p7-11)) |
| `inventory.events` | `product-service-inventory-listener` | product-service `InventoryEventListener` | earliest |
| `inventory.events` | `cart-alerts-inventory` | cart-service `AlertEventConsumers` (back-in-stock alerts) | **latest** |
| `rating.events` | `product-service-rating-listener` | product-service `RatingEventListener` | earliest |
| `order.events` | `notification-service-order-listener` | notification-service `OrderEventListener` | earliest |
| `order.events` | `promotion-service-order-listener` | promotion-service `OrderEventListener` (coins on `DELIVERED`; per-return coin reversal on `RETURN_REFUNDED`, which skips the 2-day age gate only when it carries `returnId` AND `returnItemsValue`) | earliest |
| `order.events` | `seller-service-ledger` | seller-service `OrderEventConsumer` (settlement ledger) | **earliest** (since 2026-11-17; the ledger is idempotent, so a replay books nothing twice) |
| `user.events` | `<service>-privacy` | `UserDeletedListener` in auth, cart, notification, order, payment, promotion, rating, seller, support | earliest |
| `audit.events` | `support-service-audit` | support-service `AuditConsumer` | earliest |

"Latest" means a consumer group that starts for the first time skips every event published before it started.

## Event payloads

The producer and consumer each have their own copy of the class; there is no schema registry and no
shared events library ([ADR 0002](../architecture/adr/0002-event-driven-inventory-kafka-kraft-and-event-contract-duplication.md)).
A field change must be made on both sides in the same change. All payloads are JSON.

| Event | Fields |
|---|---|
| `ProductEvent` | `eventId`, `eventType`, `productId`, `publishedAt`, `payload` (`ProductResponse`, null on delete), `initialQuantity` (create only) |
| `InventoryEvent` | `eventId`, `eventType`, `productId`, `publishedAt`, `quantity`, `reservedQuantity`, `status` (`IN_STOCK` / `LOW_STOCK` / `OUT_OF_STOCK`), `variantId` (Phase 7; null for product-level stock) |
| `RatingEvent` | `eventId`, `eventType`, `ratingId`, `productId`, `publishedAt`, `payload` (`RatingSnapshot`) |
| `USER_DELETED` | `type`, `userId` |

## What each consumer does

| Event | Consumer reaction |
|---|---|
| `PRODUCT_CREATED` | inventory-service creates the stock row with `initialQuantity`. A duplicate delivery hits the unique `product_id` and is skipped. On failure it publishes `INVENTORY_PROVISIONING_FAILED`. |
| `PRODUCT_UPDATED` | inventory-service copies product name and SKU onto the stock row. cart-service checks price-drop alerts. |
| `PRODUCT_DELETED` | inventory-service removes the stock row. |
| `INVENTORY_QUANTITY_CHANGED` | product-service sets the display-only `lastKnownQuantity`. cart-service checks back-in-stock alerts. |
| `INVENTORY_PROVISIONING_FAILED` | product-service compensates the create (the saga's failure path, [Phase 3](../phases/phase-03-catalog-enrichment-and-saga.md)). |
| `RATING_*` | product-service treats the event only as a trigger: it fetches the authoritative summary from rating-service over Feign and overwrites `average_rating`, `rating_count`, `review_count`, `rating_distribution`. Replays and out-of-order events therefore converge. |
| `order.events` | notification-service sends email and inbox messages; promotion-service credits coins on delivery and, per refunded return, gives back redeemed coins and reverses earned coins pro rata (`coin_return_reversals`); seller-service books sale, commission and fee on shipment delivery and reverses them on return (items value, capped at the SALE, `FEE_REVERSAL` once when fully reversed; ledger `type` gains `FEE_REVERSAL`). Every `order.events` message with `shipments` also feeds the `ledger_shipments` register that the nightly reconcile compares against. |
| `RETURN_REFUNDED` fields | order-service adds `returnItemsValue` (sum of unit price x quantity of the return) and `orderItemsValue` (order subtotal) to RETURN_* events, null otherwise. promotion-service's copy reads `returnId`, `returnItemsValue`, `orderItemsValue`; `paymentStatus=REFUNDED` marks the completing return. Events without them take the legacy full-refund path and keep the age gate. seller-service is strict: a `RETURN_REFUNDED` without `returnId`, `returnShipmentId` and `returnItemsValue` logs ERROR "LEDGER ALERT" and is dead-lettered at once (no `refundAmount` fallback). |
| `USER_DELETED` | each service anonymises or deletes its own data for that user. |
| `audit.events` | support-service stores the record for `GET /api/admin/audit`. |

**No reply events.** A service that updates a denormalized copy from an event never publishes an event
about that update. Domain entities have no Kafka dependency, and publishing happens only in the
service layer for the service's own writes. This is what prevents event ping-pong between
product-service and inventory-service.

## Delivery guarantees

- **Publishing.** order-service writes `order.events` to a transactional outbox in the same
  transaction as the order change; `OutboxRelay` sends them in order per order and retries until Kafka
  acknowledges (TD-P6-01, fixed 2026-11-17). The other producers still send after commit, so a crash
  or Kafka outage between commit and send loses that event ([TD-P6-33](../techdebt/phase6.md#td-p6-33)).
- **At-least-once consumption.** A redelivered event is processed again. There is no
  processed-event table; idempotency comes from each consumer's own data: unique keys (inventory
  `product_id`), `INSERT … ON CONFLICT` upserts, or re-reading the source of truth (ratings).
- **Ordering** holds per key within a partition: events for one product (`product.events`,
  `inventory.events`) or one order (`order.events`) arrive in order. Rating events are keyed by rating,
  which is safe because the consumer re-reads the summary instead of applying the event.

## Failure handling

Every listener container factory uses `DlqErrorHandlers.create(...)`:

1. A listener that **throws** is retried 3 times with exponential backoff (1 s, 2 s, 4 s).
2. The record is then published to `<topic>.DLT` (partition chosen by key), logged, and the offset moves on.
3. A payload that cannot be deserialized is dead-lettered the same way **only** where the consumer wraps
   its deserializer in `ErrorHandlingDeserializer`: cart alerts, notification, product rating listener,
   promotion and seller ledger. The other consumers do not, so a malformed record never reaches the handler.

**Every listener lets failures reach the error handler** (since 2026-11-17; before, six listeners caught
and logged everything, so their events were lost). Only "safe to skip" cases are swallowed, for example
an event for a product that no longer exists. Malformed data (`IllegalArgumentException`, bad
timestamps) skips the retries and goes straight to the DLT. Alert on any message arriving in a `*.DLT`
topic.

## Operating it

Run these inside the Kafka container (`docker exec -it ecommerce-kafka bash`, binaries under
`/opt/kafka/bin`):

```bash
kafka-topics.sh --bootstrap-server localhost:9092 --list
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group product-service-inventory-listener
kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic order.events.DLT --from-beginning
```

Logs carry the request's correlation id, and traces go to Jaeger (http://127.0.0.1:16686).

## Runbook: seller ledger (F-20261119-13)

- **Deploy order.** order-service with F-09/F-10 (it publishes `returnItemsValue`) deploys before seller-service.
  The other order breaks every return refund.
- **Expect** ERROR logs starting `LEDGER ALERT malformed RETURN_REFUNDED` and a message on `order.events.DLT` for each
  malformed event (missing or unparseable ids, items value not above zero, or a shipment that belongs to another order).
  Nothing is posted for it. After a consumer-group reset to earliest, old events without
  `returnItemsValue` also land there; they were applied before and have no money effect.
- **Replay a dead-lettered return** once the cause is fixed: republish the DLT record to `order.events`
  (same key). The consumer is idempotent per return.
- **Reconcile.** Daily at 02:30 UTC (`seller.ledger.reconcile.cron`), read-only. Findings: `GET
  /api/admin/ledger/discrepancies?open=true` (newest first, at most 200); run now: `POST /api/admin/ledger/reconcile` (409 if one is running).
  `POST /api/admin/ledger/discrepancies/{id}/repair` (ADMIN/FINANCE) fixes `MISSING_SALE` (needs `?amount=`, the shipment amount the admin has verified: order-service does not confirm amounts, so it is never taken from the register; the amount is logged and kept in the row's detail) and `UNMATCHED_REVERSAL`;
  the other kinds are manual. Refused repairs are logged at WARN with the actor.
- **Blind spots:** shipments none of whose events arrived, amount changes without an event, returns whose
  `RETURN_REFUNDED` was dead-lettered, deliveries before this deploy that have no SALE.

## Known gaps

| Gap | Tracked as |
|---|---|
| Only order-service has an outbox; other publishers can lose an event | [TD-P6-33](../techdebt/phase6.md#td-p6-33) |
| Single broker, replication factor 1, PLAINTEXT | [technical audit](../audit/technical-audit.md#8-security-audit) |
| Two consumer groups start at `latest` | [TD-P7-11](../techdebt/phase7.md#td-p7-11) |
| Duplicated event classes, no schema registry | [ADR 0002](../architecture/adr/0002-event-driven-inventory-kafka-kraft-and-event-contract-duplication.md) |
