-- =============================================================================
-- product-service: Phase 7 demo data (brand, attributes, variants). DEV ONLY.
-- Repeatable and idempotent; runs after the demo seed (R__ ordered by description).
-- =============================================================================

-- Brand heuristic: the seeded seller name doubles as the brand (see tech debt).
UPDATE product.products SET brand = seller_name WHERE brand IS NULL AND seller_name IS NOT NULL;

-- Filterable attributes for a handful of products.
UPDATE product.products SET attributes = '{"RAM":"16 GB","Storage":"512 GB SSD"}'::jsonb
 WHERE attributes IS NULL AND name IN ('Laptop Pro 15', 'Laptop Ultrabook', 'Desktop PC Gaming');
UPDATE product.products SET attributes = '{"RAM":"8 GB","Storage":"256 GB SSD"}'::jsonb
 WHERE attributes IS NULL AND name IN ('Laptop Pro 13');
UPDATE product.products SET attributes = '{"Connectivity":"Wireless"}'::jsonb
 WHERE attributes IS NULL AND name IN ('Headphones Wireless', 'Earbuds Pro', 'Speaker Bluetooth', 'Wireless Mouse');
UPDATE product.products SET attributes = '{"Connectivity":"Wired"}'::jsonb
 WHERE attributes IS NULL AND name IN ('Headphones Gaming', 'Mechanical Keyboard', 'Microphone USB', 'Webcam 1080p');
UPDATE product.products SET attributes = '{"Material":"Cotton"}'::jsonb
 WHERE attributes IS NULL AND name IN ('T-Shirt Cotton', 'Hoodie Fleece', 'Jeans Slim Fit');

-- Variants: T-Shirt Cotton (6), Hoodie Fleece (5), Laptop Pro 13 (5).
INSERT INTO product.product_variants (id, product_id, sku, attributes, price_amount, sale_price, active, sort_order)
SELECT v.id::uuid, p.id, p.sku || '-' || v.suffix, v.attrs::jsonb,
       CASE WHEN v.delta IS NULL THEN NULL ELSE p.price_amount + v.delta END, NULL, true, v.ord
FROM (VALUES
  ('T-Shirt Cotton', 'dddddddd-0000-0000-0000-000000000001', 'S-BLK', '{"size":"S","color":"Black"}', NULL::numeric, 1),
  ('T-Shirt Cotton', 'dddddddd-0000-0000-0000-000000000002', 'M-BLK', '{"size":"M","color":"Black"}', NULL, 2),
  ('T-Shirt Cotton', 'dddddddd-0000-0000-0000-000000000003', 'L-BLK', '{"size":"L","color":"Black"}', NULL, 3),
  ('T-Shirt Cotton', 'dddddddd-0000-0000-0000-000000000004', 'M-WHT', '{"size":"M","color":"White"}', NULL, 4),
  ('T-Shirt Cotton', 'dddddddd-0000-0000-0000-000000000005', 'L-WHT', '{"size":"L","color":"White"}', NULL, 5),
  ('T-Shirt Cotton', 'dddddddd-0000-0000-0000-000000000006', 'XL-WHT', '{"size":"XL","color":"White"}', 3.00, 6),
  ('Hoodie Fleece', 'dddddddd-0000-0000-0000-000000000011', 'S-GRY', '{"size":"S","color":"Grey"}', NULL, 1),
  ('Hoodie Fleece', 'dddddddd-0000-0000-0000-000000000012', 'M-GRY', '{"size":"M","color":"Grey"}', NULL, 2),
  ('Hoodie Fleece', 'dddddddd-0000-0000-0000-000000000013', 'L-GRY', '{"size":"L","color":"Grey"}', NULL, 3),
  ('Hoodie Fleece', 'dddddddd-0000-0000-0000-000000000014', 'L-NVY', '{"size":"L","color":"Navy"}', NULL, 4),
  ('Hoodie Fleece', 'dddddddd-0000-0000-0000-000000000015', 'XL-NVY', '{"size":"XL","color":"Navy"}', 5.00, 5),
  ('Laptop Pro 13', 'dddddddd-0000-0000-0000-000000000021', '256-SLV', '{"storage":"256 GB","color":"Silver"}', NULL, 1),
  ('Laptop Pro 13', 'dddddddd-0000-0000-0000-000000000022', '512-SLV', '{"storage":"512 GB","color":"Silver"}', 150.00, 2),
  ('Laptop Pro 13', 'dddddddd-0000-0000-0000-000000000023', '1TB-SLV', '{"storage":"1 TB","color":"Silver"}', 350.00, 3),
  ('Laptop Pro 13', 'dddddddd-0000-0000-0000-000000000024', '256-GRY', '{"storage":"256 GB","color":"Space Gray"}', NULL, 4),
  ('Laptop Pro 13', 'dddddddd-0000-0000-0000-000000000025', '512-GRY', '{"storage":"512 GB","color":"Space Gray"}', 150.00, 5)
) AS v(pname, id, suffix, attrs, delta, ord)
JOIN product.products p ON p.name = v.pname
ON CONFLICT DO NOTHING;
