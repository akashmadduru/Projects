-- TD-P8-07: wallet money owed back to a customer (cancel, expiry, failed payment, abandoned
-- checkout) is recorded here BEFORE payment-service is called, and retried until it lands.
-- Before this, a payment-service outage at that moment lost the customer's money.
-- payment-service credits are idempotent by idem_key, so a retry can never pay twice.
CREATE TABLE orders.pending_wallet_credits
(
    id              uuid PRIMARY KEY        DEFAULT gen_random_uuid(),
    order_id        uuid           NOT NULL,
    user_id         uuid           NOT NULL,
    amount          numeric(19, 4) NOT NULL CHECK (amount > 0),
    currency        varchar(3)     NOT NULL,
    idem_key        varchar(120)   NOT NULL UNIQUE,
    attempts        integer        NOT NULL DEFAULT 0,
    last_error      varchar(500),
    next_attempt_at timestamptz    NOT NULL DEFAULT now(),
    created_at      timestamptz    NOT NULL DEFAULT now(),
    completed_at    timestamptz
);
CREATE INDEX ix_pending_wallet_credits_due ON orders.pending_wallet_credits (next_attempt_at)
    WHERE completed_at IS NULL;
