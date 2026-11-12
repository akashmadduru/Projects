-- promotion-service baseline: coupons, redemptions, seed coupons.
CREATE SCHEMA IF NOT EXISTS promotion;

CREATE TABLE promotion.coupons
(
    id               uuid PRIMARY KEY        DEFAULT gen_random_uuid(),
    code             varchar(40)    NOT NULL UNIQUE CHECK (code = upper(code)),
    description      varchar(300),
    type             varchar(10)    NOT NULL CHECK (type IN ('PERCENT', 'FLAT')),
    value            numeric(19, 4) NOT NULL CHECK (value > 0),
    max_discount     numeric(19, 4) CHECK (max_discount > 0),
    min_order_amount numeric(19, 4) NOT NULL DEFAULT 0 CHECK (min_order_amount >= 0),
    -- null = any currency (PERCENT only); required for FLAT
    currency         char(3),
    starts_at        timestamptz,
    ends_at          timestamptz,
    total_limit      integer CHECK (total_limit > 0),
    per_user_limit   integer CHECK (per_user_limit > 0),
    first_order_only boolean        NOT NULL DEFAULT false,
    active           boolean        NOT NULL DEFAULT true,
    created_at       timestamptz    NOT NULL DEFAULT now(),
    updated_at       timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT ck_flat_has_currency CHECK (type <> 'FLAT' OR currency IS NOT NULL)
);

CREATE TABLE promotion.redemptions
(
    id              uuid PRIMARY KEY        DEFAULT gen_random_uuid(),
    coupon_id       uuid           NOT NULL REFERENCES promotion.coupons (id),
    user_id         uuid           NOT NULL,
    order_id        uuid           NOT NULL UNIQUE,
    discount_amount numeric(19, 4) NOT NULL,
    status          varchar(10)    NOT NULL CHECK (status IN ('APPLIED', 'RELEASED')),
    created_at      timestamptz    NOT NULL DEFAULT now()
);
CREATE INDEX ix_redemptions_coupon ON promotion.redemptions (coupon_id, status);
CREATE INDEX ix_redemptions_coupon_user ON promotion.redemptions (coupon_id, user_id, status);

INSERT INTO promotion.coupons (code, description, type, value, max_discount, min_order_amount,
                               currency, ends_at, first_order_only)
VALUES ('WELCOME10', '10% off your first order (up to 200)', 'PERCENT', 10, 200, 0, NULL, NULL, true),
       ('FLAT100', 'Flat INR 100 off orders above INR 999', 'FLAT', 100, NULL, 999, 'INR', NULL, false),
       ('SAVE5', 'Flat USD 5 off orders above USD 50', 'FLAT', 5, NULL, 50, 'USD', NULL, false),
       ('FESTIVE20', '20% off orders above 1999 (up to 500)', 'PERCENT', 20, 500, 1999, NULL,
        now() + interval '60 days', false);
