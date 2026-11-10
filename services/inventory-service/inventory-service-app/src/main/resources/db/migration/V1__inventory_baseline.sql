-- =============================================================================
-- inventory-service baseline schema (Phase 2).
--
-- Database `inventory_db` is created by infra/postgres/init/01-schemas.sql and
-- owned by the `inventory_service` role. Everything below lives in the
-- `inventory` SCHEMA, not in `public`: a service that owns its database should
-- still not scatter its tables across the default schema, because that is
-- where every tool, every extension and every accidental `CREATE TABLE` also
-- lands.
--
-- Flyway is configured with `schemas: inventory` / `default-schema: inventory`,
-- so it creates the schema and its own history table here.
--
-- FORWARD-ONLY. There is no down migration: rolling a schema change back in a
-- running system loses the rows written since it was applied. Zero-downtime
-- changes are made additively (add nullable column -> backfill -> start writing
-- -> stop reading the old one -> drop it in a much later release).
--
-- NO FOREIGN KEY TO product.products - THAT TABLE IS IN A DIFFERENT DATABASE,
-- and a cross-database foreign key is not physically possible in Postgres in
-- the first place. `product.products` and `inventory.stock_levels` are
-- event-linked only (LLD S4a), never referentially guarded; a `product_id`
-- with no corresponding product row is an accepted prototype gap.
-- =============================================================================

-- gen_random_uuid(). Postgres 13+ has it built in, but the extension is created
-- anyway so the DEFAULT below does not silently depend on the server version.
-- A TRUSTED extension since 13, so the database owner can create it without
-- superuser.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE SCHEMA IF NOT EXISTS inventory;

-- =============================================================================
-- stock_levels
-- =============================================================================
CREATE TABLE inventory.stock_levels
(
    id         uuid PRIMARY KEY     DEFAULT gen_random_uuid(),

    -- The product this row tracks stock for. UNIQUE, not just indexed: this
    -- constraint IS the idempotency mechanism a future Kafka PRODUCT_CREATED
    -- redelivery relies on (see LLD Edge Cases table) - a second provision
    -- attempt for the same product is rejected by the database itself, not
    -- merely discouraged by application logic. No FK - see the header note.
    product_id uuid         NOT NULL,

    -- The CHECK is here rather than only in application code because the
    -- database is the one place a bad row cannot get in through a path someone
    -- forgot about - Inventory.adjustQuantity's own guard is defense-in-depth,
    -- not the only guard.
    quantity   integer      NOT NULL DEFAULT 0
        CONSTRAINT stock_levels_quantity_check CHECK (quantity >= 0),

    -- Optimistic concurrency, same convention as product.products.version:
    -- JPA's own @Version, not a hand-rolled counter.
    version    bigint       NOT NULL DEFAULT 0,

    created_at timestamptz  NOT NULL DEFAULT now(),
    updated_at timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT stock_levels_product_id_key UNIQUE (product_id)
);

COMMENT ON TABLE inventory.stock_levels IS 'Stock levels, one row per product. Event-linked to product.products, no FK - see LLD S4a.';
COMMENT ON COLUMN inventory.stock_levels.product_id IS 'The product this row tracks stock for. Unique; no FK across databases.';

-- NOTE ON INDEXES: there are deliberately no others beyond the UNIQUE(product_id)
-- one. This phase's queries are a primary-key lookup, a product_id lookup, and
-- an `ORDER BY product_id` page walk - all served by constraints/indexes that
-- already exist.
