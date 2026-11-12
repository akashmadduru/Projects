-- Phase 9: one platform offer per product and per variant (idempotent; also covers products added by seeds).
INSERT INTO product.offers (product_id, variant_id, seller_id, price, sale_price, currency)
SELECT p.id, NULL, '00000000-0000-0000-0000-00000000a11e', p.price_amount, NULL, p.price_currency
FROM product.products p
WHERE NOT EXISTS (SELECT 1 FROM product.offers o
                  WHERE o.product_id = p.id AND o.variant_id IS NULL AND o.seller_id = '00000000-0000-0000-0000-00000000a11e');

INSERT INTO product.offers (product_id, variant_id, seller_id, price, sale_price, currency)
SELECT v.product_id, v.id, '00000000-0000-0000-0000-00000000a11e', COALESCE(v.price_amount, p.price_amount), v.sale_price, p.price_currency
FROM product.product_variants v
         JOIN product.products p ON p.id = v.product_id
WHERE NOT EXISTS (SELECT 1 FROM product.offers o
                  WHERE o.product_id = v.product_id AND o.variant_id = v.id AND o.seller_id = '00000000-0000-0000-0000-00000000a11e');
