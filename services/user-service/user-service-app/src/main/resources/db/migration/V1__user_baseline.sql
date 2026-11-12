-- user-service baseline: customer profile + saved shipping addresses.
-- Everything lives in the `users` schema (Flyway creates it). user_id is the auth-service
-- subject (JWT `sub`); there is no FK to auth_db (different database).
CREATE SCHEMA IF NOT EXISTS users;

CREATE TABLE users.user_profiles
(
    user_id      uuid PRIMARY KEY,
    email        varchar(320),
    display_name varchar(120),
    phone        varchar(32),
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    version      bigint      NOT NULL DEFAULT 0
);

CREATE TABLE users.addresses
(
    id          uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    user_id     uuid        NOT NULL,
    label       varchar(60),
    full_name   varchar(120) NOT NULL,
    phone       varchar(32),
    line1       varchar(200) NOT NULL,
    line2       varchar(200),
    city        varchar(100) NOT NULL,
    state       varchar(100),
    postal_code varchar(20)  NOT NULL,
    country     char(2)      NOT NULL,
    is_default  boolean      NOT NULL DEFAULT false,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    version     bigint       NOT NULL DEFAULT 0
);

CREATE INDEX ix_addresses_user ON users.addresses (user_id);
-- At most one default address per user, enforced by the database.
CREATE UNIQUE INDEX ux_addresses_one_default ON users.addresses (user_id) WHERE is_default;
