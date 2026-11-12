-- Phase 9: warehouses + stock keyed by (product, variant, seller, warehouse).
CREATE TABLE inventory.warehouses
(
    id         uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    code       varchar(32)  NOT NULL UNIQUE,
    name       varchar(200) NOT NULL,
    seller_id  uuid         NOT NULL,
    pincode    varchar(10)  NOT NULL,
    state      varchar(64),
    active     boolean      NOT NULL DEFAULT true,
    created_at timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_warehouses_seller ON inventory.warehouses (seller_id);

INSERT INTO inventory.warehouses (id, code, name, seller_id, pincode, state)
VALUES ('eeeeeeee-0000-0000-0000-0000000000b1', 'WH-BLR', 'Bengaluru Fulfilment Centre', '00000000-0000-0000-0000-00000000a11e', '560001', 'KA'),
       ('eeeeeeee-0000-0000-0000-0000000000d1', 'WH-DEL', 'Delhi Fulfilment Centre', '00000000-0000-0000-0000-00000000a11e', '110001', 'DL'),
       ('eeeeeeee-0000-0000-0000-0000000000e1', 'WH-MUM', 'Mumbai Fulfilment Centre', '00000000-0000-0000-0000-00000000a11e', '400001', 'MH');

CREATE TABLE inventory.warehouse_stock
(
    id           uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    product_id   uuid        NOT NULL,
    variant_id   uuid,
    variant_key  uuid GENERATED ALWAYS AS (COALESCE(variant_id, '00000000-0000-0000-0000-000000000000'::uuid)) STORED,
    seller_id    uuid        NOT NULL,
    offer_id     uuid,
    warehouse_id uuid        NOT NULL REFERENCES inventory.warehouses (id),
    quantity     integer     NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_warehouse_stock UNIQUE (product_id, variant_key, seller_id, warehouse_id)
);
CREATE INDEX idx_warehouse_stock_offer ON inventory.warehouse_stock (offer_id);
CREATE INDEX idx_warehouse_stock_seller ON inventory.warehouse_stock (seller_id);

-- Existing stock belongs to the platform seller at WH-BLR.
INSERT INTO inventory.warehouse_stock (product_id, variant_id, seller_id, warehouse_id, quantity)
SELECT product_id, NULL, '00000000-0000-0000-0000-00000000a11e', 'eeeeeeee-0000-0000-0000-0000000000b1', quantity
FROM inventory.stock_levels;

INSERT INTO inventory.warehouse_stock (product_id, variant_id, seller_id, warehouse_id, quantity)
SELECT product_id, variant_id, '00000000-0000-0000-0000-00000000a11e', 'eeeeeeee-0000-0000-0000-0000000000b1', quantity
FROM inventory.variant_stock
WHERE product_id IS NOT NULL;

ALTER TABLE inventory.reservations
    ADD COLUMN warehouse_id uuid,
    ADD COLUMN offer_id uuid,
    ADD COLUMN seller_id uuid;
UPDATE inventory.reservations
SET warehouse_id = 'eeeeeeee-0000-0000-0000-0000000000b1',
    seller_id    = '00000000-0000-0000-0000-00000000a11e';

DROP INDEX inventory.ux_reservations_order_product_variant;
CREATE UNIQUE INDEX ux_reservations_order_line
    ON inventory.reservations (order_id, product_id, COALESCE(variant_id, '00000000-0000-0000-0000-000000000000'::uuid),
                               COALESCE(warehouse_id, '00000000-0000-0000-0000-000000000000'::uuid),
                               COALESCE(seller_id, '00000000-0000-0000-0000-000000000000'::uuid));
