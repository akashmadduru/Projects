-- Phase 7 integration: seeded variant rows had product_id NULL; fill it so by-product breakdowns work.
UPDATE inventory.variant_stock SET product_id = 'cccccccc-cccc-cccc-cccc-000000000018'
WHERE product_id IS NULL AND variant_id IN (
  'dddddddd-0000-0000-0000-000000000001','dddddddd-0000-0000-0000-000000000002','dddddddd-0000-0000-0000-000000000003',
  'dddddddd-0000-0000-0000-000000000004','dddddddd-0000-0000-0000-000000000005','dddddddd-0000-0000-0000-000000000006');
UPDATE inventory.variant_stock SET product_id = 'cccccccc-cccc-cccc-cccc-000000000007'
WHERE product_id IS NULL AND variant_id IN (
  'dddddddd-0000-0000-0000-000000000011','dddddddd-0000-0000-0000-000000000012','dddddddd-0000-0000-0000-000000000013',
  'dddddddd-0000-0000-0000-000000000014','dddddddd-0000-0000-0000-000000000015');
UPDATE inventory.variant_stock SET product_id = 'aaaaaaaa-aaaa-aaaa-aaaa-000000000014'
WHERE product_id IS NULL AND variant_id IN (
  'dddddddd-0000-0000-0000-000000000021','dddddddd-0000-0000-0000-000000000022','dddddddd-0000-0000-0000-000000000023',
  'dddddddd-0000-0000-0000-000000000024','dddddddd-0000-0000-0000-000000000025');
