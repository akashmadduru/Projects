-- TD-BE-15: one cart, one order. A cart-based order records a fingerprint of the cart it consumed
-- (every line's product, variant, offer, quantity and added-at instant). Two checkouts that read the
-- same cart before it was cleared (two tabs, or a retry with a new Idempotency-Key after the
-- best-effort clear failed) produce the same fingerprint, and this index lets only one of them in.
-- A cancelled order leaves the index, so a failed or abandoned checkout can be retried; re-adding
-- items after a clear gives them new added-at instants, so a deliberate repeat order is a new cart.
ALTER TABLE orders.orders ADD COLUMN cart_fingerprint varchar(64);

CREATE UNIQUE INDEX ux_orders_user_cart_fingerprint
    ON orders.orders (user_id, cart_fingerprint)
    WHERE cart_fingerprint IS NOT NULL AND status <> 'CANCELLED';
