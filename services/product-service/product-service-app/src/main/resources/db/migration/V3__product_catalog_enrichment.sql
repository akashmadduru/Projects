-- =============================================================================
-- product-service: catalog enrichment (Phase 3 slice, schema-only pass).
--
-- ADDITIVE ONLY, per V1/V2's own convention: no existing column is widened,
-- renamed, or dropped. This migration lands the schema for
-- docs/HLD-catalog-enrichment-and-saga.md / docs/LLD-catalog-enrichment-and-saga.md
-- SS2.1/S4 - categories, product images, and the new nullable columns on
-- product.products. Event/saga wiring (initialQuantity, the aggregate read
-- endpoint, INVENTORY_* consumption) is a LATER, SEPARATE pass; this migration
-- only shapes the tables that pass will eventually populate.
-- =============================================================================

-- =============================================================================
-- categories
--
-- One level of subcategory: parent_category_id is a self-referencing FK, but
-- depth ("a category whose parent itself has a parent") is enforced in the
-- application layer, not here - Postgres has no simple CHECK for that without
-- a trigger, and this platform prefers logic in Java over logic in the
-- database wherever a plain CHECK doesn't suffice (HLD S11 Open Question #5).
-- =============================================================================
CREATE TABLE product.categories
(
    id                 uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    name               varchar(120) NOT NULL,
    parent_category_id uuid REFERENCES product.categories (id),
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now()
);

COMMENT ON TABLE product.categories IS 'Catalog categories, one level of subcategory. Depth enforced in the service layer, not by a DB constraint.';
COMMENT ON COLUMN product.categories.parent_category_id IS 'Self-referencing FK. NULL for a top-level category.';

-- =============================================================================
-- product_images
--
-- Same-database, ordinary FK back to product.products - unlike the
-- product/inventory relationship, a real FK is available and used here.
-- ON DELETE CASCADE: an image row with no owning product is not a row worth
-- keeping around for the application to clean up by hand.
-- =============================================================================
CREATE TABLE product.product_images
(
    id             uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    product_id     uuid         NOT NULL REFERENCES product.products (id) ON DELETE CASCADE,
    url            varchar(2048) NOT NULL,
    display_order  integer      NOT NULL DEFAULT 0,
    created_at     timestamptz  NOT NULL DEFAULT now()
);

COMMENT ON TABLE product.product_images IS 'Product image URLs, ordered per-product by display_order.';

-- An index on product_id: every read this pass adds (findByProductIdOrderByDisplayOrderAsc,
-- deleteByProductId) filters on exactly this column.
CREATE INDEX product_images_product_id_idx ON product.product_images (product_id);

-- =============================================================================
-- product.products - additive enrichment columns.
--
-- All four are nullable: no backfill is required, and no existing row's
-- meaning changes. category_id/seller_name/specifications are ordinary
-- enrichment fields, part of the normal create/update path from this pass
-- onward. last_known_quantity is added now but is NOT written by this pass -
-- a later pass's INVENTORY_QUANTITY_CHANGED listener is its only writer.
-- =============================================================================
ALTER TABLE product.products
    ADD COLUMN category_id         uuid REFERENCES product.categories (id),
    ADD COLUMN seller_name         varchar(200),
    ADD COLUMN specifications      jsonb,
    ADD COLUMN last_known_quantity integer;

COMMENT ON COLUMN product.products.category_id IS 'Nullable FK -> product.categories.id. Not a mapped JPA association - Product.java stays free of associations.';
COMMENT ON COLUMN product.products.seller_name IS 'Free-text seller attribution. No sellers table, no FK.';
COMMENT ON COLUMN product.products.specifications IS 'Arbitrary JSON-shaped attributes. Unstructured on purpose - see LLD S2.1.';
COMMENT ON COLUMN product.products.last_known_quantity IS 'Denormalized, non-authoritative. Populated only by a later pass''s inventory-event listener - untouched by create()/update() in this pass.';

CREATE INDEX products_category_id_idx ON product.products (category_id);
