-- Phase 6: per-order stock reservations (decrement on order, restore on cancel/return).
CREATE TABLE inventory.reservations
(
    id                uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    order_id          uuid        NOT NULL,
    product_id        uuid        NOT NULL,
    quantity          integer     NOT NULL CHECK (quantity > 0),
    restored_quantity integer     NOT NULL DEFAULT 0 CHECK (restored_quantity >= 0),
    created_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_reservations_order_product UNIQUE (order_id, product_id)
);

-- Idempotency ledger for partial restocks (returns): one row per (order, release key, product).
CREATE TABLE inventory.reservation_releases
(
    id          uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    order_id    uuid         NOT NULL,
    release_key varchar(100) NOT NULL,
    product_id  uuid         NOT NULL,
    quantity    integer      NOT NULL CHECK (quantity > 0),
    created_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ux_reservation_releases UNIQUE (order_id, release_key, product_id)
);
