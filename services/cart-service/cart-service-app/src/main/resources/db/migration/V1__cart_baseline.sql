-- cart-service baseline: server-side cart and wishlist per authenticated user.
-- user_id is the auth-service subject (JWT sub); no FK (different database).
CREATE SCHEMA IF NOT EXISTS cart;

CREATE TABLE cart.carts
(
    id         uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    user_id    uuid        NOT NULL UNIQUE,
    updated_at timestamptz NOT NULL DEFAULT now(),
    version    bigint      NOT NULL DEFAULT 0
);

CREATE TABLE cart.cart_items
(
    id         uuid PRIMARY KEY        DEFAULT gen_random_uuid(),
    cart_id    uuid           NOT NULL REFERENCES cart.carts (id) ON DELETE CASCADE,
    product_id uuid           NOT NULL,
    quantity   integer        NOT NULL CHECK (quantity BETWEEN 1 AND 99),
    name       varchar(300)   NOT NULL,
    unit_price numeric(19, 4) NOT NULL CHECK (unit_price >= 0),
    currency   char(3)        NOT NULL,
    image_url  varchar(2048),
    added_at   timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT ux_cart_items_cart_product UNIQUE (cart_id, product_id)
);

CREATE TABLE cart.wishlist_items
(
    id         uuid PRIMARY KEY        DEFAULT gen_random_uuid(),
    user_id    uuid           NOT NULL,
    product_id uuid           NOT NULL,
    name       varchar(300)   NOT NULL,
    price      numeric(19, 4) NOT NULL CHECK (price >= 0),
    currency   char(3)        NOT NULL,
    image_url  varchar(2048),
    added_at   timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT ux_wishlist_user_product UNIQUE (user_id, product_id)
);
