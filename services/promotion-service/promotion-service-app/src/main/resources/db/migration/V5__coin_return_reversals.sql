-- O-05b: per-return coin reversal record (idempotency + prior-due sums). Additive only.
CREATE TABLE promotion.coin_return_reversals
(
    return_id   uuid PRIMARY KEY,
    order_id    uuid        NOT NULL,
    user_id     uuid        NOT NULL,
    redeem_due  integer     NOT NULL,
    earn_due    integer     NOT NULL,
    earn_taken  integer     NOT NULL,
    completes   boolean     NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CHECK (redeem_due >= 0 AND earn_due >= 0 AND earn_taken >= 0 AND earn_taken <= earn_due)
);
CREATE INDEX ix_coin_return_reversals_order ON promotion.coin_return_reversals (order_id);
