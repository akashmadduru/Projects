-- Phase 9: marketplace split orders. One shipment per (seller, warehouse); order status becomes derived.
-- Every existing order gets exactly one shipment owned by the platform seller, shipped from WH-BLR.
CREATE TABLE orders.order_shipments
(
    id                      uuid PRIMARY KEY        DEFAULT gen_random_uuid(),
    order_id                uuid           NOT NULL REFERENCES orders.orders (id) ON DELETE CASCADE,
    seller_id               uuid           NOT NULL,
    seller_name             varchar(200),
    warehouse_id            uuid,
    warehouse_code          varchar(32),
    warehouse_pincode       varchar(20),
    status                  varchar(20)    NOT NULL,
    tracking_number         varchar(100),
    carrier                 varchar(100),
    estimated_delivery_date date,
    delivered_at            timestamptz,
    amount                  numeric(19, 4) NOT NULL DEFAULT 0,
    created_at              timestamptz    NOT NULL DEFAULT now(),
    updated_at              timestamptz    NOT NULL DEFAULT now(),
    version                 bigint         NOT NULL DEFAULT 0
);

CREATE INDEX ix_order_shipments_order ON orders.order_shipments (order_id);
CREATE INDEX ix_order_shipments_seller ON orders.order_shipments (seller_id, status, created_at DESC);

ALTER TABLE orders.order_items
    ADD COLUMN shipment_id uuid REFERENCES orders.order_shipments (id),
    ADD COLUMN offer_id    uuid,
    ADD COLUMN seller_id   uuid;
CREATE INDEX ix_order_items_shipment ON orders.order_items (shipment_id);

ALTER TABLE orders.order_status_history ADD COLUMN shipment_id uuid;
ALTER TABLE orders.order_returns ADD COLUMN shipment_id uuid;

INSERT INTO orders.order_shipments (order_id, seller_id, seller_name, warehouse_code, warehouse_pincode, status,
                                    tracking_number, carrier, estimated_delivery_date, delivered_at, amount,
                                    created_at, updated_at)
SELECT o.id, '00000000-0000-0000-0000-00000000a11e', 'Acme Retail', 'WH-BLR', '560001', o.status,
       o.tracking_number, o.carrier, o.estimated_delivery_date, o.delivered_at,
       COALESCE((SELECT sum(i.line_total) FROM orders.order_items i WHERE i.order_id = o.id), 0),
       o.placed_at, o.placed_at
FROM orders.orders o;

UPDATE orders.order_items i
SET shipment_id = s.id,
    seller_id   = s.seller_id
FROM orders.order_shipments s
WHERE s.order_id = i.order_id;

UPDATE orders.order_status_history h
SET shipment_id = s.id
FROM orders.order_shipments s
WHERE s.order_id = h.order_id;

UPDATE orders.order_returns r
SET shipment_id = s.id
FROM orders.order_shipments s
WHERE s.order_id = r.order_id;
