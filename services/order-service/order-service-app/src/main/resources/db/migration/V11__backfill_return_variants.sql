-- TD-P7-08: return lines created before V4 have variant_id NULL, so they restock the product row
-- instead of the variant. Backfill from the order: only where the order has exactly ONE variant line
-- for that product, so the match is unambiguous. Ambiguous rows (two variants of one product in the
-- same order) stay NULL and keep the old behaviour. Idempotent; touches no row twice.
UPDATE orders.order_return_items ri
SET variant_id = m.variant_id
FROM (
    SELECT r.id AS return_id, oi.product_id, min(oi.variant_id::text)::uuid AS variant_id
    FROM orders.order_returns r
             JOIN orders.order_items oi ON oi.order_id = r.order_id
    WHERE oi.variant_id IS NOT NULL
    GROUP BY r.id, oi.product_id
    HAVING count(DISTINCT oi.variant_id) = 1
) m
WHERE ri.return_id = m.return_id
  AND ri.product_id = m.product_id
  AND ri.variant_id IS NULL;
