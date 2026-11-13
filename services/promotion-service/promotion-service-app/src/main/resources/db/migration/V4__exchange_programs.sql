-- Phase 10: exchange offers (trade in an old device for a discount on a new one).
CREATE TABLE promotion.exchange_programs
(
    id          uuid PRIMARY KEY        DEFAULT gen_random_uuid(),
    category_id uuid           NOT NULL,
    brand       varchar(80),
    title       varchar(200)   NOT NULL,
    max_value   numeric(19, 4) NOT NULL CHECK (max_value > 0),
    currency    char(3)        NOT NULL,
    active      boolean        NOT NULL DEFAULT true,
    created_at  timestamptz    NOT NULL DEFAULT now()
);

CREATE TABLE promotion.exchange_quotes
(
    id               uuid PRIMARY KEY        DEFAULT gen_random_uuid(),
    user_id          uuid           NOT NULL,
    program_id       uuid           NOT NULL REFERENCES promotion.exchange_programs (id),
    device_brand     varchar(80)    NOT NULL,
    device_model     varchar(120)   NOT NULL,
    device_condition varchar(20)    NOT NULL CHECK (device_condition IN ('WORKING', 'SCREEN_DAMAGED', 'NOT_WORKING')),
    age_years        integer        NOT NULL CHECK (age_years >= 0),
    quoted_value     numeric(19, 4) NOT NULL,
    currency         char(3)        NOT NULL,
    expires_at       timestamptz    NOT NULL,
    status           varchar(12)    NOT NULL DEFAULT 'QUOTED' CHECK (status IN ('QUOTED', 'APPLIED', 'PICKED_UP', 'REJECTED')),
    order_id         uuid,
    final_value      numeric(19, 4),
    shortfall        numeric(19, 4) NOT NULL DEFAULT 0,
    created_at       timestamptz    NOT NULL DEFAULT now(),
    verified_at      timestamptz
);
CREATE INDEX ix_exchange_quotes_user ON promotion.exchange_quotes (user_id, created_at DESC);
CREATE INDEX ix_exchange_quotes_order ON promotion.exchange_quotes (order_id);

-- Seed against the seeded categories (Computers, Electronics, Audio). USD catalog.
INSERT INTO promotion.exchange_programs (category_id, brand, title, max_value, currency)
VALUES ('11111111-1111-1111-1111-111111111201', NULL, 'Laptop exchange - up to 150 off', 150, 'USD'),
       ('11111111-1111-1111-1111-111111111101', NULL, 'Phone and electronics exchange - up to 200 off', 200, 'USD'),
       ('11111111-1111-1111-1111-111111111203', NULL, 'Audio exchange - up to 40 off', 40, 'USD');
