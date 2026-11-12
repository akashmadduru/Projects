-- Phase 7 integration: returns are keyed by (product, variant).
ALTER TABLE orders.order_return_items ADD COLUMN variant_id uuid;
