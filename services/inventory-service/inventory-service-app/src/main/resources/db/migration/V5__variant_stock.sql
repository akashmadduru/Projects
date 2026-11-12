-- Phase 7: stock per product variant. A variant without a row here is treated as unlimited.
CREATE TABLE inventory.variant_stock
(
    variant_id uuid PRIMARY KEY,
    product_id uuid,
    quantity   integer     NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE inventory.reservations ADD COLUMN variant_id uuid;
ALTER TABLE inventory.reservations DROP CONSTRAINT ux_reservations_order_product;
CREATE UNIQUE INDEX ux_reservations_order_product_variant
    ON inventory.reservations (order_id, product_id, COALESCE(variant_id, '00000000-0000-0000-0000-000000000000'::uuid));

ALTER TABLE inventory.reservation_releases ADD COLUMN variant_id uuid;
ALTER TABLE inventory.reservation_releases DROP CONSTRAINT ux_reservation_releases;
CREATE UNIQUE INDEX ux_reservation_releases_variant
    ON inventory.reservation_releases (order_id, release_key, product_id, COALESCE(variant_id, '00000000-0000-0000-0000-000000000000'::uuid));

-- Seed stock for the variant ids the product seed uses (dddddddd-0000-...-0000000000NN).
INSERT INTO inventory.variant_stock (variant_id, quantity)
SELECT ('dddddddd-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, 50
FROM unnest(ARRAY[1,2,3,4,5,6,11,12,13,14,15,21,22,23,24,25]) AS n;
