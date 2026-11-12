-- Save for later: lines parked outside the cart. Price/name are snapshots from the cart line.
CREATE TABLE cart.saved_items
(
    id         uuid PRIMARY KEY        DEFAULT gen_random_uuid(),
    user_id    uuid           NOT NULL,
    product_id uuid           NOT NULL,
    name       varchar(300)   NOT NULL,
    unit_price numeric(19, 4) NOT NULL CHECK (unit_price >= 0),
    currency   char(3)        NOT NULL,
    image_url  varchar(2048),
    saved_at   timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT ux_saved_user_product UNIQUE (user_id, product_id)
);
