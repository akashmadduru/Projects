-- order-service baseline. Orders are immutable snapshots: shipping address and line prices are
-- copied at placement time, so later profile or catalog edits never rewrite history.
-- user_id is the auth-service subject (JWT sub); no cross-database FKs.
CREATE SCHEMA IF NOT EXISTS orders;

CREATE TABLE orders.orders
(
    id               uuid PRIMARY KEY        DEFAULT gen_random_uuid(),
    user_id          uuid           NOT NULL,
    status           varchar(20)    NOT NULL,
    currency         char(3)        NOT NULL,
    total_amount     numeric(19, 4) NOT NULL,
    placed_at        timestamptz    NOT NULL DEFAULT now(),
    cancelled_at     timestamptz,
    idempotency_key  varchar(100)   NOT NULL,
    address_id       uuid           NOT NULL,
    ship_full_name   varchar(120)   NOT NULL,
    ship_phone       varchar(32),
    ship_line1       varchar(200)   NOT NULL,
    ship_line2       varchar(200),
    ship_city        varchar(100)   NOT NULL,
    ship_state       varchar(100),
    ship_postal_code varchar(20)    NOT NULL,
    ship_country     char(2)        NOT NULL,
    version          bigint         NOT NULL DEFAULT 0,
    CONSTRAINT ux_orders_user_idem UNIQUE (user_id, idempotency_key),
    CONSTRAINT ck_orders_status CHECK (status IN ('PLACED', 'CANCELLED'))
);

CREATE INDEX ix_orders_user_placed ON orders.orders (user_id, placed_at DESC);

CREATE TABLE orders.order_items
(
    id         uuid PRIMARY KEY        DEFAULT gen_random_uuid(),
    order_id   uuid           NOT NULL REFERENCES orders.orders (id) ON DELETE CASCADE,
    product_id uuid           NOT NULL,
    sku        varchar(64),
    name       varchar(300)   NOT NULL,
    unit_price numeric(19, 4) NOT NULL,
    quantity   integer        NOT NULL CHECK (quantity > 0),
    line_total numeric(19, 4) NOT NULL,
    image_url  varchar(2048)
);

CREATE INDEX ix_order_items_order ON orders.order_items (order_id);
