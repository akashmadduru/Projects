-- =============================================================================
-- inventory-service schema enrichment (Phase 3 slice - schema only).
--
-- Additive columns on inventory.stock_levels, per
-- docs/HLD-catalog-enrichment-and-saga.md S2/S4 and
-- docs/LLD-catalog-enrichment-and-saga.md S4. This migration ONLY adds columns;
-- the Kafka wiring that populates product_name/product_sku (denormalization on
-- consumption) and warehouse_code's future multi-warehouse semantics are a
-- LATER pass - see those columns' comments below.
--
-- FORWARD-ONLY, same posture as V1: no down migration, additive-only, nullable
-- (or DEFAULT-backed) columns so existing rows never need a backfill to stay
-- valid.
-- =============================================================================

ALTER TABLE inventory.stock_levels
    -- Single-warehouse, forward-compatible label. NOT a real multi-warehouse
    -- model - sized so a future rename isn't needed, not so multi-warehouse
    -- logic works today (HLD S1 Non-Goals).
    ADD COLUMN warehouse_code varchar(32) NULL,

    -- Denormalized from product-service. NULL-only in this pass - populated by
    -- a LATER pass's Kafka consumption (ProductEventListener ->
    -- denormalizeProductSnapshot), not by this migration or this pass's
    -- application code.
    ADD COLUMN product_name varchar(200) NULL,

    -- Denormalized from product-service. Same as product_name above.
    ADD COLUMN product_sku varchar(64) NULL,

    -- NOT NULL with a DEFAULT: every existing row gets a sensible threshold
    -- without a separate backfill step, and every future direct-insert path
    -- that omits it still lands on the same default the service layer applies
    -- when the caller's request omits it too.
    ADD COLUMN low_stock_threshold integer NOT NULL DEFAULT 10;

COMMENT ON COLUMN inventory.stock_levels.warehouse_code IS 'Single-warehouse, forward-compatible label - not a multi-warehouse model.';
COMMENT ON COLUMN inventory.stock_levels.product_name IS 'Denormalized from product-service via product.events - populated by a later pass, NULL until then.';
COMMENT ON COLUMN inventory.stock_levels.product_sku IS 'Denormalized from product-service via product.events - populated by a later pass, NULL until then.';
COMMENT ON COLUMN inventory.stock_levels.low_stock_threshold IS 'Quantity at/below which this row is considered low stock.';
