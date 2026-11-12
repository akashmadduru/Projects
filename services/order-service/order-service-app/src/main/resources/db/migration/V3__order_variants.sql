-- Phase 7: variant-aware order lines (existing rows keep variant_id null).
ALTER TABLE orders.order_items
    ADD COLUMN variant_id    uuid,
    ADD COLUMN variant_label varchar(200);

CREATE INDEX ix_order_items_product ON orders.order_items (product_id);
