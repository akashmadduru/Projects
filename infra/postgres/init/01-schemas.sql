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

-- --- user-service ------------------------------------------------------------
CREATE ROLE user_service WITH LOGIN PASSWORD 'user_service_local_pwd';
CREATE DATABASE user_db WITH OWNER user_service ENCODING 'UTF8';
COMMENT ON DATABASE user_db IS 'user-service: customer profiles, addresses';

-- --- cart-service ------------------------------------------------------------
CREATE ROLE cart_service WITH LOGIN PASSWORD 'cart_service_local_pwd';
CREATE DATABASE cart_db WITH OWNER cart_service ENCODING 'UTF8';
COMMENT ON DATABASE cart_db IS 'cart-service: carts, wishlists';

-- --- order-service -----------------------------------------------------------
CREATE ROLE order_service WITH LOGIN PASSWORD 'order_service_local_pwd';
CREATE DATABASE order_db WITH OWNER order_service ENCODING 'UTF8';
COMMENT ON DATABASE order_db IS 'order-service: orders (address and price snapshots)';

-- --- rating-service ----------------------------------------------------------
CREATE ROLE rating_service WITH LOGIN PASSWORD 'rating_service_local_pwd';
CREATE DATABASE rating_db WITH OWNER rating_service ENCODING 'UTF8';
COMMENT ON DATABASE rating_db IS 'rating-service: product ratings, reviews, helpfulness votes';

-- --- payment-service ---------------------------------------------------------
CREATE ROLE payment_service WITH LOGIN PASSWORD 'payment_service_local_pwd';
CREATE DATABASE payment_db WITH OWNER payment_service ENCODING 'UTF8';
COMMENT ON DATABASE payment_db IS 'payment-service: payments, refunds, processed Stripe events';

-- --- promotion-service -------------------------------------------------------
CREATE ROLE promotion_service WITH LOGIN PASSWORD 'promotion_service_local_pwd';
CREATE DATABASE promotion_db WITH OWNER promotion_service ENCODING 'UTF8';
COMMENT ON DATABASE promotion_db IS 'promotion-service: coupons and redemptions';

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

\connect user_db
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT ALL ON SCHEMA public TO user_service;
ALTER DATABASE user_db SET search_path TO public;

\connect cart_db
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT ALL ON SCHEMA public TO cart_service;
ALTER DATABASE cart_db SET search_path TO public;

\connect order_db
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT ALL ON SCHEMA public TO order_service;
ALTER DATABASE order_db SET search_path TO public;

\connect rating_db
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT ALL ON SCHEMA public TO rating_service;
ALTER DATABASE rating_db SET search_path TO public;

\connect payment_db
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT ALL ON SCHEMA public TO payment_service;
ALTER DATABASE payment_db SET search_path TO public;

\connect promotion_db
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT ALL ON SCHEMA public TO promotion_service;
ALTER DATABASE promotion_db SET search_path TO public;
