#!/usr/bin/env bash
# =============================================================================
# db-snapshot.sh - DEV ONLY. Dump the local Postgres databases somewhere safe.
#
# WHY THIS EXISTS: on 2026-11-12 raw pg_dump output (password hashes, SCRAM
# verifiers, refresh-token hashes, a real email and IP) was committed to this
# public repository under db-snapshots/. This script is the supported way to take
# a snapshot so that cannot happen again:
#   - output goes OUTSIDE the repository by default and the script refuses any
#     directory inside it;
#   - databases that hold credentials or personal data are dumped schema-only
#     unless you pass --with-personal-data;
#   - role passwords (pg_dumpall --globals-only) are never dumped.
# Incident runbook: docs/guides/runbooks/leaked-database-dump.md
#
# USAGE
#   ./infra/scripts/db-snapshot.sh                       # -> ~/ecommerce-db-snapshots/<timestamp>/
#   SNAPSHOT_DIR=/some/dir ./infra/scripts/db-snapshot.sh
#   ./infra/scripts/db-snapshot.sh --with-personal-data  # full data for every database (still outside the repo)
#
# RESTORE one database (the dump is plain SQL):
#   docker exec -i ecommerce-postgres psql -U postgres -d product_db < <dir>/product_db.sql
# =============================================================================
set -euo pipefail

CONTAINER="${POSTGRES_CONTAINER:-ecommerce-postgres}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
BASE_DIR="${SNAPSHOT_DIR:-$HOME/ecommerce-db-snapshots}"
WITH_PERSONAL=false
[[ "${1:-}" == "--with-personal-data" ]] && WITH_PERSONAL=true

# Catalog and stock data: no credentials, no personal data. Dumped with data,
# except product.product_views (per-session view tracking).
DATA_DBS=(product_db inventory_db)
# Credentials or personal data (users, addresses, orders, payments, messages,
# coins and coupon redemptions, seller KYC, tickets). Schema-only unless --with-personal-data.
PERSONAL_DBS=(auth_db user_db cart_db order_db payment_db promotion_db notification_db rating_db seller_db support_db)

CREATED=false
[[ -d "$BASE_DIR" ]] || { mkdir -p "$BASE_DIR"; CREATED=true; }
BASE_DIR="$(cd "$BASE_DIR" && pwd -P)"
case "$BASE_DIR/" in
  "$REPO_ROOT"/*)
    $CREATED && rmdir "$BASE_DIR" 2>/dev/null || true
    echo "Refusing to write a database dump inside the repository ($BASE_DIR)." >&2
    echo "Set SNAPSHOT_DIR to a directory outside $REPO_ROOT." >&2
    exit 1 ;;
esac

OUT="$BASE_DIR/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"
chmod 700 "$OUT"

dump() { # dump <db> [extra pg_dump args...]
  local db="$1"; shift
  if docker exec "$CONTAINER" psql -U postgres -Atc "select 1 from pg_database where datname='$db'" | grep -q 1; then
    docker exec "$CONTAINER" pg_dump -U postgres --no-owner --no-privileges "$@" "$db" > "$OUT/$db.sql"
    echo "  $db $([[ " $* " == *" --schema-only "* ]] && echo '(schema only)')"
  else
    echo "  $db (missing, skipped)"
  fi
}

echo "Writing snapshots to $OUT"
for db in "${DATA_DBS[@]}"; do
  if $WITH_PERSONAL; then dump "$db"; else dump "$db" --exclude-table-data=product.product_views; fi
done
for db in "${PERSONAL_DBS[@]}"; do
  if $WITH_PERSONAL; then dump "$db"; else dump "$db" --schema-only; fi
done
chmod 600 "$OUT"/*.sql
echo "Done. Keep these files out of the repository and do not share them."
