-- Phase 8: payment purposes, wallet, gift cards. Forward-only.
-- order_id stays the unique "subject id" of a payment: for purpose ORDER it is the order id,
-- for MEMBERSHIP / GIFT_CARD it is the membership / gift card id (same value as reference_id).
ALTER TABLE payment.payments
    ADD COLUMN purpose      varchar(12) NOT NULL DEFAULT 'ORDER',
    ADD COLUMN reference_id uuid;
UPDATE payment.payments SET reference_id = order_id;
ALTER TABLE payment.payments
    ADD CONSTRAINT ck_payments_purpose CHECK (purpose IN ('ORDER', 'MEMBERSHIP', 'GIFT_CARD'));

CREATE TABLE payment.wallets
(
    user_id    uuid PRIMARY KEY,
    balance    numeric(19, 4) NOT NULL DEFAULT 0 CHECK (balance >= 0),
    currency   char(3)        NOT NULL,
    created_at timestamptz    NOT NULL DEFAULT now(),
    updated_at timestamptz    NOT NULL DEFAULT now()
);

CREATE TABLE payment.wallet_txns
(
    id              uuid PRIMARY KEY        DEFAULT gen_random_uuid(),
    user_id         uuid           NOT NULL,
    type            varchar(6)     NOT NULL CHECK (type IN ('CREDIT', 'DEBIT')),
    amount          numeric(19, 4) NOT NULL CHECK (amount > 0),
    currency        char(3)        NOT NULL,
    reason          varchar(12)    NOT NULL CHECK (reason IN
                    ('REFUND', 'GIFT_CARD', 'ORDER', 'REFERRAL', 'ADJUSTMENT', 'CASHBACK')),
    reference_id    varchar(100),
    idempotency_key varchar(150)   NOT NULL UNIQUE,
    balance_after   numeric(19, 4) NOT NULL,
    created_at      timestamptz    NOT NULL DEFAULT now()
);
CREATE INDEX ix_wallet_txns_user ON payment.wallet_txns (user_id, created_at DESC);

CREATE TABLE payment.gift_cards
(
    id                uuid PRIMARY KEY        DEFAULT gen_random_uuid(),
    code              varchar(19)    NOT NULL UNIQUE,
    amount            numeric(19, 4) NOT NULL CHECK (amount > 0),
    currency          char(3)        NOT NULL,
    status            varchar(16)    NOT NULL CHECK (status IN
                      ('ACTIVE', 'REDEEMED', 'PENDING_PAYMENT', 'CANCELLED')),
    purchaser_user_id uuid           NOT NULL,
    purchaser_email   varchar(320),
    recipient_email   varchar(320)   NOT NULL,
    message           varchar(500),
    redeemed_by       uuid,
    redeemed_at       timestamptz,
    created_at        timestamptz    NOT NULL DEFAULT now(),
    expires_at        timestamptz    NOT NULL DEFAULT now() + interval '1 year'
);
CREATE INDEX ix_gift_cards_purchaser ON payment.gift_cards (purchaser_user_id, created_at DESC);
