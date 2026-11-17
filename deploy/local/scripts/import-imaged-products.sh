#!/usr/bin/env bash
# Copies the synthetic products that have a generated image (synthetic-db-ai/product-images/images/
# <product_id>.jpg) from the scratch DB (sdai-pg) into the local stack's product_db, so the storefront
# shows them with their images from static-image-server.
#
#   bash deploy/local/scripts/import-imaged-products.sh
#
# Re-runnable: rows that already exist (same id, sku or slug) are skipped. Categories are matched by
# name to existing ones, otherwise copied. Products become platform-listed with one platform offer
# (the local stack has no scratch sellers). Image rows store the key <product_id>.jpg, resolved by
# product-service against the parentUrl setting. Inventory-service has no stock rows for them.
set -euo pipefail
cd "$(dirname "$0")/../../.."
SRC=${SRC_CONTAINER:-sdai-pg}
DST=${DST_CONTAINER:-ecommerce-postgres}
IMAGES=synthetic-db-ai/product-images/images
PLATFORM=00000000-0000-0000-0000-00000000a11e

ids=$(ls "$IMAGES" | sed -n 's/^\([0-9a-f-]\{36\}\)\.jpg$/\1/p' | paste -sd, -)
[ -n "$ids" ] || { echo "no images in $IMAGES"; exit 1; }
echo "images: $(echo "$ids" | tr , '\n' | wc -l)"

src_sql() { docker exec -i "$SRC" psql -U postgres -d product_db -qAt -v ON_ERROR_STOP=1; }
dst_sql() { docker exec -i "$DST" psql -U postgres -d product_db -qAt -v ON_ERROR_STOP=1 "$@"; }

dst_sql <<'SQL'
DROP SCHEMA IF EXISTS import_stage CASCADE;
CREATE SCHEMA import_stage;
CREATE TABLE import_stage.products (LIKE product.products);
CREATE TABLE import_stage.categories (LIKE product.categories);
SQL

src_sql <<SQL | dst_sql -c "COPY import_stage.products FROM STDIN"
COPY (SELECT * FROM product.products WHERE id = ANY('{$ids}'::uuid[])) TO STDOUT;
SQL
src_sql <<SQL | dst_sql -c "COPY import_stage.categories FROM STDIN"
COPY (WITH RECURSIVE c AS (
        SELECT * FROM product.categories WHERE id IN (
          SELECT category_id FROM product.products WHERE id = ANY('{$ids}'::uuid[]))
        UNION
        SELECT p.* FROM product.categories p JOIN c ON p.id = c.parent_category_id)
      SELECT * FROM c) TO STDOUT;
SQL

# Generated columns (e.g. search_vector) are recomputed by the target, never inserted.
cols=$(dst_sql -c "SELECT string_agg(quote_ident(column_name), ',' ORDER BY ordinal_position)
  FROM information_schema.columns WHERE table_schema = 'product' AND table_name = 'products'
  AND is_generated = 'NEVER'")

dst_sql <<SQL
BEGIN;
-- Reuse an existing category with the same name; otherwise keep the scratch category.
CREATE TEMP TABLE cat_map AS
SELECT s.id AS src,
       COALESCE((SELECT a.id FROM product.categories a WHERE lower(a.name) = lower(s.name)
                 ORDER BY a.depth_level LIMIT 1), s.id) AS dst
FROM import_stage.categories s;

WITH added AS (
  INSERT INTO product.categories (id, name, parent_category_id, created_at, updated_at, description,
                                  depth_level, is_active, version)
  SELECT s.id, s.name, (SELECT dst FROM cat_map WHERE src = s.parent_category_id), s.created_at,
         s.updated_at, s.description, s.depth_level, s.is_active, s.version
  FROM import_stage.categories s JOIN cat_map m ON m.src = s.id AND m.dst = s.id
  ON CONFLICT DO NOTHING RETURNING 1)
SELECT 'categories added: ' || count(*) FROM added;

UPDATE import_stage.products s SET category_id = m.dst FROM cat_map m WHERE m.src = s.category_id;
UPDATE import_stage.products SET seller_id = '$PLATFORM', listed_by = 'PLATFORM',
                                 thumbnail_url = id::text || '.jpg';

CREATE TEMP TABLE inserted AS SELECT id FROM product.products WHERE false;
WITH ins AS (
  INSERT INTO product.products ($cols) SELECT $cols FROM import_stage.products
  ON CONFLICT DO NOTHING RETURNING id)
INSERT INTO inserted SELECT id FROM ins;

INSERT INTO product.product_images (product_id, url, display_order)
SELECT id, id::text || '.jpg', 0 FROM inserted;

INSERT INTO product.offers (product_id, seller_id, price, currency, condition, dispatch_days, active)
SELECT p.id, '$PLATFORM', p.price_amount, p.price_currency, 'NEW', 1, true
FROM product.products p JOIN inserted i ON i.id = p.id;

SELECT 'products imported: ' || (SELECT count(*) FROM inserted)
    || ' (skipped as duplicates: ' || ((SELECT count(*) FROM import_stage.products) - (SELECT count(*) FROM inserted)) || ')';
COMMIT;
DROP SCHEMA import_stage CASCADE;
SQL
echo "done"
