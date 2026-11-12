-- Phase 9: multi-seller offers. ADDITIVE ONLY.
CREATE TABLE product.offers
(
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id    uuid           NOT NULL REFERENCES product.products (id) ON DELETE CASCADE,
    variant_id    uuid,
    seller_id     uuid           NOT NULL,
    price         numeric(19, 2) NOT NULL CHECK (price >= 0),
    sale_price    numeric(19, 2),
    currency      varchar(3)     NOT NULL,
    condition     varchar(20)    NOT NULL DEFAULT 'NEW',
    dispatch_days integer        NOT NULL DEFAULT 1 CHECK (dispatch_days >= 0),
    active        boolean        NOT NULL DEFAULT true,
    created_at    timestamptz    NOT NULL DEFAULT now(),
    updated_at    timestamptz    NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_offers_product_variant_seller
    ON product.offers (product_id, COALESCE(variant_id, '00000000-0000-0000-0000-000000000000'::uuid), seller_id);
CREATE INDEX idx_offers_seller ON product.offers (seller_id);
CREATE INDEX idx_offers_product ON product.offers (product_id);

ALTER TABLE product.products
    ADD COLUMN seller_id  uuid        NOT NULL DEFAULT '00000000-0000-0000-0000-00000000a11e',
    ADD COLUMN listed_by  varchar(20) NOT NULL DEFAULT 'PLATFORM';
CREATE INDEX idx_products_seller ON product.products (seller_id);
