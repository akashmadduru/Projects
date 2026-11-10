-- =============================================================================
-- product-service: write endpoints (Phase 2).
--
-- ADDITIVE ONLY, per V1's own convention: no existing column is widened,
-- renamed, or dropped. `version` backs JPA's own `@Version` optimistic-locking
-- field on Product.java, the same mechanism `inventory.stock_levels.version`
-- uses.
--
-- DEFAULT 0, NOT NULL, applied to every existing row (the five Flyway-seeded
-- products from V1) so the column can be added without a separate backfill
-- step - there is no window where a row exists with a null version that a
-- concurrent write could race against.
-- =============================================================================

ALTER TABLE product.products
    ADD COLUMN version bigint NOT NULL DEFAULT 0;

COMMENT ON COLUMN product.products.version IS 'Optimistic-concurrency token. JPA @Version, not a hand-rolled counter.';
