-- O-05a: per-return refund progress. Additive only: every column is nullable or defaulted and
-- nothing is backfilled, so rows created before this migration load with null legs and are frozen
-- on their first retry. Old code ignores these columns.
ALTER TABLE orders.order_returns
    ADD COLUMN shipping_refund     numeric(19, 4) NOT NULL DEFAULT 0,
    ADD COLUMN wallet_leg_amount   numeric(19, 4),
    ADD COLUMN rest_leg_amount     numeric(19, 4),
    ADD COLUMN rest_route          varchar(16),   -- NONE|PAYMENT_SOURCE|PAYMENT_WALLET|DIRECT_WALLET
    ADD COLUMN wallet_leg_done_at  timestamptz,
    ADD COLUMN rest_leg_state      varchar(12),   -- null=not started|PENDING|SUCCEEDED|FAILED
    ADD COLUMN rest_leg_attempt    int            NOT NULL DEFAULT 0,
    ADD COLUMN refund_lease_until  timestamptz;

CREATE INDEX ix_order_returns_rest_pending
    ON orders.order_returns (updated_at) WHERE rest_leg_state = 'PENDING';
