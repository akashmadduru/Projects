--
-- PostgreSQL database dump
--

\restrict 906NF1eCul5W1AbQASbxslMftFrm13kv4ikBMteMEf7YYcCJFXS3NFDzA20MYC4

-- Dumped from database version 17.11 (Debian 17.11-1.pgdg13+2)
-- Dumped by pg_dump version 17.11 (Debian 17.11-1.pgdg13+2)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

DROP DATABASE IF EXISTS inventory_db;
--
-- Name: inventory_db; Type: DATABASE; Schema: -; Owner: inventory_service
--

CREATE DATABASE inventory_db WITH TEMPLATE = template0 ENCODING = 'UTF8' LOCALE_PROVIDER = libc LOCALE = 'C';


ALTER DATABASE inventory_db OWNER TO inventory_service;

\unrestrict 906NF1eCul5W1AbQASbxslMftFrm13kv4ikBMteMEf7YYcCJFXS3NFDzA20MYC4
\connect inventory_db
\restrict 906NF1eCul5W1AbQASbxslMftFrm13kv4ikBMteMEf7YYcCJFXS3NFDzA20MYC4

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: DATABASE inventory_db; Type: COMMENT; Schema: -; Owner: inventory_service
--

COMMENT ON DATABASE inventory_db IS 'inventory-service: stock levels, event-linked to product.products (no FK - see LLD S4a)';


--
-- Name: inventory_db; Type: DATABASE PROPERTIES; Schema: -; Owner: inventory_service
--

ALTER DATABASE inventory_db SET search_path TO 'public';


\unrestrict 906NF1eCul5W1AbQASbxslMftFrm13kv4ikBMteMEf7YYcCJFXS3NFDzA20MYC4
\connect inventory_db
\restrict 906NF1eCul5W1AbQASbxslMftFrm13kv4ikBMteMEf7YYcCJFXS3NFDzA20MYC4

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: inventory; Type: SCHEMA; Schema: -; Owner: inventory_service
--

CREATE SCHEMA inventory;


ALTER SCHEMA inventory OWNER TO inventory_service;

--
-- Name: pgcrypto; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA inventory;


--
-- Name: EXTENSION pgcrypto; Type: COMMENT; Schema: -; Owner: 
--

COMMENT ON EXTENSION pgcrypto IS 'cryptographic functions';


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: flyway_schema_history; Type: TABLE; Schema: inventory; Owner: inventory_service
--

CREATE TABLE inventory.flyway_schema_history (
    installed_rank integer NOT NULL,
    version character varying(50),
    description character varying(200) NOT NULL,
    type character varying(20) NOT NULL,
    script character varying(1000) NOT NULL,
    checksum integer,
    installed_by character varying(100) NOT NULL,
    installed_on timestamp without time zone DEFAULT now() NOT NULL,
    execution_time integer NOT NULL,
    success boolean NOT NULL
);


ALTER TABLE inventory.flyway_schema_history OWNER TO inventory_service;

--
-- Name: stock_levels; Type: TABLE; Schema: inventory; Owner: inventory_service
--

CREATE TABLE inventory.stock_levels (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    product_id uuid NOT NULL,
    quantity integer DEFAULT 0 NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    warehouse_code character varying(32),
    product_name character varying(200),
    product_sku character varying(64),
    low_stock_threshold integer DEFAULT 10 NOT NULL,
    reserved_quantity integer DEFAULT 0 NOT NULL,
    status character varying(32),
    last_updated_at timestamp with time zone,
    CONSTRAINT stock_levels_quantity_check CHECK ((quantity >= 0)),
    CONSTRAINT stock_levels_reserved_check CHECK (((reserved_quantity >= 0) AND (reserved_quantity <= quantity))),
    CONSTRAINT stock_levels_status_check CHECK (((status IS NULL) OR ((status)::text = ANY ((ARRAY['IN_STOCK'::character varying, 'LOW_STOCK'::character varying, 'OUT_OF_STOCK'::character varying])::text[]))))
);


ALTER TABLE inventory.stock_levels OWNER TO inventory_service;

--
-- Name: TABLE stock_levels; Type: COMMENT; Schema: inventory; Owner: inventory_service
--

COMMENT ON TABLE inventory.stock_levels IS 'Stock levels, one row per product. Event-linked to product.products, no FK - see LLD S4a.';


--
-- Name: COLUMN stock_levels.product_id; Type: COMMENT; Schema: inventory; Owner: inventory_service
--

COMMENT ON COLUMN inventory.stock_levels.product_id IS 'The product this row tracks stock for. Unique; no FK across databases.';


--
-- Name: COLUMN stock_levels.warehouse_code; Type: COMMENT; Schema: inventory; Owner: inventory_service
--

COMMENT ON COLUMN inventory.stock_levels.warehouse_code IS 'Single-warehouse, forward-compatible label - not a multi-warehouse model.';


--
-- Name: COLUMN stock_levels.product_name; Type: COMMENT; Schema: inventory; Owner: inventory_service
--

COMMENT ON COLUMN inventory.stock_levels.product_name IS 'Denormalized from product-service via product.events - populated by a later pass, NULL until then.';


--
-- Name: COLUMN stock_levels.product_sku; Type: COMMENT; Schema: inventory; Owner: inventory_service
--

COMMENT ON COLUMN inventory.stock_levels.product_sku IS 'Denormalized from product-service via product.events - populated by a later pass, NULL until then.';


--
-- Name: COLUMN stock_levels.low_stock_threshold; Type: COMMENT; Schema: inventory; Owner: inventory_service
--

COMMENT ON COLUMN inventory.stock_levels.low_stock_threshold IS 'Quantity at/below which this row is considered low stock.';


--
-- Name: COLUMN stock_levels.reserved_quantity; Type: COMMENT; Schema: inventory; Owner: inventory_service
--

COMMENT ON COLUMN inventory.stock_levels.reserved_quantity IS 'Items allocated but not yet shipped. Used to calculate available quantity.';


--
-- Name: COLUMN stock_levels.status; Type: COMMENT; Schema: inventory; Owner: inventory_service
--

COMMENT ON COLUMN inventory.stock_levels.status IS 'Inventory status: IN_STOCK, LOW_STOCK, OUT_OF_STOCK.';


--
-- Name: COLUMN stock_levels.last_updated_at; Type: COMMENT; Schema: inventory; Owner: inventory_service
--

COMMENT ON COLUMN inventory.stock_levels.last_updated_at IS 'Timestamp of the last inventory update.';


--
-- Data for Name: flyway_schema_history; Type: TABLE DATA; Schema: inventory; Owner: inventory_service
--

COPY inventory.flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) FROM stdin;
0	\N	<< Flyway Schema Creation >>	SCHEMA	"inventory"	\N	inventory_service	2026-11-08 12:33:29.46782	0	t
1	1	inventory baseline	SQL	V1__inventory_baseline.sql	-1289232457	inventory_service	2026-11-08 12:33:29.497631	15	t
2	2	inventory enrichment	SQL	V2__inventory_enrichment.sql	-1408659641	inventory_service	2026-11-08 12:33:29.544759	4	t
3	3	inventory amazon enrichment	SQL	V3__inventory_amazon_enrichment.sql	-2062429352	inventory_service	2026-11-11 17:42:47.170052	43	t
\.


--
-- Data for Name: stock_levels; Type: TABLE DATA; Schema: inventory; Owner: inventory_service
--

COPY inventory.stock_levels (id, product_id, quantity, version, created_at, updated_at, warehouse_code, product_name, product_sku, low_stock_threshold, reserved_quantity, status, last_updated_at) FROM stdin;
\.


--
-- Name: flyway_schema_history flyway_schema_history_pk; Type: CONSTRAINT; Schema: inventory; Owner: inventory_service
--

ALTER TABLE ONLY inventory.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);


--
-- Name: stock_levels stock_levels_pkey; Type: CONSTRAINT; Schema: inventory; Owner: inventory_service
--

ALTER TABLE ONLY inventory.stock_levels
    ADD CONSTRAINT stock_levels_pkey PRIMARY KEY (id);


--
-- Name: stock_levels stock_levels_product_id_key; Type: CONSTRAINT; Schema: inventory; Owner: inventory_service
--

ALTER TABLE ONLY inventory.stock_levels
    ADD CONSTRAINT stock_levels_product_id_key UNIQUE (product_id);


--
-- Name: flyway_schema_history_s_idx; Type: INDEX; Schema: inventory; Owner: inventory_service
--

CREATE INDEX flyway_schema_history_s_idx ON inventory.flyway_schema_history USING btree (success);


--
-- Name: stock_levels_last_updated_at_idx; Type: INDEX; Schema: inventory; Owner: inventory_service
--

CREATE INDEX stock_levels_last_updated_at_idx ON inventory.stock_levels USING btree (last_updated_at);


--
-- Name: stock_levels_status_idx; Type: INDEX; Schema: inventory; Owner: inventory_service
--

CREATE INDEX stock_levels_status_idx ON inventory.stock_levels USING btree (status);


--
-- Name: stock_levels_warehouse_code_idx; Type: INDEX; Schema: inventory; Owner: inventory_service
--

CREATE INDEX stock_levels_warehouse_code_idx ON inventory.stock_levels USING btree (warehouse_code);


--
-- Name: SCHEMA public; Type: ACL; Schema: -; Owner: pg_database_owner
--

REVOKE USAGE ON SCHEMA public FROM PUBLIC;
GRANT ALL ON SCHEMA public TO inventory_service;


--
-- PostgreSQL database dump complete
--

\unrestrict 906NF1eCul5W1AbQASbxslMftFrm13kv4ikBMteMEf7YYcCJFXS3NFDzA20MYC4

