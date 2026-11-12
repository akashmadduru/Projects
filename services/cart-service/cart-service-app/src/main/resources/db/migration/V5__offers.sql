-- Phase 9: marketplace offers. A line is keyed by (product, variant, offer); a null offer means "buy box"
-- (resolved by order-service at checkout). offer_key = COALESCE(offer_id, nil uuid) for the unique keys.
ALTER TABLE cart.cart_items
    ADD COLUMN offer_id  uuid,
    ADD COLUMN seller_id uuid,
    ADD COLUMN offer_key uuid GENERATED ALWAYS AS (COALESCE(offer_id, '00000000-0000-0000-0000-000000000000'::uuid)) STORED;
ALTER TABLE cart.cart_items DROP CONSTRAINT ux_cart_items_cart_product_variant;
ALTER TABLE cart.cart_items ADD CONSTRAINT ux_cart_items_cart_product_variant_offer UNIQUE (cart_id, product_id, variant_key, offer_key);

ALTER TABLE cart.saved_items
    ADD COLUMN offer_id  uuid,
    ADD COLUMN seller_id uuid,
    ADD COLUMN offer_key uuid GENERATED ALWAYS AS (COALESCE(offer_id, '00000000-0000-0000-0000-000000000000'::uuid)) STORED;
ALTER TABLE cart.saved_items DROP CONSTRAINT ux_saved_user_product_variant;
ALTER TABLE cart.saved_items ADD CONSTRAINT ux_saved_user_product_variant_offer UNIQUE (user_id, product_id, variant_key, offer_key);
