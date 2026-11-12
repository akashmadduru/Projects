-- Phase 8: membership, notification preferences, referrals.

CREATE TABLE users.membership_plans
(
    code   varchar(40) PRIMARY KEY,
    name   varchar(80) NOT NULL,
    days   integer     NOT NULL CHECK (days > 0),
    active boolean     NOT NULL DEFAULT true
);

CREATE TABLE users.membership_plan_prices
(
    plan_code varchar(40)    NOT NULL REFERENCES users.membership_plans (code),
    currency  char(3)        NOT NULL,
    price     numeric(19, 4) NOT NULL CHECK (price > 0),
    PRIMARY KEY (plan_code, currency)
);

INSERT INTO users.membership_plans (code, name, days)
VALUES ('PLUS_MONTHLY', 'Plus Monthly', 30),
       ('PLUS_YEARLY', 'Plus Yearly', 365);

INSERT INTO users.membership_plan_prices (plan_code, currency, price)
VALUES ('PLUS_MONTHLY', 'INR', 149),
       ('PLUS_MONTHLY', 'USD', 2.99),
       ('PLUS_MONTHLY', 'EUR', 2.79),
       ('PLUS_YEARLY', 'INR', 999),
       ('PLUS_YEARLY', 'USD', 19.99),
       ('PLUS_YEARLY', 'EUR', 18.99);

CREATE TABLE users.memberships
(
    user_id    uuid PRIMARY KEY,
    plan       varchar(40) NOT NULL,
    status     varchar(20) NOT NULL CHECK (status IN ('PENDING_PAYMENT', 'ACTIVE', 'EXPIRED', 'CANCELLED')),
    starts_at  timestamptz,
    ends_at    timestamptz,
    auto_renew boolean     NOT NULL DEFAULT false,
    payment_id varchar(64),
    email      varchar(320),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_memberships_ends ON users.memberships (ends_at) WHERE status IN ('ACTIVE', 'CANCELLED');

-- One row per purchase attempt; its id is the referenceId sent to payment-service (purpose MEMBERSHIP).
CREATE TABLE users.membership_payments
(
    id         uuid PRIMARY KEY        DEFAULT gen_random_uuid(),
    user_id    uuid           NOT NULL,
    plan       varchar(40)    NOT NULL,
    currency   char(3)        NOT NULL,
    amount     numeric(19, 4) NOT NULL,
    email      varchar(320),
    status     varchar(20)    NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    payment_id varchar(64),
    created_at timestamptz    NOT NULL DEFAULT now()
);
CREATE INDEX ix_membership_payments_user ON users.membership_payments (user_id);

CREATE TABLE users.user_preferences
(
    user_id          uuid PRIMARY KEY,
    email_orders     boolean     NOT NULL DEFAULT true,
    email_promotions boolean     NOT NULL DEFAULT true,
    push_orders      boolean     NOT NULL DEFAULT true,
    push_promotions  boolean     NOT NULL DEFAULT false,
    sms              boolean     NOT NULL DEFAULT true,
    updated_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE users.referral_codes
(
    user_id    uuid PRIMARY KEY,
    code       varchar(8)  NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE users.referral_claims
(
    referee_user_id  uuid PRIMARY KEY,
    referrer_user_id uuid        NOT NULL,
    code             varchar(8)  NOT NULL,
    claimed_at       timestamptz NOT NULL DEFAULT now(),
    rewarded_at      timestamptz
);
CREATE INDEX ix_referral_claims_referrer ON users.referral_claims (referrer_user_id);
