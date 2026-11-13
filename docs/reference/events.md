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
| `order.events` | `promotion-service-order-listener` | promotion-service `OrderEventListener` (coins on `DELIVERED`) | earliest |
| `order.events` | `seller-service-ledger` | seller-service `OrderEventConsumer` (settlement ledger) | **latest** ([TD-P9-07](../techdebt/phase9.md#td-p9-07)) |
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
| `order.events` | notification-service sends email and inbox messages; promotion-service credits coins on delivery; seller-service books sale, commission and fee on shipment delivery and reverses them on return. |
| `USER_DELETED` | each service anonymises or deletes its own data for that user. |
| `audit.events` | support-service stores the record for `GET /api/admin/audit`. |

**No reply events.** A service that updates a denormalized copy from an event never publishes an event
about that update. Domain entities have no Kafka dependency, and publishing happens only in the
service layer for the service's own writes. This is what prevents event ping-pong between
product-service and inventory-service.

## Delivery guarantees

- **Publish after commit.** Producers send after the database transaction commits. There is **no
  transactional outbox**, so if the process dies or Kafka is down between commit and send, the event
  is lost and nothing retries it ([TD-P6-01](../techdebt/phase6.md#td-p6-01)).
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

**Six listeners bypass this.** They catch `Exception` and only log it, so the error handler never sees
the failure and the record is not retried or dead-lettered: inventory-service `ProductEventListener`,
product-service `InventoryEventListener` and `RatingEventListener`, notification-service
`OrderEventListener`, promotion-service `OrderEventListener`, seller-service `OrderEventConsumer`
([TD-BE-17](../techdebt/backend.md#td-be-17)). The `UserDeletedListener`s, cart alerts and the audit
consumer do throw, so they are covered.

## Operating it

Run these inside the Kafka container (`docker exec -it ecommerce-kafka bash`, binaries under
`/opt/kafka/bin`):

```bash
kafka-topics.sh --bootstrap-server localhost:9092 --list
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group product-service-inventory-listener
kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic order.events.DLT --from-beginning
```

Logs carry the request's correlation id, and traces go to Jaeger (http://127.0.0.1:16686).

## Known gaps

| Gap | Tracked as |
|---|---|
| No outbox: post-commit events can be lost | [TD-P6-01](../techdebt/phase6.md#td-p6-01) |
| Six listeners swallow errors, so no retry or DLT for them | [TD-BE-17](../techdebt/backend.md#td-be-17) |
| Single broker, replication factor 1, PLAINTEXT | [technical audit](../audit/technical-audit.md#8-security-audit) |
| Three consumer groups start at `latest` | [TD-P7-11](../techdebt/phase7.md#td-p7-11), [TD-P9-07](../techdebt/phase9.md#td-p9-07) |
| Duplicated event classes, no schema registry | [ADR 0002](../architecture/adr/0002-event-driven-inventory-kafka-kraft-and-event-contract-duplication.md) |
