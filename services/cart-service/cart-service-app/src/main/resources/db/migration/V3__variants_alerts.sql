-- Phase 7: variant-aware lines and price-drop / back-in-stock alerts.
-- variant_key = COALESCE(variant_id, nil uuid) so the unique constraints can include a nullable variant.
ALTER TABLE cart.cart_items
    ADD COLUMN variant_id    uuid,
    ADD COLUMN variant_label varchar(200),
    ADD COLUMN variant_key   uuid GENERATED ALWAYS AS (COALESCE(variant_id, '00000000-0000-0000-0000-000000000000'::uuid)) STORED;
ALTER TABLE cart.cart_items DROP CONSTRAINT ux_cart_items_cart_product;
ALTER TABLE cart.cart_items ADD CONSTRAINT ux_cart_items_cart_product_variant UNIQUE (cart_id, product_id, variant_key);

ALTER TABLE cart.saved_items
    ADD COLUMN variant_id    uuid,
    ADD COLUMN variant_label varchar(200),
    ADD COLUMN variant_key   uuid GENERATED ALWAYS AS (COALESCE(variant_id, '00000000-0000-0000-0000-000000000000'::uuid)) STORED;
ALTER TABLE cart.saved_items DROP CONSTRAINT ux_saved_user_product;
ALTER TABLE cart.saved_items ADD CONSTRAINT ux_saved_user_product_variant UNIQUE (user_id, product_id, variant_key);

ALTER TABLE cart.wishlist_items
    ADD COLUMN variant_id    uuid,
    ADD COLUMN variant_label varchar(200),
    ADD COLUMN variant_key   uuid GENERATED ALWAYS AS (COALESCE(variant_id, '00000000-0000-0000-0000-000000000000'::uuid)) STORED;
ALTER TABLE cart.wishlist_items DROP CONSTRAINT ux_wishlist_user_product;
ALTER TABLE cart.wishlist_items ADD CONSTRAINT ux_wishlist_user_product_variant UNIQUE (user_id, product_id, variant_key);

CREATE TABLE cart.alerts
(
    id           uuid PRIMARY KEY        DEFAULT gen_random_uuid(),
    user_id      uuid           NOT NULL,
    email        varchar(254)   NOT NULL,
    product_id   uuid           NOT NULL,
    variant_id   uuid,
    variant_key  uuid GENERATED ALWAYS AS (COALESCE(variant_id, '00000000-0000-0000-0000-000000000000'::uuid)) STORED,
    product_name varchar(300),
    type         varchar(20)    NOT NULL CHECK (type IN ('PRICE_DROP', 'BACK_IN_STOCK')),
    target_price numeric(19, 4),
    last_price   numeric(19, 4),
    currency     char(3),
    active       boolean        NOT NULL DEFAULT true,
    triggered_at timestamptz,
    created_at   timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT ux_alerts_user_product_variant_type UNIQUE (user_id, product_id, variant_key, type)
);
CREATE INDEX ix_alerts_product_active ON cart.alerts (product_id, type) WHERE active;
