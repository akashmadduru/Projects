#!/usr/bin/env bash
# =============================================================================
# db-backup.sh - DEV ONLY. Full backup of every database in the sdai-pg container
# (the synthetic-data Postgres that runs as the `postgres` service of the
# ecommerce-platform compose project).
#
# Each database is dumped WITH ALL DATA in pg_dump custom format (compressed),
# one <db>.dump per database, plus MANIFEST.txt (sizes + SHA-256) and a
# `pg_restore --list` check of every archive. Role passwords are never dumped
# (no pg_dumpall --globals): restore into a container started with the same
# POSTGRES_PASSWORD.
#
# Safety rules (see docs/guides/runbooks/leaked-database-dump.md): output goes
# OUTSIDE the repository and this script refuses any directory inside it.
#
# USAGE
#   ./db-backup.sh                           # -> ~/sdai-db-backups/<timestamp>/
#   BACKUP_DIR=/some/dir ./db-backup.sh
#   SDAI_CONTAINER=other-container ./db-backup.sh
#
# NOTE: each database is dumped in its own consistent snapshot, but the 12
# databases are not snapshotted at the same instant. Stop the microservices
# first if they are writing and you need a cross-database consistent backup.
#
# RESTORE one database (create it first if the cluster is empty):
#   docker exec sdai-pg createdb -U postgres product_db
#   docker exec -i sdai-pg pg_restore -U postgres -d product_db --no-owner --clean --if-exists < <dir>/product_db.dump
# RESTORE everything:
#   for f in <dir>/*.dump; do db=$(basename "$f" .dump); docker exec sdai-pg createdb -U postgres "$db" 2>/dev/null || true
#     docker exec -i sdai-pg pg_restore -U postgres -d "$db" --no-owner --clean --if-exists < "$f"; done
# =============================================================================
set -euo pipefail

CONTAINER="${SDAI_CONTAINER:-sdai-pg}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
BASE_DIR="${BACKUP_DIR:-$HOME/sdai-db-backups}"

docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null | grep -q true \
  || { echo "Container '$CONTAINER' is not running. Start it: docker compose up -d postgres" >&2; exit 1; }

CREATED=false
[[ -d "$BASE_DIR" ]] || { mkdir -p "$BASE_DIR"; CREATED=true; }
BASE_DIR="$(cd "$BASE_DIR" && pwd -P)"
case "$BASE_DIR/" in
  "$REPO_ROOT"/*)
    $CREATED && rmdir "$BASE_DIR" 2>/dev/null || true
    echo "Refusing to write a database dump inside the repository ($BASE_DIR)." >&2
    echo "Set BACKUP_DIR to a directory outside $REPO_ROOT." >&2
    exit 1 ;;
esac

OUT="$BASE_DIR/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"
chmod 700 "$OUT"
# Removed only after every database is dumped and verified; a leftover marker means an unusable backup.
echo "Backup did not finish. Do not restore from this directory." > "$OUT/INCOMPLETE"

CURRENT=""
trap '[[ -n "$CURRENT" ]] && rm -f "$OUT/$CURRENT.dump" && echo "Backup failed while dumping $CURRENT; partial file removed." >&2' ERR

mapfile -t DBS < <(docker exec "$CONTAINER" psql -U postgres -Atc \
  "select datname from pg_database where not datistemplate order by datname" 2>/dev/null)
[[ ${#DBS[@]} -gt 0 ]] || { echo "No databases found in $CONTAINER." >&2; exit 1; }

VERSION="$(docker exec "$CONTAINER" psql -U postgres -Atc 'show server_version' 2>/dev/null)"
echo "Backing up ${#DBS[@]} databases from $CONTAINER (PostgreSQL $VERSION) to $OUT"

{
  echo "sdai-pg backup"
  echo "created:   $(date -Iseconds)"
  echo "container: $CONTAINER"
  echo "server:    PostgreSQL $VERSION"
  echo "format:    pg_dump custom (-Fc), data included, role passwords not included"
  echo
} > "$OUT/MANIFEST.txt"

for db in "${DBS[@]}"; do
  CURRENT="$db"
  docker exec "$CONTAINER" pg_dump -U postgres -Fc --no-owner "$db" > "$OUT/$db.dump" 2>/dev/null
  docker exec -i "$CONTAINER" pg_restore --list < "$OUT/$db.dump" > /dev/null
  size="$(du -h "$OUT/$db.dump" | cut -f1)"
  sum="$(sha256sum "$OUT/$db.dump" | cut -d' ' -f1)"
  printf '%-18s %8s  sha256:%s\n' "$db.dump" "$size" "$sum" >> "$OUT/MANIFEST.txt"
  echo "  $db  $size  (archive verified)"
done
CURRENT=""

[[ "$(ls "$OUT"/*.dump | wc -l)" -eq "${#DBS[@]}" ]] || { echo "Dump count does not match the database count." >&2; exit 1; }
echo "databases: ${#DBS[@]}  (complete)" >> "$OUT/MANIFEST.txt"
rm -f "$OUT/INCOMPLETE"
chmod 600 "$OUT"/*
echo "Done: $OUT  ${#DBS[@]} databases, $(du -sh "$OUT" | cut -f1) total. Keep it out of the repository and do not share it."
