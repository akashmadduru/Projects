-- TD-P6-01: transactional outbox. Side effects of an order change (stock restore, coupon/coins/deal/
-- exchange release, payment cancel, COD collected, return restock, order.events) are written here in
-- the same transaction as the change, then delivered by OutboxRelay with retries. Before this they
-- ran after the commit and a crash or an outage lost them.
CREATE TABLE orders.outbox
(
    id              uuid PRIMARY KEY        DEFAULT gen_random_uuid(),
    -- Delivery order within one aggregate (order id); a row waits while an older one is pending.
    seq             bigserial      NOT NULL UNIQUE,
    aggregate_id    uuid           NOT NULL,
    kind            varchar(40)    NOT NULL,
    payload         jsonb          NOT NULL DEFAULT '{}'::jsonb,
    -- One row per effect: a second request for the same effect is a no-op.
    idem_key        varchar(200)   NOT NULL UNIQUE,
    status          varchar(10)    NOT NULL DEFAULT 'PENDING'
        CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'DONE', 'DEAD')),
    attempts        integer        NOT NULL DEFAULT 0,
    next_attempt_at timestamptz    NOT NULL DEFAULT now(),
    last_error      varchar(500),
    created_at      timestamptz    NOT NULL DEFAULT now(),
    done_at         timestamptz
);
CREATE INDEX ix_outbox_due ON orders.outbox (next_attempt_at, seq) WHERE status = 'PENDING';
CREATE INDEX ix_outbox_aggregate_pending ON orders.outbox (aggregate_id, seq) WHERE status = 'PENDING';
CREATE INDEX ix_outbox_dead ON orders.outbox (created_at) WHERE status = 'DEAD';
