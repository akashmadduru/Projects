-- =============================================================================
-- One-off bootstrap of the customer-facing service databases for an EXISTING
-- postgres volume (the init scripts only run on an empty volume).
--
--   docker exec -i ecommerce-postgres psql -U postgres -v ON_ERROR_STOP=0 < infra/scripts/create-customer-dbs.sql
--
-- Re-runnable: roles/databases that already exist are skipped. Passwords are the
-- local-dev throwaway values that match infra/.env. Mirrors the user_db/cart_db/
-- order_db blocks in infra/postgres/init/01-schemas.sql.
-- =============================================================================

SELECT 'CREATE ROLE user_service WITH LOGIN PASSWORD ''user_service_local_pwd'''
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'user_service')\gexec
SELECT 'CREATE ROLE cart_service WITH LOGIN PASSWORD ''cart_service_local_pwd'''
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cart_service')\gexec
SELECT 'CREATE ROLE order_service WITH LOGIN PASSWORD ''order_service_local_pwd'''
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'order_service')\gexec
SELECT 'CREATE ROLE rating_service WITH LOGIN PASSWORD ''rating_service_local_pwd'''
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rating_service')\gexec

SELECT 'CREATE DATABASE user_db WITH OWNER user_service ENCODING ''UTF8'' TEMPLATE template0'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'user_db')\gexec
SELECT 'CREATE DATABASE cart_db WITH OWNER cart_service ENCODING ''UTF8'' TEMPLATE template0'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'cart_db')\gexec
SELECT 'CREATE DATABASE order_db WITH OWNER order_service ENCODING ''UTF8'' TEMPLATE template0'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'order_db')\gexec
SELECT 'CREATE DATABASE rating_db WITH OWNER rating_service ENCODING ''UTF8'' TEMPLATE template0'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'rating_db')\gexec

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

-- --- Phase 6: payment-service + promotion-service ---------------------------
\connect postgres
SELECT 'CREATE ROLE payment_service WITH LOGIN PASSWORD ''payment_service_local_pwd'''
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'payment_service')\gexec
SELECT 'CREATE ROLE promotion_service WITH LOGIN PASSWORD ''promotion_service_local_pwd'''
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'promotion_service')\gexec
SELECT 'CREATE DATABASE payment_db WITH OWNER payment_service ENCODING ''UTF8'' TEMPLATE template0'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'payment_db')\gexec
SELECT 'CREATE DATABASE promotion_db WITH OWNER promotion_service ENCODING ''UTF8'' TEMPLATE template0'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'promotion_db')\gexec

\connect payment_db
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT ALL ON SCHEMA public TO payment_service;
ALTER DATABASE payment_db SET search_path TO public;

\connect promotion_db
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT ALL ON SCHEMA public TO promotion_service;
ALTER DATABASE promotion_db SET search_path TO public;

-- --- Phase 8: notification-service -------------------------------------------
\connect postgres
SELECT 'CREATE ROLE notification_service WITH LOGIN PASSWORD ''notification_service_local_pwd'''
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'notification_service')\gexec
SELECT 'CREATE DATABASE notification_db WITH OWNER notification_service ENCODING ''UTF8'' TEMPLATE template0'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'notification_db')\gexec

\connect notification_db
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT ALL ON SCHEMA public TO notification_service;
ALTER DATABASE notification_db SET search_path TO public;

-- --- Phase 9: seller-service --------------------------------------------------
\connect postgres
SELECT 'CREATE ROLE seller_service WITH LOGIN PASSWORD ''seller_service_local_pwd'''
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'seller_service')\gexec
SELECT 'CREATE DATABASE seller_db WITH OWNER seller_service ENCODING ''UTF8'' TEMPLATE template0'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'seller_db')\gexec

\connect seller_db
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT ALL ON SCHEMA public TO seller_service;
ALTER DATABASE seller_db SET search_path TO public;

-- --- Phase 10: support-service ------------------------------------------------
\connect postgres
SELECT 'CREATE ROLE support_service WITH LOGIN PASSWORD ''support_service_local_pwd'''
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'support_service')\gexec
SELECT 'CREATE DATABASE support_db WITH OWNER support_service ENCODING ''UTF8'' TEMPLATE template0'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'support_db')\gexec

\connect support_db
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT ALL ON SCHEMA public TO support_service;
ALTER DATABASE support_db SET search_path TO public;
