-- Phase 9: seller onboarding/KYC, feedback, settlements ledger + payouts, sale lines, ad campaigns.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE sellers
(
    id                  uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    user_id             uuid         NOT NULL UNIQUE,
    legal_name          varchar(200),
    display_name        varchar(120) NOT NULL,
    slug                varchar(140) NOT NULL UNIQUE,
    email               varchar(254),
    phone               varchar(20),
    gstin               varchar(15),
    pan                 varchar(10),
    bank_account_last4  varchar(4),
    -- AES-256-GCM (base64 iv||ciphertext) of the FULL account number; key from SELLER_DATA_KEY.
    bank_account_hash   text,
    ifsc                varchar(11),
    pickup_address      jsonb,
    status              varchar(20)  NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'SUSPENDED')),
    rejection_reason    varchar(500),
    commission_percent  numeric(5, 2) NOT NULL DEFAULT 10,
    rating_avg          numeric(3, 2) NOT NULL DEFAULT 0,
    rating_count        integer      NOT NULL DEFAULT 0,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    approved_at         timestamptz,
    updated_at          timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_sellers_status ON sellers (status);

-- The platform seller that owns every pre-marketplace product/offer/stock row.
INSERT INTO sellers (id, user_id, legal_name, display_name, slug, email, status, commission_percent,
                     rating_avg, rating_count, approved_at)
VALUES ('00000000-0000-0000-0000-00000000a11e', '00000000-0000-0000-0000-00000000a11f',
        'Acme Retail Pvt Ltd', 'Acme Retail', 'acme-retail', 'sellers@acme.local', 'APPROVED', 0,
        4.50, 0, now());

CREATE TABLE seller_feedback
(
    id          uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    seller_id   uuid        NOT NULL REFERENCES sellers (id),
    order_id    uuid        NOT NULL,
    shipment_id uuid        NOT NULL,
    user_id     uuid        NOT NULL,
    rating      smallint    NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment     varchar(500),
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_feedback_shipment_user UNIQUE (shipment_id, user_id)
);
CREATE INDEX ix_feedback_seller ON seller_feedback (seller_id, created_at DESC);

CREATE TABLE payouts
(
    id         uuid PRIMARY KEY       DEFAULT gen_random_uuid(),
    seller_id  uuid          NOT NULL REFERENCES sellers (id),
    amount     numeric(14, 2) NOT NULL,
    currency   varchar(3)    NOT NULL,
    status     varchar(20)   NOT NULL DEFAULT 'PAID',
    reference  varchar(40)   NOT NULL,
    created_at timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX ix_payouts_seller ON payouts (seller_id, created_at DESC);

CREATE TABLE ledger_entries
(
    id          uuid PRIMARY KEY       DEFAULT gen_random_uuid(),
    seller_id   uuid           NOT NULL REFERENCES sellers (id),
    type        varchar(30)    NOT NULL
        CHECK (type IN ('SALE', 'COMMISSION', 'FEE', 'REFUND_REVERSAL', 'COMMISSION_REVERSAL')),
    amount      numeric(14, 2) NOT NULL,
    currency    varchar(3)     NOT NULL,
    order_id    uuid           NOT NULL,
    shipment_id uuid           NOT NULL,
    created_at  timestamptz    NOT NULL DEFAULT now(),
    payout_id   uuid REFERENCES payouts (id),
    -- Idempotency key for redelivered order events.
    CONSTRAINT ux_ledger_shipment_type UNIQUE (shipment_id, type)
);
CREATE INDEX ix_ledger_seller_created ON ledger_entries (seller_id, created_at DESC);
CREATE INDEX ix_ledger_unsettled ON ledger_entries (seller_id, currency) WHERE payout_id IS NULL;

-- Per-line detail of delivered shipments (when order.events carries it) for analytics.
CREATE TABLE sale_lines
(
    id          uuid PRIMARY KEY       DEFAULT gen_random_uuid(),
    seller_id   uuid           NOT NULL REFERENCES sellers (id),
    order_id    uuid           NOT NULL,
    shipment_id uuid           NOT NULL,
    product_id  uuid           NOT NULL,
    name        varchar(300),
    quantity    integer        NOT NULL,
    amount      numeric(14, 2) NOT NULL,
    created_at  timestamptz    NOT NULL DEFAULT now()
);
CREATE INDEX ix_sale_lines_seller ON sale_lines (seller_id, created_at);

CREATE TABLE ad_campaigns
(
    id            uuid PRIMARY KEY       DEFAULT gen_random_uuid(),
    seller_id     uuid           NOT NULL REFERENCES sellers (id),
    product_id    uuid           NOT NULL,
    keywords      text[]         NOT NULL DEFAULT '{}',
    category_id   uuid,
    bid_cpc       numeric(10, 2) NOT NULL CHECK (bid_cpc > 0),
    daily_budget  numeric(12, 2) NOT NULL CHECK (daily_budget > 0),
    currency      varchar(3)     NOT NULL DEFAULT 'USD',
    status        varchar(20)    NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'PAUSED', 'EXHAUSTED')),
    spent_today   numeric(12, 2) NOT NULL DEFAULT 0,
    clicks        bigint         NOT NULL DEFAULT 0,
    impressions   bigint         NOT NULL DEFAULT 0,
    created_at    timestamptz    NOT NULL DEFAULT now()
);
CREATE INDEX ix_ads_seller ON ad_campaigns (seller_id);
CREATE INDEX ix_ads_active ON ad_campaigns (status) WHERE status = 'ACTIVE';
