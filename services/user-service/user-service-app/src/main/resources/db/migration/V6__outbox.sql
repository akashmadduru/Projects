-- TD-P6-33: transactional outbox for USER_DELETED (same shape as orders.outbox, V10 in order-service).
-- The erasure and the event are written in one transaction; UserOutboxRelay delivers to user.events.
CREATE TABLE users.outbox
(
    id              uuid PRIMARY KEY        DEFAULT gen_random_uuid(),
    -- Delivery order within one aggregate (user id); a row waits while an older one is pending.
    seq             bigserial      NOT NULL UNIQUE,
    aggregate_id    uuid           NOT NULL,
    kind            varchar(40)    NOT NULL,
    payload         jsonb          NOT NULL DEFAULT '{}'::jsonb,
    idem_key        varchar(200)   NOT NULL UNIQUE,
    status          varchar(10)    NOT NULL DEFAULT 'PENDING'
        CONSTRAINT ck_user_outbox_status CHECK (status IN ('PENDING', 'DONE', 'DEAD')),
    attempts        integer        NOT NULL DEFAULT 0,
    next_attempt_at timestamptz    NOT NULL DEFAULT now(),
    last_error      varchar(500),
    created_at      timestamptz    NOT NULL DEFAULT now(),
    done_at         timestamptz
);
CREATE INDEX ix_user_outbox_due ON users.outbox (next_attempt_at, seq) WHERE status = 'PENDING';
CREATE INDEX ix_user_outbox_aggregate_pending ON users.outbox (aggregate_id, seq) WHERE status = 'PENDING';
CREATE INDEX ix_user_outbox_dead ON users.outbox (created_at) WHERE status = 'DEAD';
