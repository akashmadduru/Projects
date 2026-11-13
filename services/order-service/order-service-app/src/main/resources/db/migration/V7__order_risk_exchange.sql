-- Phase 10 trust: risk scoring + hold, return abuse flag, exchange offers.
ALTER TABLE orders.orders
    ADD COLUMN risk_score         integer        NOT NULL DEFAULT 0,
    ADD COLUMN risk_reasons       text           NOT NULL DEFAULT '[]',
    ADD COLUMN risk_hold          boolean        NOT NULL DEFAULT false,
    ADD COLUMN exchange_quote_id  uuid,
    ADD COLUMN exchange_amount    numeric(19, 4) NOT NULL DEFAULT 0,
    ADD COLUMN exchange_shortfall numeric(19, 4) NOT NULL DEFAULT 0;
CREATE INDEX ix_orders_risk_hold ON orders.orders (placed_at DESC) WHERE risk_hold;

ALTER TABLE orders.order_returns ADD COLUMN abuse_flag boolean NOT NULL DEFAULT false;
