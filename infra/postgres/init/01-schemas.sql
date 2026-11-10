-- =============================================================================
-- Local development database bootstrap.
--
-- Runs ONCE, on first container start, via the postgres image's
-- /docker-entrypoint-initdb.d hook. If you change this file you must recreate
-- the volume for it to take effect:
--     docker compose down -v && docker compose up -d postgres
--
-- SCOPE: local development only. Real environments provision databases and
-- roles through infrastructure-as-code, and the passwords below are throwaway
-- values that must never appear in a deployed environment.
--
-- DESIGN: each service owns its own database and its own least-privileged role.
-- There is no shared schema and no cross-database querying - that separation is
-- what keeps the services independently deployable and stops a convenient join
-- from quietly becoming a coupling.
-- =============================================================================

-- --- auth-service ------------------------------------------------------------
CREATE ROLE auth_service WITH LOGIN PASSWORD 'auth_service_local_pwd';
CREATE DATABASE auth_db WITH OWNER auth_service ENCODING 'UTF8';
COMMENT ON DATABASE auth_db IS 'auth-service: users, roles, refresh tokens';

-- --- product-service ---------------------------------------------------------
CREATE ROLE product_service WITH LOGIN PASSWORD 'product_service_local_pwd';
CREATE DATABASE product_db WITH OWNER product_service ENCODING 'UTF8';
-- Inventory moved into its own database (inventory_db, below) in Phase 2 - this
-- comment previously claimed inventory too, which stopped being true the moment
-- inventory-service got its own owned database.
COMMENT ON DATABASE product_db IS 'product-service: catalog, categories';

-- --- inventory-service ---------------------------------------------------------
CREATE ROLE inventory_service WITH LOGIN PASSWORD 'inventory_service_local_pwd';
CREATE DATABASE inventory_db WITH OWNER inventory_service ENCODING 'UTF8';
COMMENT ON DATABASE inventory_db IS 'inventory-service: stock levels, event-linked to product.products (no FK - see LLD S4a)';

-- --- lock down the public schema in each database ----------------------------
-- Postgres 15+ already revokes CREATE on public from PUBLIC, but the grants are
-- restated explicitly so the intent survives a version change.

\connect auth_db
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT ALL ON SCHEMA public TO auth_service;
-- Flyway needs to create its history table and the service's own objects.
ALTER DATABASE auth_db SET search_path TO public;

\connect product_db
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT ALL ON SCHEMA public TO product_service;
ALTER DATABASE product_db SET search_path TO public;

\connect inventory_db
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT ALL ON SCHEMA public TO inventory_service;
ALTER DATABASE inventory_db SET search_path TO public;

-- --- admin user for local development -----------------------------------------
-- This user is ONLY for local development. Production environments must
-- provision users through proper identity management systems.

\connect auth_db

-- Create the admin user in the auth schema
INSERT INTO auth.users (id, email, email_verified, display_name, status)
VALUES ('f47ac10b-58cc-4372-a567-0e02b2c3d479'::uuid, 'admin@acme-ecommerce.internal', true, 'Administrator', 'ACTIVE');

-- Grant ROLE_ADMIN to the admin user
INSERT INTO auth.user_roles (user_id, role_id, granted_at)
VALUES ('f47ac10b-58cc-4372-a567-0e02b2c3d479'::uuid, 1, now());
