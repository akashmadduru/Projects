-- TD-ADM-08: admin customer search filters with lower(email) LIKE '%q%' OR lower(display_name)
-- LIKE '%q%' (AdminUserService#list). Without these indexes every search scans auth.users.
-- Checked with EXPLAIN on postgres:17 (50k rows): both indexes are used through a BitmapOr.
-- lower() on the citext email column resolves to lower(email::text), which is the indexed
-- expression. pg_trgm is a trusted extension (creatable by the database owner), kept in the
-- auth schema like product-service keeps it in product.
CREATE EXTENSION IF NOT EXISTS pg_trgm SCHEMA auth;

CREATE INDEX IF NOT EXISTS ix_users_email_trgm
    ON auth.users USING gin (lower(email::text) auth.gin_trgm_ops);

CREATE INDEX IF NOT EXISTS ix_users_display_name_trgm
    ON auth.users USING gin (lower(display_name) auth.gin_trgm_ops);
