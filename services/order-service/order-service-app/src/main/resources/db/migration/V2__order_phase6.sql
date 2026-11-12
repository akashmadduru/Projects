-- Phase 6 "Sell for real": payments, pricing breakdown, fulfilment tracking, returns.
-- Forward-only. Legacy (Phase 5) rows are PLACED card-style orders treated as already paid.

ALTER TABLE orders.orders
    ADD COLUMN payment_method         varchar(10)    NOT NULL DEFAULT 'CARD',
    ADD COLUMN payment_status         varchar(24)    NOT NULL DEFAULT 'PAID',
    ADD COLUMN payment_id             varchar(64),
    ADD COLUMN subtotal_amount        numeric(19, 4),
    ADD COLUMN discount_amount        numeric(19, 4) NOT NULL DEFAULT 0,
    ADD COLUMN coupon_code            varchar(40),
    ADD COLUMN shipping_amount        numeric(19, 4) NOT NULL DEFAULT 0,
    ADD COLUMN tax_amount             numeric(19, 4) NOT NULL DEFAULT 0,
    ADD COLUMN tax_label              varchar(40),
    ADD COLUMN cgst_amount            numeric(19, 4),
    ADD COLUMN sgst_amount            numeric(19, 4),
    ADD COLUMN igst_amount            numeric(19, 4),
    ADD COLUMN refunded_amount        numeric(19, 4) NOT NULL DEFAULT 0,
    ADD COLUMN email                  varchar(320),
    ADD COLUMN estimated_delivery_date date,
    ADD COLUMN tracking_number        varchar(100),
    ADD COLUMN carrier                varchar(100),
    ADD COLUMN delivered_at           timestamptz;

UPDATE orders.orders SET subtotal_amount = total_amount;
ALTER TABLE orders.orders ALTER COLUMN subtotal_amount SET NOT NULL;

ALTER TABLE orders.orders DROP CONSTRAINT ck_orders_status;
ALTER TABLE orders.orders
    ADD CONSTRAINT ck_orders_status CHECK (status IN ('PENDING_PAYMENT', 'PLACED', 'CONFIRMED', 'PACKED',
                                                      'SHIPPED', 'OUT_FOR_DELIVERY', 'DELIVERED', 'CANCELLED')),
    ADD CONSTRAINT ck_orders_payment_method CHECK (payment_method IN ('CARD', 'COD'));

CREATE INDEX ix_orders_status_placed ON orders.orders (status, placed_at);

CREATE TABLE orders.order_status_history
(
    id         uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    order_id   uuid         NOT NULL REFERENCES orders.orders (id) ON DELETE CASCADE,
    status     varchar(20)  NOT NULL,
    note       varchar(500),
    actor      varchar(10)  NOT NULL,
    created_at timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX ix_order_history_order ON orders.order_status_history (order_id, created_at);

-- Backfill the tracking timeline for existing orders.
INSERT INTO orders.order_status_history (order_id, status, note, actor, created_at)
SELECT id, 'PLACED', 'Order placed', 'SYSTEM', placed_at
FROM orders.orders;

INSERT INTO orders.order_status_history (order_id, status, note, actor, created_at)
SELECT id, 'CANCELLED', 'Order cancelled', 'USER', COALESCE(cancelled_at, placed_at)
FROM orders.orders
WHERE status = 'CANCELLED';

CREATE TABLE orders.order_returns
(
    id            uuid PRIMARY KEY       DEFAULT gen_random_uuid(),
    order_id      uuid          NOT NULL REFERENCES orders.orders (id) ON DELETE CASCADE,
    user_id       uuid          NOT NULL,
    status        varchar(20)   NOT NULL,
    reason        varchar(30)   NOT NULL,
    comment       varchar(1000),
    refund_amount numeric(19, 4) NOT NULL,
    admin_note    varchar(1000),
    created_at    timestamptz   NOT NULL DEFAULT now(),
    updated_at    timestamptz   NOT NULL DEFAULT now(),
    version       bigint        NOT NULL DEFAULT 0
);

CREATE INDEX ix_order_returns_order ON orders.order_returns (order_id);
CREATE INDEX ix_order_returns_status ON orders.order_returns (status, created_at DESC);

CREATE TABLE orders.order_return_items
(
    id         uuid PRIMARY KEY        DEFAULT gen_random_uuid(),
    return_id  uuid           NOT NULL REFERENCES orders.order_returns (id) ON DELETE CASCADE,
    product_id uuid           NOT NULL,
    name       varchar(300)   NOT NULL,
    quantity   integer        NOT NULL CHECK (quantity > 0),
    unit_price numeric(19, 4) NOT NULL
);

CREATE INDEX ix_order_return_items_return ON orders.order_return_items (return_id);
