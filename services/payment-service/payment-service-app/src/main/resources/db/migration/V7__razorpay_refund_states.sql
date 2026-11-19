-- Razorpay refund lifecycle (F-20261119-04). Forward-only and additive: old pods ignore the new
-- columns and table. Every existing refund becomes SUCCEEDED through the constant default (it is
-- already counted in payments.refunded_amount); the default stays so a pod still on the previous
-- version inserts rows with today's meaning during a rolling deploy.
ALTER TABLE payment.refunds
    ADD COLUMN status            varchar(10) NOT NULL DEFAULT 'SUCCEEDED'
        CONSTRAINT ck_refunds_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    ADD COLUMN settled_at        timestamptz,
    ADD COLUMN pending_alerted_at timestamptz;

-- Non-unique on purpose: the migration must not fail on unexpected duplicates; the webhook warns.
CREATE INDEX ix_refunds_provider_refund ON payment.refunds (provider_refund_id)
    WHERE provider_refund_id IS NOT NULL;
CREATE INDEX ix_refunds_pending ON payment.refunds (created_at) WHERE status = 'PENDING';
CREATE INDEX ix_payments_provider_payment ON payment.payments (provider_payment_id)
    WHERE provider_payment_id IS NOT NULL;

-- Refund events that arrived before their refund row was committed. Applied and deleted by
-- refund(); rows for refunds made outside this service (dashboard) stay (retention is backlog).
CREATE TABLE payment.razorpay_refund_parked
(
    provider_refund_id  varchar(100) NOT NULL,
    status              varchar(10)  NOT NULL,
    provider_payment_id varchar(100) NOT NULL,
    amount_minor        bigint       NOT NULL,
    currency            char(3)      NOT NULL,
    received_at         timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (provider_refund_id, status)
);
