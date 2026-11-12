-- Phase 8: wallet + coins at checkout, member shipping, return refund destination, subscriptions.
ALTER TABLE orders.orders
    ADD COLUMN coins_redeemed            integer        NOT NULL DEFAULT 0,
    ADD COLUMN coins_value               numeric(19, 4) NOT NULL DEFAULT 0,
    ADD COLUMN wallet_amount             numeric(19, 4) NOT NULL DEFAULT 0,
    ADD COLUMN membership_free_shipping  boolean        NOT NULL DEFAULT false,
    ADD COLUMN subscription_id           uuid;

ALTER TABLE orders.orders DROP CONSTRAINT ck_orders_payment_method;
ALTER TABLE orders.orders
    ADD CONSTRAINT ck_orders_payment_method CHECK (payment_method IN ('CARD', 'COD', 'WALLET'));

ALTER TABLE orders.order_returns
    ADD COLUMN refund_to varchar(10) NOT NULL DEFAULT 'SOURCE';

CREATE TABLE orders.subscriptions
(
    id               uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    user_id          uuid         NOT NULL,
    email            varchar(320),
    product_id       uuid         NOT NULL,
    variant_id       uuid,
    product_name     varchar(300),
    quantity         integer      NOT NULL CHECK (quantity BETWEEN 1 AND 99),
    frequency_days   integer      NOT NULL CHECK (frequency_days IN (7, 14, 30, 60)),
    next_run_on      date         NOT NULL,
    address_id       uuid         NOT NULL,
    payment_method   varchar(10)  NOT NULL CHECK (payment_method IN ('COD', 'WALLET')),
    status           varchar(10)  NOT NULL CHECK (status IN ('ACTIVE', 'PAUSED', 'CANCELLED')),
    discount_percent integer      NOT NULL DEFAULT 5,
    last_order_id    uuid,
    failures         integer      NOT NULL DEFAULT 0,
    created_at       timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_subscriptions_user ON orders.subscriptions (user_id, created_at DESC);
CREATE INDEX ix_subscriptions_due ON orders.subscriptions (next_run_on) WHERE status = 'ACTIVE';
