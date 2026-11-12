-- payment-service baseline: payments, refunds, processed Stripe webhook events.
CREATE SCHEMA IF NOT EXISTS payment;

CREATE TABLE payment.payments
(
    id                       uuid PRIMARY KEY        DEFAULT gen_random_uuid(),
    order_id                 uuid           NOT NULL UNIQUE,
    user_id                  uuid           NOT NULL,
    method                   varchar(10)    NOT NULL CHECK (method IN ('CARD', 'COD')),
    mode                     varchar(10)    NOT NULL CHECK (mode IN ('STRIPE', 'SIMULATED')),
    amount                   numeric(19, 4) NOT NULL CHECK (amount > 0),
    currency                 char(3)        NOT NULL,
    status                   varchar(20)    NOT NULL CHECK (status IN
                             ('REQUIRES_PAYMENT', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'REFUNDED',
                              'PARTIALLY_REFUNDED', 'COD_PENDING')),
    stripe_payment_intent_id varchar(100),
    refunded_amount          numeric(19, 4) NOT NULL DEFAULT 0,
    -- true once the latest SUCCEEDED/FAILED outcome was delivered to order-service
    reported                 boolean        NOT NULL DEFAULT false,
    report_attempts          integer        NOT NULL DEFAULT 0,
    created_at               timestamptz    NOT NULL DEFAULT now(),
    updated_at               timestamptz    NOT NULL DEFAULT now()
);
CREATE INDEX ix_payments_user ON payment.payments (user_id);
CREATE INDEX ix_payments_intent ON payment.payments (stripe_payment_intent_id);
CREATE INDEX ix_payments_unreported ON payment.payments (updated_at)
    WHERE reported = false AND status IN ('SUCCEEDED', 'FAILED');

CREATE TABLE payment.refunds
(
    id               uuid PRIMARY KEY        DEFAULT gen_random_uuid(),
    payment_id       uuid           NOT NULL REFERENCES payment.payments (id),
    amount           numeric(19, 4) NOT NULL CHECK (amount > 0),
    reason           varchar(200),
    stripe_refund_id varchar(100),
    idempotency_key  varchar(100)   NOT NULL UNIQUE,
    created_at       timestamptz    NOT NULL DEFAULT now()
);

CREATE TABLE payment.processed_events
(
    event_id    varchar(100) PRIMARY KEY,
    received_at timestamptz NOT NULL DEFAULT now()
);
