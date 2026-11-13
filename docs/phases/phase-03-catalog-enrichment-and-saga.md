# Phase 3: Catalog enrichment and choreographed saga

**Status:** Built.
**Design:** [HLD](../architecture/design/HLD-catalog-enrichment-and-saga.md) · [LLD](../architecture/design/LLD-catalog-enrichment-and-saga.md) · [ADR 0003](../architecture/adr/0003-choreographed-catalog-saga-and-narrow-read-only-cross-service-call.md)

## What was built

1. **Bidirectional events.** inventory-service publishes `inventory.events`
   (`INVENTORY_QUANTITY_CHANGED`, `INVENTORY_LOW_STOCK`, `INVENTORY_PROVISIONING_FAILED`), consumed by
   product-service. `PRODUCT_CREATED` / `PRODUCT_UPDATED` became load-bearing: inventory-service
   denormalizes product name and SKU from them.
2. **Richer catalog schema.** `product.categories` (one level of subcategory), `product.product_images`,
   free-text `seller_name`, JSONB `specifications`, and a single-warehouse `warehouse_code` on stock rows.
3. **Create a product with initial stock** as one client operation. `POST /api/products` accepts an
   optional `initialQuantity`, carried on `PRODUCT_CREATED`. Failure compensates through
   `INVENTORY_PROVISIONING_FAILED`, not through a coordinator. It is choreographed: no orchestrating
   service and no synchronous call between the two services.

product-service keeps a display-only `lastKnownQuantity`, which is never authoritative and never re-published.

## Changed since

- Phase 9 replaced the single-warehouse label with real warehouses. The legacy stock tables are
  still mirrored ([TD-P9-02](../techdebt/phase9.md#td-p9-02)).
