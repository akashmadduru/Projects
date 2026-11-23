-- F-14: per-shipment cancel and refund. Additive only: every column is nullable or defaulted and
-- nothing is backfilled, so existing shipments load with no cancel claim. Old code ignores these.
-- Fail fast instead of queueing behind a long transaction: just redeploy.
SET LOCAL lock_timeout = '5s';

ALTER TABLE orders.order_shipments
    ADD COLUMN cancel_refund_amount   numeric(19, 4),
    ADD COLUMN cancel_shipping_refund numeric(19, 4) NOT NULL DEFAULT 0,
    ADD COLUMN cancel_wallet_leg      numeric(19, 4),
    ADD COLUMN cancel_rest_leg        numeric(19, 4),
    ADD COLUMN cancel_rest_route      varchar(16),  -- NONE|PAYMENT_SOURCE|PAYMENT_WALLET|DIRECT_WALLET
    ADD COLUMN cancel_state           varchar(12),  -- null|REQUESTED|FAILED|DONE
    ADD COLUMN cancel_attempt         int            NOT NULL DEFAULT 0;
