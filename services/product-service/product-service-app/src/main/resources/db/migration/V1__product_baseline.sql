-- =============================================================================
-- product-service baseline schema (Phase 1E).
--
-- Database `product_db` is created by infra/postgres/init/01-schemas.sql and
-- owned by the `product_service` role. Everything below lives in the `product`
-- SCHEMA, not in `public`: a service that owns its database should still not
-- scatter its tables across the default schema, because that is where every
-- tool, every extension and every accidental `CREATE TABLE` also lands.
--
-- Flyway is configured with `schemas: product` / `default-schema: product`, so
-- it creates the schema and its own history table here.
--
-- FORWARD-ONLY. There is no down migration: rolling a schema change back in a
-- running system loses the rows written since it was applied. Zero-downtime
-- changes are made additively (add nullable column -> backfill -> start writing
-- -> stop reading the old one -> drop it in a much later release).
--
-- SCOPE NOTE: one table. No categories, no inventory, no price history, no
-- images. Phase 1E exists to prove the routing/discovery/token-validation path,
-- and a speculative schema for behaviour that does not exist yet is a migration
-- that will have to be undone before it is ever used.
-- =============================================================================

-- gen_random_uuid(). Postgres 13+ has it built in, but the extension is created
-- anyway so the DEFAULT below does not silently depend on the server version.
-- A TRUSTED extension since 13, so the database owner can create it without
-- superuser.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE SCHEMA IF NOT EXISTS product;

-- =============================================================================
-- products
-- =============================================================================
CREATE TABLE product.products
(
    id             uuid PRIMARY KEY      DEFAULT gen_random_uuid(),

    -- The merchandising identifier. UNIQUE because it is the natural key
    -- external systems (ERP, supplier feeds, the warehouse) quote, and two rows
    -- sharing one SKU means two systems disagree about what was ordered.
    --
    -- The UNIQUE constraint also provides the index that makes the default
    -- `ORDER BY sku` page walk cheap - see the note on stable ordering below.
    sku            varchar(64)  NOT NULL,

    name           varchar(200) NOT NULL,

    -- numeric(19,2), NEVER double precision. Binary floating point cannot
    -- represent 0.10 exactly; a money column that round-trips through a float
    -- accumulates error that surfaces later as a one-cent discrepancy nobody can
    -- reproduce. 19 digits is far more headroom than any realistic price needs
    -- and costs nothing - numeric is variable-width.
    --
    -- The CHECK is here rather than only in application code because the
    -- database is the one place a bad row cannot get in through a path someone
    -- forgot about.
    price_amount   numeric(19, 2) NOT NULL
        CONSTRAINT products_price_amount_check CHECK (price_amount >= 0),

    -- ISO-4217 alphabetic code. char(3), so a two- or four-letter value is
    -- rejected by the storage layer rather than discovered by a client.
    price_currency char(3)      NOT NULL
        CONSTRAINT products_price_currency_check CHECK (price_currency ~ '^[A-Z]{3}$'),

    -- Whether the product is currently offered. Phase 1E's read endpoints do NOT
    -- filter on it - deciding what "visible" means is catalog behaviour, and this
    -- phase deliberately has none. The column exists now because adding it later
    -- would mean backfilling every row.
    active         boolean      NOT NULL DEFAULT true,

    created_at     timestamptz  NOT NULL DEFAULT now(),
    updated_at     timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT products_sku_key UNIQUE (sku)
);

COMMENT ON TABLE product.products IS 'Catalog products. Phase 1E: read-only, no categories/inventory/pricing rules.';
COMMENT ON COLUMN product.products.sku IS 'Merchandising identifier. Unique; the natural key external systems quote.';
COMMENT ON COLUMN product.products.price_amount IS 'Exact decimal. Never store money in a floating-point column.';

-- NOTE ON INDEXES: there are deliberately no others. The two queries this phase
-- issues are a primary-key lookup and a `ORDER BY sku` page walk, both of which
-- are served by constraints that already exist. An index on `active` (or a
-- speculative one on `name`) would be write cost paid for a query nobody makes;
-- add them when pg_stat_statements says to.

-- =============================================================================
-- Seed data.
--
-- IN THE BASELINE MIGRATION, NOT IN A REPEATABLE ONE, and not conditional on the
-- environment. Phase 1E's whole purpose is a verifiable end-to-end request, and
-- an endpoint that correctly returns an empty page proves routing but not much
-- else. These five rows make the first boot of a fresh database return something
-- real through the gateway.
--
-- Explicit ids so a test (or a curl in the README) can address a known row
-- without first listing the catalog to discover one.
--
-- CAVEAT FOR PHASE 2: this data ships to every environment, including a
-- production first deploy. When real catalog loading exists these rows should be
-- deleted by a later migration, not edited in place here - Flyway checksums this
-- file and a running system will refuse to start if it changes.
-- =============================================================================
INSERT INTO product.products (id, sku, name, price_amount, price_currency, active)
VALUES ('11111111-1111-4111-8111-111111111111', 'SKU-KEYBOARD-01', 'Mechanical Keyboard', 129.99, 'USD', true),
       ('22222222-2222-4222-8222-222222222222', 'SKU-MOUSE-01', 'Wireless Mouse', 49.50, 'USD', true),
       ('33333333-3333-4333-8333-333333333333', 'SKU-MONITOR-27', '27-inch 4K Monitor', 449.00, 'USD', true),
       ('44444444-4444-4444-8444-444444444444', 'SKU-DOCK-USBC', 'USB-C Docking Station', 199.95, 'USD', true),
       -- One inactive row on purpose: it is what makes "Phase 1E does not filter
       -- on `active`" an observable, testable fact rather than a claim.
       ('55555555-5555-4555-8555-555555555555', 'SKU-HEADSET-01', 'Noise-Cancelling Headset', 279.00, 'USD', false);
