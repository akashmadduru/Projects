#!/usr/bin/env bash
# =============================================================================
# backfill-product-ratings.sh  -  DEV ONLY, idempotent.
#
# WHY: rating-service's seed ratings (Flyway V2) were inserted without publishing
# rating.events, so product-service's denormalized columns (average_rating,
# rating_count, review_count, rating_distribution) never saw them. Live ratings
# keep the columns current through RatingEventListener; this script repairs
# history (and any event missed while rating-service was down).
#
# HOW (simplest reliable approach): rating_db and product_db are separate
# databases, so the script runs the same aggregation rating-service's getSummary()
# uses (AVG rounded HALF_UP to 2dp, COUNT, COUNT of non-null review_text,
# per-star distribution with all five keys) in rating_db via `psql` inside the
# ecommerce-postgres container, streams the result as tab-separated text into a temp table in
# product_db, and UPDATEs product.products in one transaction. Re-running is
# harmless: it overwrites with the same authoritative values.
#
# By default only products that HAVE ratings in rating-service are touched.
# Pass --reset-unrated to also zero products with no ratings there (average_rating
# NULL, counts 0, distribution NULL) so the product table fully mirrors
# rating-service; this discards the catalog's own demo rating numbers.
#
# NOTE: this does not bump product.version / updated_at and publishes no events.
#
# Usage: infra/scripts/backfill-product-ratings.sh [--reset-unrated]
# Env:   PG_CONTAINER (default ecommerce-postgres), PG_SUPERUSER (default postgres)
# =============================================================================
set -euo pipefail

PG_CONTAINER="${PG_CONTAINER:-ecommerce-postgres}"
PG_SUPERUSER="${PG_SUPERUSER:-postgres}"
RESET_UNRATED=false
[[ "${1:-}" == "--reset-unrated" ]] && RESET_UNRATED=true

psql_in() { docker exec -i "$PG_CONTAINER" psql -v ON_ERROR_STOP=1 -U "$PG_SUPERUSER" "$@"; }

ROWS="$(psql_in -d rating_db -At -F $'\t' -c "
  SELECT product_id,
         ROUND(AVG(rating_value)::numeric, 2),
         COUNT(*),
         COUNT(review_text),
         jsonb_build_object(
           '1', COUNT(*) FILTER (WHERE rating_value = 1),
           '2', COUNT(*) FILTER (WHERE rating_value = 2),
           '3', COUNT(*) FILTER (WHERE rating_value = 3),
           '4', COUNT(*) FILTER (WHERE rating_value = 4),
           '5', COUNT(*) FILTER (WHERE rating_value = 5))::text
  FROM rating.ratings GROUP BY product_id")"

if [[ -z "$ROWS" ]]; then
  echo "No ratings in rating_db; nothing to backfill."
  exit 0
fi

{
  echo "BEGIN;"
  echo "CREATE TEMP TABLE rating_agg (product_id uuid, avg_rating numeric(3,2), cnt int, reviews int, dist jsonb) ON COMMIT DROP;"
  echo "COPY rating_agg FROM STDIN;"
  printf '%s\n' "$ROWS"
  printf '%s\n' '\.'
  echo "UPDATE product.products p SET average_rating = a.avg_rating, rating_count = a.cnt, review_count = a.reviews, rating_distribution = a.dist FROM rating_agg a WHERE p.id = a.product_id;"
  if $RESET_UNRATED; then
    echo "UPDATE product.products SET average_rating = NULL, rating_count = 0, review_count = 0, rating_distribution = NULL WHERE id NOT IN (SELECT product_id FROM rating_agg);"
  fi
  echo "COMMIT;"
} | psql_in -d product_db
