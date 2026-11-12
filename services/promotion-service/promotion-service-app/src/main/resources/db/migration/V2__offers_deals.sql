-- Phase 7: bank offers and deals of the day.
CREATE TABLE promotion.bank_offers
(
    id            uuid PRIMARY KEY        DEFAULT gen_random_uuid(),
    bank          varchar(60)    NOT NULL,
    card_type     varchar(10)    NOT NULL DEFAULT 'ANY' CHECK (card_type IN ('CREDIT', 'DEBIT', 'ANY')),
    title         varchar(200)   NOT NULL,
    discount_type varchar(10)    NOT NULL CHECK (discount_type IN ('PERCENT', 'FLAT')),
    value         numeric(19, 4) NOT NULL CHECK (value > 0),
    max_discount  numeric(19, 4),
    min_amount    numeric(19, 4) NOT NULL DEFAULT 0,
    currency      char(3),
    active        boolean        NOT NULL DEFAULT true,
    starts_at     timestamptz,
    ends_at       timestamptz
);

INSERT INTO promotion.bank_offers (bank, card_type, title, discount_type, value, max_discount, min_amount, currency)
VALUES ('HDFC Bank', 'CREDIT', '10% instant discount on HDFC credit cards (up to INR 1500)', 'PERCENT', 10, 1500, 5000, 'INR'),
       ('ICICI Bank', 'ANY', 'Flat INR 250 off on ICICI cards', 'FLAT', 250, NULL, 2500, 'INR'),
       ('Axis Bank', 'ANY', '5% off with Axis Bank cards (up to 50)', 'PERCENT', 5, 50, 0, NULL);

CREATE TABLE promotion.deals
(
    id             uuid PRIMARY KEY        DEFAULT gen_random_uuid(),
    product_id     uuid           NOT NULL,
    variant_id     uuid,
    title          varchar(200),
    deal_price     numeric(19, 4) NOT NULL CHECK (deal_price > 0),
    currency       char(3)        NOT NULL,
    starts_at      timestamptz    NOT NULL DEFAULT now(),
    ends_at        timestamptz    NOT NULL,
    quantity_limit integer        NOT NULL CHECK (quantity_limit > 0),
    claimed        integer        NOT NULL DEFAULT 0 CHECK (claimed >= 0),
    active         boolean        NOT NULL DEFAULT true,
    created_at     timestamptz    NOT NULL DEFAULT now()
);
CREATE INDEX ix_deals_product ON promotion.deals (product_id, active);

CREATE TABLE promotion.deal_claims
(
    id         uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    order_id   uuid        NOT NULL,
    deal_id    uuid        NOT NULL REFERENCES promotion.deals (id),
    quantity   integer     NOT NULL CHECK (quantity > 0),
    released   boolean     NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (order_id, deal_id)
);

INSERT INTO promotion.deals (product_id, title, deal_price, currency, ends_at, quantity_limit)
VALUES ('33333333-3333-4333-8333-333333333333', 'Monitor flash deal', 349.00, 'USD', now() + interval '2 days', 20),
       ('bbbbbbbb-bbbb-bbbb-bbbb-000000000001', 'Air Fryer deal of the day', 99.99, 'USD', now() + interval '1 day', 30),
       ('aaaaaaaa-aaaa-aaaa-aaaa-000000000001', 'Audio Interface special', 149.99, 'USD', now() + interval '3 days', 15),
       ('dddddddd-dddd-dddd-dddd-000000000001', 'Backpack 65L trail deal', 99.99, 'USD', now() + interval '2 days', 25);
