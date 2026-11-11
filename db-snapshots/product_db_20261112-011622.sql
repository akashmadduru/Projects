--
-- PostgreSQL database dump
--

\restrict rDW5ec791rfwUA9ZmRyvBJ2lqTsLO0yCPhcdbXnhv35Wpkh8P6A0180AgajaumY

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

DROP DATABASE IF EXISTS product_db;
--
-- Name: product_db; Type: DATABASE; Schema: -; Owner: product_service
--

CREATE DATABASE product_db WITH TEMPLATE = template0 ENCODING = 'UTF8' LOCALE_PROVIDER = libc LOCALE = 'C';


ALTER DATABASE product_db OWNER TO product_service;

\unrestrict rDW5ec791rfwUA9ZmRyvBJ2lqTsLO0yCPhcdbXnhv35Wpkh8P6A0180AgajaumY
\connect product_db
\restrict rDW5ec791rfwUA9ZmRyvBJ2lqTsLO0yCPhcdbXnhv35Wpkh8P6A0180AgajaumY

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
-- Name: DATABASE product_db; Type: COMMENT; Schema: -; Owner: product_service
--

COMMENT ON DATABASE product_db IS 'product-service: catalog, categories';


--
-- Name: product_db; Type: DATABASE PROPERTIES; Schema: -; Owner: product_service
--

ALTER DATABASE product_db SET search_path TO 'public';


\unrestrict rDW5ec791rfwUA9ZmRyvBJ2lqTsLO0yCPhcdbXnhv35Wpkh8P6A0180AgajaumY
\connect product_db
\restrict rDW5ec791rfwUA9ZmRyvBJ2lqTsLO0yCPhcdbXnhv35Wpkh8P6A0180AgajaumY

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
-- Name: product; Type: SCHEMA; Schema: -; Owner: product_service
--

CREATE SCHEMA product;


ALTER SCHEMA product OWNER TO product_service;

--
-- Name: pgcrypto; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA product;


--
-- Name: EXTENSION pgcrypto; Type: COMMENT; Schema: -; Owner: 
--

COMMENT ON EXTENSION pgcrypto IS 'cryptographic functions';


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: categories; Type: TABLE; Schema: product; Owner: product_service
--

CREATE TABLE product.categories (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    name character varying(120) NOT NULL,
    parent_category_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    description text,
    depth_level integer DEFAULT 0 NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    version bigint DEFAULT 0 NOT NULL
);


ALTER TABLE product.categories OWNER TO product_service;

--
-- Name: TABLE categories; Type: COMMENT; Schema: product; Owner: product_service
--

COMMENT ON TABLE product.categories IS 'Catalog categories, one level of subcategory. Depth enforced in the service layer, not by a DB constraint.';


--
-- Name: COLUMN categories.parent_category_id; Type: COMMENT; Schema: product; Owner: product_service
--

COMMENT ON COLUMN product.categories.parent_category_id IS 'Self-referencing FK. NULL for a top-level category.';


--
-- Name: COLUMN categories.description; Type: COMMENT; Schema: product; Owner: product_service
--

COMMENT ON COLUMN product.categories.description IS 'Long-form category description.';


--
-- Name: COLUMN categories.depth_level; Type: COMMENT; Schema: product; Owner: product_service
--

COMMENT ON COLUMN product.categories.depth_level IS '0 = root, 1 = subcategory. Set and capped by CategoryService, not a DB constraint.';


--
-- Name: COLUMN categories.is_active; Type: COMMENT; Schema: product; Owner: product_service
--

COMMENT ON COLUMN product.categories.is_active IS 'Storefront visibility flag.';


--
-- Name: COLUMN categories.version; Type: COMMENT; Schema: product; Owner: product_service
--

COMMENT ON COLUMN product.categories.version IS 'Optimistic-concurrency token. JPA @Version, not a hand-rolled counter.';


--
-- Name: flyway_schema_history; Type: TABLE; Schema: product; Owner: product_service
--

CREATE TABLE product.flyway_schema_history (
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


ALTER TABLE product.flyway_schema_history OWNER TO product_service;

--
-- Name: product_images; Type: TABLE; Schema: product; Owner: product_service
--

CREATE TABLE product.product_images (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    product_id uuid NOT NULL,
    url character varying(2048) NOT NULL,
    display_order integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


ALTER TABLE product.product_images OWNER TO product_service;

--
-- Name: TABLE product_images; Type: COMMENT; Schema: product; Owner: product_service
--

COMMENT ON TABLE product.product_images IS 'Product image URLs, ordered per-product by display_order.';


--
-- Name: products; Type: TABLE; Schema: product; Owner: product_service
--

CREATE TABLE product.products (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    sku character varying(64) NOT NULL,
    name character varying(200) NOT NULL,
    price_amount numeric(19,2) NOT NULL,
    price_currency character(3) NOT NULL,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    category_id uuid,
    seller_name character varying(200),
    specifications jsonb,
    last_known_quantity integer,
    description text,
    list_price numeric(19,2),
    sale_price numeric(19,2),
    discount_percent numeric(5,2),
    thumbnail_url character varying(2048),
    video_urls jsonb,
    view_360_url character varying(2048),
    weight_grams integer,
    dimensions_length numeric(10,2),
    dimensions_width numeric(10,2),
    dimensions_height numeric(10,2),
    color character varying(100),
    material character varying(100),
    average_rating numeric(3,2),
    rating_count integer DEFAULT 0 NOT NULL,
    review_count integer DEFAULT 0 NOT NULL,
    rating_distribution jsonb,
    is_featured boolean DEFAULT false NOT NULL,
    seller_rating numeric(3,2),
    is_seller_verified boolean DEFAULT false NOT NULL,
    CONSTRAINT products_price_amount_check CHECK ((price_amount >= (0)::numeric)),
    CONSTRAINT products_price_currency_check CHECK ((price_currency ~ '^[A-Z]{3}$'::text))
);


ALTER TABLE product.products OWNER TO product_service;

--
-- Name: TABLE products; Type: COMMENT; Schema: product; Owner: product_service
--

COMMENT ON TABLE product.products IS 'Catalog products. Phase 1E: read-only, no categories/inventory/pricing rules.';


--
-- Name: COLUMN products.sku; Type: COMMENT; Schema: product; Owner: product_service
--

COMMENT ON COLUMN product.products.sku IS 'Merchandising identifier. Unique; the natural key external systems quote.';


--
-- Name: COLUMN products.price_amount; Type: COMMENT; Schema: product; Owner: product_service
--

COMMENT ON COLUMN product.products.price_amount IS 'Exact decimal. Never store money in a floating-point column.';


--
-- Name: COLUMN products.version; Type: COMMENT; Schema: product; Owner: product_service
--

COMMENT ON COLUMN product.products.version IS 'Optimistic-concurrency token. JPA @Version, not a hand-rolled counter.';


--
-- Name: COLUMN products.category_id; Type: COMMENT; Schema: product; Owner: product_service
--

COMMENT ON COLUMN product.products.category_id IS 'Nullable FK -> product.categories.id. Not a mapped JPA association - Product.java stays free of associations.';


--
-- Name: COLUMN products.seller_name; Type: COMMENT; Schema: product; Owner: product_service
--

COMMENT ON COLUMN product.products.seller_name IS 'Free-text seller attribution. No sellers table, no FK.';


--
-- Name: COLUMN products.specifications; Type: COMMENT; Schema: product; Owner: product_service
--

COMMENT ON COLUMN product.products.specifications IS 'Arbitrary JSON-shaped attributes. Unstructured on purpose - see LLD S2.1.';


--
-- Name: COLUMN products.last_known_quantity; Type: COMMENT; Schema: product; Owner: product_service
--

COMMENT ON COLUMN product.products.last_known_quantity IS 'Denormalized, non-authoritative. Populated only by a later pass''s inventory-event listener - untouched by create()/update() in this pass.';


--
-- Name: COLUMN products.description; Type: COMMENT; Schema: product; Owner: product_service
--

COMMENT ON COLUMN product.products.description IS 'Long-form product description for detail views.';


--
-- Name: COLUMN products.list_price; Type: COMMENT; Schema: product; Owner: product_service
--

COMMENT ON COLUMN product.products.list_price IS 'MSRP or original price before discount.';


--
-- Name: COLUMN products.sale_price; Type: COMMENT; Schema: product; Owner: product_service
--

COMMENT ON COLUMN product.products.sale_price IS 'Current selling price, may differ from price_amount.';


--
-- Name: COLUMN products.discount_percent; Type: COMMENT; Schema: product; Owner: product_service
--

COMMENT ON COLUMN product.products.discount_percent IS 'Calculated discount percentage (0-100).';


--
-- Name: COLUMN products.thumbnail_url; Type: COMMENT; Schema: product; Owner: product_service
--

COMMENT ON COLUMN product.products.thumbnail_url IS 'Primary product image URL for list views.';


--
-- Name: COLUMN products.video_urls; Type: COMMENT; Schema: product; Owner: product_service
--

COMMENT ON COLUMN product.products.video_urls IS 'Array of video URLs (JSONB).';


--
-- Name: COLUMN products.view_360_url; Type: COMMENT; Schema: product; Owner: product_service
--

COMMENT ON COLUMN product.products.view_360_url IS 'URL for 360-degree product view.';


--
-- Name: COLUMN products.weight_grams; Type: COMMENT; Schema: product; Owner: product_service
--

COMMENT ON COLUMN product.products.weight_grams IS 'Product weight in grams.';


--
-- Name: COLUMN products.dimensions_length; Type: COMMENT; Schema: product; Owner: product_service
--

COMMENT ON COLUMN product.products.dimensions_length IS 'Product length in cm.';


--
-- Name: COLUMN products.dimensions_width; Type: COMMENT; Schema: product; Owner: product_service
--

COMMENT ON COLUMN product.products.dimensions_width IS 'Product width in cm.';


--
-- Name: COLUMN products.dimensions_height; Type: COMMENT; Schema: product; Owner: product_service
--

COMMENT ON COLUMN product.products.dimensions_height IS 'Product height in cm.';


--
-- Name: COLUMN products.color; Type: COMMENT; Schema: product; Owner: product_service
--

COMMENT ON COLUMN product.products.color IS 'Primary product color.';


--
-- Name: COLUMN products.material; Type: COMMENT; Schema: product; Owner: product_service
--

COMMENT ON COLUMN product.products.material IS 'Primary material composition.';


--
-- Name: COLUMN products.average_rating; Type: COMMENT; Schema: product; Owner: product_service
--

COMMENT ON COLUMN product.products.average_rating IS 'Denormalized average rating (0-5), updated by rating-service listener.';


--
-- Name: COLUMN products.rating_count; Type: COMMENT; Schema: product; Owner: product_service
--

COMMENT ON COLUMN product.products.rating_count IS 'Total count of ratings, updated by rating-service listener.';


--
-- Name: COLUMN products.review_count; Type: COMMENT; Schema: product; Owner: product_service
--

COMMENT ON COLUMN product.products.review_count IS 'Count of text reviews, updated by rating-service listener.';


--
-- Name: COLUMN products.rating_distribution; Type: COMMENT; Schema: product; Owner: product_service
--

COMMENT ON COLUMN product.products.rating_distribution IS 'Rating count distribution by star value (JSONB).';


--
-- Name: COLUMN products.is_featured; Type: COMMENT; Schema: product; Owner: product_service
--

COMMENT ON COLUMN product.products.is_featured IS 'Flag for featured/promoted products on storefront.';


--
-- Name: COLUMN products.seller_rating; Type: COMMENT; Schema: product; Owner: product_service
--

COMMENT ON COLUMN product.products.seller_rating IS 'Seller''s own rating separate from product ratings.';


--
-- Name: COLUMN products.is_seller_verified; Type: COMMENT; Schema: product; Owner: product_service
--

COMMENT ON COLUMN product.products.is_seller_verified IS 'Flag indicating seller verification status.';


--
-- Data for Name: categories; Type: TABLE DATA; Schema: product; Owner: product_service
--

COPY product.categories (id, name, parent_category_id, created_at, updated_at, description, depth_level, is_active, version) FROM stdin;
11111111-1111-1111-1111-111111111101	Electronics	\N	2026-11-11 18:21:08.18535+00	2026-11-11 18:21:08.18535+00	\N	0	t	0
11111111-1111-1111-1111-111111111102	Home & Garden	\N	2026-11-11 18:21:08.18535+00	2026-11-11 18:21:08.18535+00	\N	0	t	0
11111111-1111-1111-1111-111111111103	Fashion	\N	2026-11-11 18:21:08.18535+00	2026-11-11 18:21:08.18535+00	\N	0	t	0
11111111-1111-1111-1111-111111111104	Sports	\N	2026-11-11 18:21:08.18535+00	2026-11-11 18:21:08.18535+00	\N	0	t	0
11111111-1111-1111-1111-111111111105	Books	\N	2026-11-11 18:21:08.18535+00	2026-11-11 18:21:08.18535+00	\N	0	t	0
11111111-1111-1111-1111-111111111201	Computers	11111111-1111-1111-1111-111111111101	2026-11-11 18:21:08.18535+00	2026-11-11 18:21:08.18535+00	\N	1	t	0
11111111-1111-1111-1111-111111111202	Peripherals	11111111-1111-1111-1111-111111111101	2026-11-11 18:21:08.18535+00	2026-11-11 18:21:08.18535+00	\N	1	t	0
11111111-1111-1111-1111-111111111203	Audio	11111111-1111-1111-1111-111111111101	2026-11-11 18:21:08.18535+00	2026-11-11 18:21:08.18535+00	\N	1	t	0
11111111-1111-1111-1111-111111111204	Kitchen	11111111-1111-1111-1111-111111111102	2026-11-11 18:21:08.18535+00	2026-11-11 18:21:08.18535+00	\N	1	t	0
11111111-1111-1111-1111-111111111205	Furniture	11111111-1111-1111-1111-111111111102	2026-11-11 18:21:08.18535+00	2026-11-11 18:21:08.18535+00	\N	1	t	0
11111111-1111-1111-1111-111111111206	Lighting	11111111-1111-1111-1111-111111111102	2026-11-11 18:21:08.18535+00	2026-11-11 18:21:08.18535+00	\N	1	t	0
11111111-1111-1111-1111-111111111207	Clothing	11111111-1111-1111-1111-111111111103	2026-11-11 18:21:08.18535+00	2026-11-11 18:21:08.18535+00	\N	1	t	0
11111111-1111-1111-1111-111111111208	Shoes	11111111-1111-1111-1111-111111111103	2026-11-11 18:21:08.18535+00	2026-11-11 18:21:08.18535+00	\N	1	t	0
11111111-1111-1111-1111-111111111209	Accessories	11111111-1111-1111-1111-111111111103	2026-11-11 18:21:08.18535+00	2026-11-11 18:21:08.18535+00	\N	1	t	0
11111111-1111-1111-1111-111111111210	Fitness	11111111-1111-1111-1111-111111111104	2026-11-11 18:21:08.18535+00	2026-11-11 18:21:08.18535+00	\N	1	t	0
11111111-1111-1111-1111-111111111211	Outdoor	11111111-1111-1111-1111-111111111104	2026-11-11 18:21:08.18535+00	2026-11-11 18:21:08.18535+00	\N	1	t	0
11111111-1111-1111-1111-111111111212	Fiction	11111111-1111-1111-1111-111111111105	2026-11-11 18:21:08.18535+00	2026-11-11 18:21:08.18535+00	\N	1	t	0
11111111-1111-1111-1111-111111111213	Non-Fiction	11111111-1111-1111-1111-111111111105	2026-11-11 18:21:08.18535+00	2026-11-11 18:21:08.18535+00	\N	1	t	0
\.


--
-- Data for Name: flyway_schema_history; Type: TABLE DATA; Schema: product; Owner: product_service
--

COPY product.flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) FROM stdin;
0	\N	<< Flyway Schema Creation >>	SCHEMA	"product"	\N	product_service	2026-11-08 12:33:29.679053	0	t
1	1	product baseline	SQL	V1__product_baseline.sql	-1854330802	product_service	2026-11-08 12:33:29.707197	25	t
2	2	product writes	SQL	V2__product_writes.sql	-598820657	product_service	2026-11-08 12:33:29.769514	3	t
3	3	product catalog enrichment	SQL	V3__product_catalog_enrichment.sql	-868442857	product_service	2026-11-08 12:33:29.788017	11	t
4	4	product amazon enrichment	SQL	V4__product_amazon_enrichment.sql	-163263257	product_service	2026-11-10 14:44:44.13835	30	t
5	5	product category enrichment	SQL	V5__product_category_enrichment.sql	1665216705	product_service	2026-11-11 18:21:08.125087	22	t
6	\N	product demo seed	SQL	R__product_demo_seed.sql	171470586	product_service	2026-11-11 18:21:08.16937	22	t
\.


--
-- Data for Name: product_images; Type: TABLE DATA; Schema: product; Owner: product_service
--

COPY product.product_images (id, product_id, url, display_order, created_at) FROM stdin;
a3c2118a-7689-4fc4-bd3b-2c74b2a6dee3	33333333-3333-4333-8333-333333333333	https://thumb.wikimedia.org/wikipedia/commons/thumb/1/18/DiRT_Rally_%E3%82%924K_HD%E3%81%A7%E3%83%99%E3%83%B3%E3%83%81%E3%83%9E%E3%83%BC%E3%82%AF_-Lenovo_ThinkCentre_M75q-1_Tiny_%2B_135W_AC_Adapter-_%2850065100496%29.jpg/960px-DiRT_Rally_%E3%82%924K_HD%E3%81%A7%E3%83%99%E3%83%B3%E3%83%81%E3%83%9E%E3%83%BC%E3%82%AF_-Lenovo_ThinkCentre_M75q-1_Tiny_%2B_135W_AC_Adapter-_%2850065100496%29.jpg	0	2026-11-11 19:44:20.054006+00
9cd00839-fffc-4d1c-b1c7-b77a5ea21fea	33333333-3333-4333-8333-333333333333	https://thumb.wikimedia.org/wikipedia/commons/thumb/2/27/Belleville_Personal_Computer%2C_8080%2C_2MHz%2C_1980._DSC_0663_%286685119805%29.jpg/960px-Belleville_Personal_Computer%2C_8080%2C_2MHz%2C_1980._DSC_0663_%286685119805%29.jpg	1	2026-11-11 19:44:20.054006+00
a70a2641-1696-41f0-aa67-092416b5b64b	33333333-3333-4333-8333-333333333333	https://thumb.wikimedia.org/wikipedia/commons/thumb/2/29/Home_computer_con_interfaccia_a_riga_di_comando_-_Museo_scienza_tecnologia_Milano_09238.jpg/960px-Home_computer_con_interfaccia_a_riga_di_comando_-_Museo_scienza_tecnologia_Milano_09238.jpg	2	2026-11-11 19:44:20.054006+00
1e7e2403-36ab-427c-9032-28584b00682e	33333333-3333-4333-8333-333333333333	https://thumb.wikimedia.org/wikipedia/commons/thumb/5/5b/Kohakus_World_Basement_4K.jpg/960px-Kohakus_World_Basement_4K.jpg	3	2026-11-11 19:44:20.054006+00
3039ebfe-53b5-46d9-aa19-9d5a5f3ca473	bbbbbbbb-bbbb-bbbb-bbbb-000000000001	https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d8/Air_fryer_interior.jpg/960px-Air_fryer_interior.jpg	0	2026-11-11 19:44:20.054006+00
027da395-ea5c-4369-8e70-16ea99ed5653	bbbbbbbb-bbbb-bbbb-bbbb-000000000001	https://thumb.wikimedia.org/wikipedia/commons/thumb/4/41/Tabletop_convection_oven.jpg/960px-Tabletop_convection_oven.jpg	1	2026-11-11 19:44:20.054006+00
a0b9bef1-d3c3-4c2c-baef-dba821a9f7c0	bbbbbbbb-bbbb-bbbb-bbbb-000000000001	https://thumb.wikimedia.org/wikipedia/commons/thumb/8/89/Industrial_oven.jpg/960px-Industrial_oven.jpg	2	2026-11-11 19:44:20.054006+00
da1723c6-c61b-4b62-a04f-ad21addde611	bbbbbbbb-bbbb-bbbb-bbbb-000000000001	https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d3/Air_Fryer_2020.jpg/960px-Air_Fryer_2020.jpg	3	2026-11-11 19:44:20.054006+00
bc3d572f-81a8-4877-ae34-3b3f0b778733	bbbbbbbb-bbbb-bbbb-bbbb-000000000002	https://thumb.wikimedia.org/wikipedia/commons/thumb/c/c4/Navajo_Yei_Rug_03.jpg/960px-Navajo_Yei_Rug_03.jpg	0	2026-11-11 19:44:20.054006+00
1dc35c8f-a985-4d68-b2c9-2555a1d6423f	bbbbbbbb-bbbb-bbbb-bbbb-000000000002	https://thumb.wikimedia.org/wikipedia/commons/thumb/5/57/LESGHI_RUG%2C_EAST_CAUCASUS%2C_c._1880.jpg/960px-LESGHI_RUG%2C_EAST_CAUCASUS%2C_c._1880.jpg	1	2026-11-11 19:44:20.054006+00
0ecdfbcd-ddda-4912-9211-9ab4b386cb67	bbbbbbbb-bbbb-bbbb-bbbb-000000000002	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/0d/Navajo_Storm_Rug_01.jpg/960px-Navajo_Storm_Rug_01.jpg	2	2026-11-11 19:44:20.054006+00
ceef7cfb-5c00-484a-b625-458cdd933093	bbbbbbbb-bbbb-bbbb-bbbb-000000000002	https://thumb.wikimedia.org/wikipedia/commons/thumb/5/55/Navajo_Sandpainting_Blanket_or_Rug_01.jpg/960px-Navajo_Sandpainting_Blanket_or_Rug_01.jpg	3	2026-11-11 19:44:20.054006+00
d2fe0e25-3879-41cc-b0fc-a09edcd373cd	eeeeeeee-eeee-eeee-eeee-000000000001	https://thumb.wikimedia.org/wikipedia/commons/thumb/8/87/Atom_odatlar_3.jpg/960px-Atom_odatlar_3.jpg	0	2026-11-11 19:44:20.054006+00
c03312fc-610b-48b3-9191-bbf6c7c8a379	eeeeeeee-eeee-eeee-eeee-000000000001	https://thumb.wikimedia.org/wikipedia/commons/thumb/5/58/Atom_odatlar_2.jpg/960px-Atom_odatlar_2.jpg	1	2026-11-11 19:44:20.054006+00
b549e71f-b20f-4ead-a084-4e12fd3619fc	eeeeeeee-eeee-eeee-eeee-000000000001	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f5/Plain_home_talk_about_the_human_system-the_habits_of_men_and_women-the_cause_and_prevention_of_disease-our_sexual_relations_and_social_natures_%281896%29_%2814577904417%29.jpg/960px-thumbnail.jpg	2	2026-11-11 19:44:20.054006+00
542ed5e7-b830-41b3-a668-46bbde63fa61	aaaaaaaa-aaaa-aaaa-aaaa-000000000001	https://thumb.wikimedia.org/wikipedia/commons/thumb/6/62/AVID_Audio_Interface_%2848964856277%29.jpg/960px-AVID_Audio_Interface_%2848964856277%29.jpg	0	2026-11-11 19:44:20.054006+00
ed3cbbc5-5d62-4211-b896-31da50b82e06	aaaaaaaa-aaaa-aaaa-aaaa-000000000001	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/04/M-Audio_delta_1010_recording_interface.jpg/960px-M-Audio_delta_1010_recording_interface.jpg	1	2026-11-11 19:44:20.054006+00
2f8a3bb2-014a-413e-a43f-faf84f038e48	aaaaaaaa-aaaa-aaaa-aaaa-000000000001	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/7c/Universal_Audio_%28AES_124%29.jpg/960px-Universal_Audio_%28AES_124%29.jpg	2	2026-11-11 19:44:20.054006+00
982b1762-ccfd-49ae-9160-dce6c42533e9	aaaaaaaa-aaaa-aaaa-aaaa-000000000001	https://thumb.wikimedia.org/wikipedia/commons/thumb/a/ad/Jason_and_Ilya_Working_Out_a_Part%2C_Recording_Fischer%2C_Compound_Recordings%2C_January_2008.jpg/960px-Jason_and_Ilya_Working_Out_a_Part%2C_Recording_Fischer%2C_Compound_Recordings%2C_January_2008.jpg	3	2026-11-11 19:44:20.054006+00
5090fdd4-0679-40a9-bf99-2ca830bc166a	dddddddd-dddd-dddd-dddd-000000000001	https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a3/A_backpack_with_trekking_poles_and_shoes.jpg/960px-A_backpack_with_trekking_poles_and_shoes.jpg	0	2026-11-11 19:44:20.054006+00
68ac2545-591c-4f98-955b-75163769aca4	dddddddd-dddd-dddd-dddd-000000000001	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/38/Catbells_Northern_Ascent%2C_Lake_District_-_June_2009.jpg/960px-Catbells_Northern_Ascent%2C_Lake_District_-_June_2009.jpg	1	2026-11-11 19:44:20.054006+00
6c43021a-8e23-438a-8a89-a1538b5f91c6	dddddddd-dddd-dddd-dddd-000000000001	https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d3/Nude_woman_hiking_in_the_desert_with_a_backpack.jpg/960px-Nude_woman_hiking_in_the_desert_with_a_backpack.jpg	2	2026-11-11 19:44:20.054006+00
8a4e8000-9ac2-4b9a-b306-718e9034a085	dddddddd-dddd-dddd-dddd-000000000001	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f7/Trekking_trail_of_Netravati_peak_from_the_summit_%28Zoomed_in%29.jpg/960px-Trekking_trail_of_Netravati_peak_from_the_summit_%28Zoomed_in%29.jpg	3	2026-11-11 19:44:20.054006+00
6b835af5-339d-4b63-9678-696b02246a8a	cccccccc-cccc-cccc-cccc-000000000001	https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a3/A_backpack_with_trekking_poles_and_shoes.jpg/960px-A_backpack_with_trekking_poles_and_shoes.jpg	0	2026-11-11 19:44:20.054006+00
f68ddadb-0b88-496b-9464-8b7d40fcfc3a	cccccccc-cccc-cccc-cccc-000000000001	https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a1/Plecak_Hiker_50_L_HiMountain.jpg/960px-Plecak_Hiker_50_L_HiMountain.jpg	1	2026-11-11 19:44:20.054006+00
667b77d6-0f1e-4873-a7b8-8a23f4c4e343	cccccccc-cccc-cccc-cccc-000000000001	https://thumb.wikimedia.org/wikipedia/commons/thumb/8/81/Backpack_small.jpg/960px-Backpack_small.jpg	2	2026-11-11 19:44:20.054006+00
beb95078-29a2-43f4-bea2-2a8f129f0364	cccccccc-cccc-cccc-cccc-000000000001	https://upload.wikimedia.org/wikipedia/commons/b/bb/Cat_photo_backpack.jpg	3	2026-11-11 19:44:20.054006+00
f001afae-29db-43f9-bbab-fbda93cd93c7	bbbbbbbb-bbbb-bbbb-bbbb-000000000003	https://thumb.wikimedia.org/wikipedia/commons/thumb/1/1e/Product_965_size.jpg/960px-Product_965_size.jpg	0	2026-11-11 19:44:20.054006+00
c2cbfe16-7599-440f-aa39-1a4cb7eaa055	bbbbbbbb-bbbb-bbbb-bbbb-000000000003	https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a9/Fragment_of_a_bed_frame%2C_wood_-_Museo_Egizio_Turin_S_11111_2_p01.jpg/960px-Fragment_of_a_bed_frame%2C_wood_-_Museo_Egizio_Turin_S_11111_2_p01.jpg	1	2026-11-11 19:44:20.054006+00
3c3cd823-f0d9-40bd-9eb6-150a0fa4b059	bbbbbbbb-bbbb-bbbb-bbbb-000000000003	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f4/Fragment_of_a_bed_frame%2C_wood_-_Museo_Egizio_%28Turin%29_S_11111_02_p04.jpg/960px-Fragment_of_a_bed_frame%2C_wood_-_Museo_Egizio_%28Turin%29_S_11111_02_p04.jpg	2	2026-11-11 19:44:20.054006+00
2949a911-748a-4ac8-803d-95c04ea8a0cb	bbbbbbbb-bbbb-bbbb-bbbb-000000000003	https://thumb.wikimedia.org/wikipedia/commons/thumb/6/60/Fragment_of_a_bed_frame%2C_wood_-_Museo_Egizio_%28Turin%29_S_11111_02_p03.jpg/960px-Fragment_of_a_bed_frame%2C_wood_-_Museo_Egizio_%28Turin%29_S_11111_02_p03.jpg	3	2026-11-11 19:44:20.054006+00
ab240484-f9e1-4a39-8d76-643354ab491a	cccccccc-cccc-cccc-cccc-000000000002	https://thumb.wikimedia.org/wikipedia/commons/thumb/1/18/Germany_Belt-and-Buckle-02.jpg/960px-Germany_Belt-and-Buckle-02.jpg	0	2026-11-11 19:44:20.054006+00
27050b98-bbfa-4481-a813-a17e28dcf61b	cccccccc-cccc-cccc-cccc-000000000002	https://thumb.wikimedia.org/wikipedia/commons/thumb/a/ae/Lederarbeiten_002_2016_11_26.jpg/960px-Lederarbeiten_002_2016_11_26.jpg	1	2026-11-11 19:44:20.054006+00
85e011ba-b079-49db-8819-82ef1761cbc6	cccccccc-cccc-cccc-cccc-000000000002	https://thumb.wikimedia.org/wikipedia/commons/thumb/9/91/Belt-clothing.jpg/960px-Belt-clothing.jpg	2	2026-11-11 19:44:20.054006+00
c2567fe3-5efd-4c90-b09a-4396c463d4ab	cccccccc-cccc-cccc-cccc-000000000002	https://upload.wikimedia.org/wikipedia/commons/4/46/Belt_%28clothing%29.jpg	3	2026-11-11 19:44:20.054006+00
bf81b987-8aee-4c2d-90a0-2e610e82dafb	dddddddd-dddd-dddd-dddd-000000000002	https://thumb.wikimedia.org/wikipedia/commons/thumb/2/2e/Mountain_biking.jpg/960px-Mountain_biking.jpg	0	2026-11-11 19:44:20.054006+00
cf1cf8a3-7b4d-4481-aadc-dae1173e79ed	dddddddd-dddd-dddd-dddd-000000000002	https://thumb.wikimedia.org/wikipedia/commons/thumb/1/19/Cube%2C_Cyclingworld_Europe_2024%2C_Meerbusch_%28P1180003%29.jpg/960px-Cube%2C_Cyclingworld_Europe_2024%2C_Meerbusch_%28P1180003%29.jpg	1	2026-11-11 19:44:20.054006+00
48835cb9-c57e-485a-bbbc-483ec93fa4c0	dddddddd-dddd-dddd-dddd-000000000002	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/7d/Bulls_Wild_Cup_1_%28Modell_2010%29_20100814.jpg/960px-Bulls_Wild_Cup_1_%28Modell_2010%29_20100814.jpg	2	2026-11-11 19:44:20.054006+00
65ec7b54-eed4-4671-a501-c02d2f4e7425	dddddddd-dddd-dddd-dddd-000000000002	https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a2/Mountain_bike_downhill_06.jpg/960px-Mountain_bike_downhill_06.jpg	3	2026-11-11 19:44:20.054006+00
0af77414-959d-4538-b586-285dc8250a62	bbbbbbbb-bbbb-bbbb-bbbb-000000000004	https://thumb.wikimedia.org/wikipedia/commons/thumb/d/dc/Pi%C3%B1a_Coladas_made_in_home_kitchen_blender_-_1.jpg/960px-Pi%C3%B1a_Coladas_made_in_home_kitchen_blender_-_1.jpg	0	2026-11-11 19:44:20.054006+00
d85dd141-9c26-442b-8a50-5717e026bb3d	bbbbbbbb-bbbb-bbbb-bbbb-000000000004	https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b4/Pi%C3%B1a_Coladas_made_in_home_kitchen_blender_-_3.jpg/960px-Pi%C3%B1a_Coladas_made_in_home_kitchen_blender_-_3.jpg	1	2026-11-11 19:44:20.054006+00
72e34a53-1c99-47af-bcb8-1e20be7ea5db	bbbbbbbb-bbbb-bbbb-bbbb-000000000004	https://thumb.wikimedia.org/wikipedia/commons/thumb/1/11/Pi%C3%B1a_Coladas_made_in_home_kitchen_blender_-_2.jpg/960px-Pi%C3%B1a_Coladas_made_in_home_kitchen_blender_-_2.jpg	2	2026-11-11 19:44:20.054006+00
1114887e-a050-47dd-8f5d-62065b4c2aac	bbbbbbbb-bbbb-bbbb-bbbb-000000000004	https://thumb.wikimedia.org/wikipedia/commons/thumb/6/6d/Old_blender_on_shelf_in_New_Orleans_kitchen.jpg/960px-Old_blender_on_shelf_in_New_Orleans_kitchen.jpg	3	2026-11-11 19:44:20.054006+00
47182c36-33ae-4386-bc98-a0d0e81e0061	bbbbbbbb-bbbb-bbbb-bbbb-000000000005	https://thumb.wikimedia.org/wikipedia/commons/thumb/c/c5/Part_of_a_bookshelf_containing_books_by_Aristotle_%281%29.jpg/960px-Part_of_a_bookshelf_containing_books_by_Aristotle_%281%29.jpg	0	2026-11-11 19:44:20.054006+00
6513c598-0b07-4e22-ae75-365934fa40f0	bbbbbbbb-bbbb-bbbb-bbbb-000000000005	https://thumb.wikimedia.org/wikipedia/commons/thumb/e/ec/Bookshelf_Prunksaal_OeNB_Vienna_AT_matl00786ch.jpg/960px-Bookshelf_Prunksaal_OeNB_Vienna_AT_matl00786ch.jpg	1	2026-11-11 19:44:20.054006+00
8fc746db-730d-4f89-88ea-f09c1bd66467	bbbbbbbb-bbbb-bbbb-bbbb-000000000005	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/73/Part_of_a_bookshelf_containing_books_by_ancient_philosophers_%281.1%29.jpg/960px-Part_of_a_bookshelf_containing_books_by_ancient_philosophers_%281.1%29.jpg	2	2026-11-11 19:44:20.054006+00
5efb7ef3-b1a1-4e42-b20e-92269e584915	bbbbbbbb-bbbb-bbbb-bbbb-000000000005	https://thumb.wikimedia.org/wikipedia/commons/thumb/d/dc/Bookcase_in_Olten.jpg/960px-Bookcase_in_Olten.jpg	3	2026-11-11 19:44:20.054006+00
ed52563c-0c81-4b01-bb8e-6758ae28cb8f	aaaaaaaa-aaaa-aaaa-aaaa-000000000002	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/ff/Tripod_microphone_stand.jpg/960px-Tripod_microphone_stand.jpg	0	2026-11-11 19:44:20.054006+00
7fd6302d-e6bc-4e7d-b401-045e1bfc119b	aaaaaaaa-aaaa-aaaa-aaaa-000000000002	https://upload.wikimedia.org/wikipedia/commons/f/f4/Boom_Crash_Opera_Australia_Day.jpg	1	2026-11-11 19:44:20.054006+00
577a8296-d5ff-483b-95c9-37eb45a927bd	cccccccc-cccc-cccc-cccc-000000000003	https://thumb.wikimedia.org/wikipedia/commons/thumb/1/1f/Leather_boots_men%27s.jpg/960px-Leather_boots_men%27s.jpg	0	2026-11-11 19:44:20.054006+00
e7e675bb-1c50-4659-ad88-c52994712c2d	cccccccc-cccc-cccc-cccc-000000000003	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/7a/Applying_mink_oil_to_leather_work_boots.jpg/960px-Applying_mink_oil_to_leather_work_boots.jpg	1	2026-11-11 19:44:20.054006+00
2ddd75a7-ea69-4d86-b630-bac99e7131f2	cccccccc-cccc-cccc-cccc-000000000003	https://thumb.wikimedia.org/wikipedia/commons/thumb/2/26/Cleaning_my_nubuck_leather_work_boots.jpg/960px-Cleaning_my_nubuck_leather_work_boots.jpg	2	2026-11-11 19:44:20.054006+00
1bb08fb6-94ef-409b-a56e-dd41e4970fa4	cccccccc-cccc-cccc-cccc-000000000003	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/38/Leather_Goodyear_welt_example.jpg/960px-Leather_Goodyear_welt_example.jpg	3	2026-11-11 19:44:20.054006+00
48843d7f-ff03-490b-9ef0-cbf77e148d49	aaaaaaaa-aaaa-aaaa-aaaa-000000000003	https://thumb.wikimedia.org/wikipedia/commons/thumb/9/90/Cable_Management_Dell_1950.jpg/960px-Cable_Management_Dell_1950.jpg	0	2026-11-11 19:44:20.054006+00
3b93510c-9a94-4b0a-887a-210e7ad575ff	aaaaaaaa-aaaa-aaaa-aaaa-000000000003	https://thumb.wikimedia.org/wikipedia/commons/thumb/b/bc/19-inch_rackmount_Ethernet_switches_and_patch_panels.jpg/960px-19-inch_rackmount_Ethernet_switches_and_patch_panels.jpg	1	2026-11-11 19:44:20.054006+00
6d8bacea-1661-476f-86a0-b9bff570e5e3	aaaaaaaa-aaaa-aaaa-aaaa-000000000003	https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b0/Server_cable_management_%282%29_-_IMG_3548.jpg/960px-Server_cable_management_%282%29_-_IMG_3548.jpg	2	2026-11-11 19:44:20.054006+00
ff45081c-62f9-4203-9216-bec9d4ac8255	aaaaaaaa-aaaa-aaaa-aaaa-000000000003	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/07/Server_cable_management_-_IMG_3545.jpg/960px-Server_cable_management_-_IMG_3545.jpg	3	2026-11-11 19:44:20.054006+00
cbadc4dd-f85c-46f9-956b-bda52115db6c	bbbbbbbb-bbbb-bbbb-bbbb-000000000006	https://thumb.wikimedia.org/wikipedia/commons/thumb/8/88/Ceiling_light_fixture_in_Hofstatt_Mall%2C_Munich%2C_February_2017.jpg/960px-Ceiling_light_fixture_in_Hofstatt_Mall%2C_Munich%2C_February_2017.jpg	0	2026-11-11 19:44:20.054006+00
8dfc1c2e-16a7-4367-83d1-36e512f35da0	bbbbbbbb-bbbb-bbbb-bbbb-000000000006	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/3b/Shattered_light_fixture_3.jpg/960px-Shattered_light_fixture_3.jpg	1	2026-11-11 19:44:20.054006+00
5c442f29-a11b-4d1f-af58-d805861e00b2	bbbbbbbb-bbbb-bbbb-bbbb-000000000006	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/34/Korfu_%28GR%29%2C_Korfu%2C_Alte_Festung_--_2018_--_1137.jpg/960px-Korfu_%28GR%29%2C_Korfu%2C_Alte_Festung_--_2018_--_1137.jpg	2	2026-11-11 19:44:20.054006+00
c1a16ab0-91d4-47b6-be4f-5d29e19a04c3	bbbbbbbb-bbbb-bbbb-bbbb-000000000006	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/70/Prague_Main_Railway_Station_Ceiling_Lights.jpg/960px-Prague_Main_Railway_Station_Ceiling_Lights.jpg	3	2026-11-11 19:44:20.054006+00
bf27d7b3-dd9a-4966-b12f-d3bca7497fe0	bbbbbbbb-bbbb-bbbb-bbbb-000000000007	https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b9/Our_new_coffee_maker_%282006%29_Hamilton_Beach.jpg/960px-Our_new_coffee_maker_%282006%29_Hamilton_Beach.jpg	0	2026-11-11 19:44:20.054006+00
08c7eff6-7519-4340-afbc-76719e5e8f15	bbbbbbbb-bbbb-bbbb-bbbb-000000000007	https://thumb.wikimedia.org/wikipedia/commons/thumb/2/20/Hamilton_Beach_coffee_maker_2022.jpg/960px-Hamilton_Beach_coffee_maker_2022.jpg	1	2026-11-11 19:44:20.054006+00
a7742059-5140-4390-afe7-2abcd34a7103	bbbbbbbb-bbbb-bbbb-bbbb-000000000007	https://thumb.wikimedia.org/wikipedia/commons/thumb/6/6b/Krups_koffiezetapparaat_van_Van_Nelle%2C_zwart%2C_objectnr_90899-A-B.JPG/960px-Krups_koffiezetapparaat_van_Van_Nelle%2C_zwart%2C_objectnr_90899-A-B.JPG	2	2026-11-11 19:44:20.054006+00
946b69f7-2f18-4b84-9dd8-6d299d6de34d	bbbbbbbb-bbbb-bbbb-bbbb-000000000007	https://thumb.wikimedia.org/wikipedia/commons/thumb/b/bb/Hamilton_Beach_home_coffee_maker.jpg/960px-Hamilton_Beach_home_coffee_maker.jpg	3	2026-11-11 19:44:20.054006+00
0b2e89a1-fa73-495f-8735-7fcec6f47189	bbbbbbbb-bbbb-bbbb-bbbb-000000000008	https://thumb.wikimedia.org/wikipedia/commons/thumb/8/8c/Miele%2C_IFA_2018%2C_Berlin_%28P1070072%29.jpg/960px-Miele%2C_IFA_2018%2C_Berlin_%28P1070072%29.jpg	0	2026-11-11 19:44:20.054006+00
d8ffa1ff-bac9-4f8a-944c-b57c833a71f2	bbbbbbbb-bbbb-bbbb-bbbb-000000000008	https://upload.wikimedia.org/wikipedia/commons/4/42/Coffee_Table_on_Wheels.jpg	1	2026-11-11 19:44:20.054006+00
86db2bf9-936f-4435-8eb1-e28505d15164	bbbbbbbb-bbbb-bbbb-bbbb-000000000008	https://thumb.wikimedia.org/wikipedia/commons/thumb/5/5b/Coffee_Table_book_stack.jpg/960px-Coffee_Table_book_stack.jpg	2	2026-11-11 19:44:20.054006+00
7488e3a5-a7a7-4f2d-a447-ce5242181f4b	bbbbbbbb-bbbb-bbbb-bbbb-000000000008	https://thumb.wikimedia.org/wikipedia/commons/thumb/5/5b/Glass_Coffee_Table_1_2016-05-21.jpg/960px-Glass_Coffee_Table_1_2016-05-21.jpg	3	2026-11-11 19:44:20.054006+00
f2215e61-5acd-4b1a-b025-aa817f252c23	aaaaaaaa-aaaa-aaaa-aaaa-000000000004	https://thumb.wikimedia.org/wikipedia/commons/thumb/1/14/Laptop_cooler.JPG/960px-Laptop_cooler.JPG	0	2026-11-11 19:44:20.054006+00
cf8a1949-328a-4254-842e-80ffcb645ec8	aaaaaaaa-aaaa-aaaa-aaaa-000000000004	https://thumb.wikimedia.org/wikipedia/commons/thumb/c/cd/Passive_Laptop_cooler.jpg/960px-Passive_Laptop_cooler.jpg	1	2026-11-11 19:44:20.054006+00
de7d9c5b-6b90-4103-8a3b-79de7898ade3	aaaaaaaa-aaaa-aaaa-aaaa-000000000004	https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b0/ThinkPad_X220_CPU_cooling_system_%28fan_and_heatsink_assembly%29.jpg/960px-ThinkPad_X220_CPU_cooling_system_%28fan_and_heatsink_assembly%29.jpg	2	2026-11-11 19:44:20.054006+00
7b352a47-cc6c-42f5-8e9d-2bdc4ab3f9e2	aaaaaaaa-aaaa-aaaa-aaaa-000000000004	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/06/Laptop_coolers.jpg/960px-Laptop_coolers.jpg	3	2026-11-11 19:44:20.054006+00
dd7288f4-d786-4a1c-90b8-1e6164230400	bbbbbbbb-bbbb-bbbb-bbbb-000000000009	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/ff/French_Return_Drapery_Rods.jpg/960px-French_Return_Drapery_Rods.jpg	0	2026-11-11 19:44:20.054006+00
7932739a-e4d7-4c7a-9180-5bbd28bdc306	bbbbbbbb-bbbb-bbbb-bbbb-000000000009	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f1/Liturgic_Curtain_in_synagogue_in_T%C5%99eb%C3%AD%C4%8D%2C_T%C5%99eb%C3%AD%C4%8D_District.jpg/960px-Liturgic_Curtain_in_synagogue_in_T%C5%99eb%C3%AD%C4%8D%2C_T%C5%99eb%C3%AD%C4%8D_District.jpg	1	2026-11-11 19:44:20.054006+00
f1f2329d-f045-4fc0-9316-b176278e3a98	bbbbbbbb-bbbb-bbbb-bbbb-000000000009	https://upload.wikimedia.org/wikipedia/commons/2/26/Brooklyn_Museum_-_Noren_%28Shop_Curtain%29.jpg	2	2026-11-11 19:44:20.054006+00
7ef27185-caa9-49b9-a1e2-5967880ac493	bbbbbbbb-bbbb-bbbb-bbbb-000000000009	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/31/Design_for_Gothic_Curtains_and_Curtain_Rod_MET_DP804606.jpg/960px-Design_for_Gothic_Curtains_and_Curtain_Rod_MET_DP804606.jpg	3	2026-11-11 19:44:20.054006+00
1a67547f-69a8-49b7-ba34-259e5ce343f8	bbbbbbbb-bbbb-bbbb-bbbb-000000000010	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/74/Pacific_oyster_from_Brofjorden_on_a_chopping_board_in_Tuntorp.jpg/960px-Pacific_oyster_from_Brofjorden_on_a_chopping_board_in_Tuntorp.jpg	0	2026-11-11 19:44:20.054006+00
41a86bc7-fc53-4044-bc17-77ce36232e99	bbbbbbbb-bbbb-bbbb-bbbb-000000000010	https://thumb.wikimedia.org/wikipedia/commons/thumb/4/48/Cutting_Board.jpg/960px-Cutting_Board.jpg	1	2026-11-11 19:44:20.054006+00
503dc2b0-044c-4879-af17-25c4e9b90166	bbbbbbbb-bbbb-bbbb-bbbb-000000000010	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/74/Chopping_Board.jpg/960px-Chopping_Board.jpg	2	2026-11-11 19:44:20.054006+00
76053648-663d-469f-af38-77024dc6adf1	bbbbbbbb-bbbb-bbbb-bbbb-000000000010	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f9/Detail_of_end_grain_cutting_board.jpg/960px-Detail_of_end_grain_cutting_board.jpg	3	2026-11-11 19:44:20.054006+00
efaf231a-5f13-4383-b5c3-75dc89f84256	bbbbbbbb-bbbb-bbbb-bbbb-000000000011	https://thumb.wikimedia.org/wikipedia/commons/thumb/1/13/Abbotsford_House_Study_Room.jpg/960px-Abbotsford_House_Study_Room.jpg	0	2026-11-11 19:44:20.054006+00
c80742ed-f459-4190-b4fe-ec03cb9a4e14	bbbbbbbb-bbbb-bbbb-bbbb-000000000011	https://thumb.wikimedia.org/wikipedia/commons/thumb/b/bf/Palamuse_kihelkonnakooli_klassiruum.jpg/960px-Palamuse_kihelkonnakooli_klassiruum.jpg	1	2026-11-11 19:44:20.054006+00
36304abb-6602-4b8a-b1c7-4aeb5b12a227	bbbbbbbb-bbbb-bbbb-bbbb-000000000011	https://thumb.wikimedia.org/wikipedia/commons/thumb/d/da/Merida_Yucatan_-_Consulate_Office_Building%2C_Secretarial_Desk_-_1977.jpg/960px-Merida_Yucatan_-_Consulate_Office_Building%2C_Secretarial_Desk_-_1977.jpg	2	2026-11-11 19:44:20.054006+00
8fa8f0e6-c368-4ce3-b839-12a6ba89d24b	bbbbbbbb-bbbb-bbbb-bbbb-000000000011	https://thumb.wikimedia.org/wikipedia/commons/thumb/c/c1/George-peabody-library.jpg/960px-George-peabody-library.jpg	3	2026-11-11 19:44:20.054006+00
5067dabf-aaca-4138-8fa4-70588e3df52f	bbbbbbbb-bbbb-bbbb-bbbb-000000000012	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/3b/Shattered_light_fixture_3.jpg/960px-Shattered_light_fixture_3.jpg	0	2026-11-11 19:44:20.054006+00
04026e5e-a553-4f9a-a846-3b05d10dc49a	bbbbbbbb-bbbb-bbbb-bbbb-000000000012	https://thumb.wikimedia.org/wikipedia/commons/thumb/8/88/Ceiling_light_fixture_in_Hofstatt_Mall%2C_Munich%2C_February_2017.jpg/960px-Ceiling_light_fixture_in_Hofstatt_Mall%2C_Munich%2C_February_2017.jpg	1	2026-11-11 19:44:20.054006+00
524496ba-9836-4b33-9928-26c79bb82c6d	bbbbbbbb-bbbb-bbbb-bbbb-000000000012	https://thumb.wikimedia.org/wikipedia/commons/thumb/8/8d/M%C3%BCnster%2C_St.-Lamberti-Kirche%2C_Westportal_--_2021_--_9082.jpg/960px-M%C3%BCnster%2C_St.-Lamberti-Kirche%2C_Westportal_--_2021_--_9082.jpg	2	2026-11-11 19:44:20.054006+00
90b169c2-78a3-4bf2-8f94-1c731b03ca89	bbbbbbbb-bbbb-bbbb-bbbb-000000000012	https://thumb.wikimedia.org/wikipedia/commons/thumb/9/95/Staircase_of_the_BGK_Building_%2826620981474%29.jpg/960px-Staircase_of_the_BGK_Building_%2826620981474%29.jpg	3	2026-11-11 19:44:20.054006+00
3dd40248-6834-4e56-a05c-c1d75ca4bc32	aaaaaaaa-aaaa-aaaa-aaaa-000000000005	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/3f/Drawing%2C_Design_for_Desk_Organizer_with_Stationery%2C_1900%E2%80%9305_%28CH_18684169%29.jpg/960px-Drawing%2C_Design_for_Desk_Organizer_with_Stationery%2C_1900%E2%80%9305_%28CH_18684169%29.jpg	0	2026-11-11 19:44:20.054006+00
6a8658b9-b8e9-4861-95ca-541e51d04f53	aaaaaaaa-aaaa-aaaa-aaaa-000000000005	https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d6/Drawing%2C_Design_for_Plain_Desk_Organizer%2C_1900%E2%80%9305_%28CH_18684165%29.jpg/960px-Drawing%2C_Design_for_Plain_Desk_Organizer%2C_1900%E2%80%9305_%28CH_18684165%29.jpg	1	2026-11-11 19:44:20.054006+00
da016081-48ec-40dd-96b1-c6a96d98b6e2	aaaaaaaa-aaaa-aaaa-aaaa-000000000005	https://thumb.wikimedia.org/wikipedia/commons/thumb/e/ed/Drawing%2C_Design_for_Desk_Organizer_with_Quill_Pen%2C_1900%E2%80%9305_%28CH_18684179%29.jpg/960px-Drawing%2C_Design_for_Desk_Organizer_with_Quill_Pen%2C_1900%E2%80%9305_%28CH_18684179%29.jpg	2	2026-11-11 19:44:20.054006+00
0565458c-958d-4d1f-832e-08717b70bdf7	aaaaaaaa-aaaa-aaaa-aaaa-000000000005	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/05/Drawing%2C_Design_for_Desk_Organizer_in_Plan_and_Elevation%2C_1900%E2%80%9305_%28CH_18684163%29.jpg/960px-Drawing%2C_Design_for_Desk_Organizer_in_Plan_and_Elevation%2C_1900%E2%80%9305_%28CH_18684163%29.jpg	3	2026-11-11 19:44:20.054006+00
05bb6ab4-6ccc-4ff9-9938-cbaf5071fbdb	aaaaaaaa-aaaa-aaaa-aaaa-000000000006	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/78/Gaming_computers_%281%29.jpg/960px-Gaming_computers_%281%29.jpg	0	2026-11-11 19:44:20.054006+00
52b52280-e1e0-49ab-9240-2dbed7a30e12	aaaaaaaa-aaaa-aaaa-aaaa-000000000006	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/31/Gaming_PC-Setup_-_Astaroth-_The_Completed_System.jpg/960px-Gaming_PC-Setup_-_Astaroth-_The_Completed_System.jpg	1	2026-11-11 19:44:20.054006+00
702c672d-4ab4-47ec-8cbd-1676796ba736	aaaaaaaa-aaaa-aaaa-aaaa-000000000006	https://thumb.wikimedia.org/wikipedia/commons/thumb/b/be/Astaroth-_Inside_the_Finished_Build.jpg/960px-Astaroth-_Inside_the_Finished_Build.jpg	2	2026-11-11 19:44:20.054006+00
2ce130d9-9f3f-4f0f-97d3-bec82bf10896	aaaaaaaa-aaaa-aaaa-aaaa-000000000006	https://thumb.wikimedia.org/wikipedia/commons/thumb/6/6e/Astaroth-_Build_in_Progress.jpg/960px-Astaroth-_Build_in_Progress.jpg	3	2026-11-11 19:44:20.054006+00
d33777fe-ae63-45fb-991e-c84add973a98	bbbbbbbb-bbbb-bbbb-bbbb-000000000013	https://thumb.wikimedia.org/wikipedia/commons/thumb/8/85/Wright_dining_chair_side_-_HNT.jpg/960px-Wright_dining_chair_side_-_HNT.jpg	0	2026-11-11 19:44:20.054006+00
d9789c6d-96f7-46fc-903d-d8631fd614f9	bbbbbbbb-bbbb-bbbb-bbbb-000000000013	https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b0/Wright_dining_chair_back_-_HNT.jpg/960px-Wright_dining_chair_back_-_HNT.jpg	1	2026-11-11 19:44:20.054006+00
8ed96b77-1466-4c42-b154-2e0badeaa510	bbbbbbbb-bbbb-bbbb-bbbb-000000000013	https://thumb.wikimedia.org/wikipedia/commons/thumb/2/28/Wright_dining_table_-_HNT.jpg/960px-Wright_dining_table_-_HNT.jpg	2	2026-11-11 19:44:20.054006+00
d6820054-1e86-47c0-bbde-403880ce80c6	bbbbbbbb-bbbb-bbbb-bbbb-000000000013	https://upload.wikimedia.org/wikipedia/commons/9/95/Danish_Rosewood_Dining_Chair-1.jpg	3	2026-11-11 19:44:20.054006+00
b887fb6d-7453-4411-bf63-acf2734a1e70	bbbbbbbb-bbbb-bbbb-bbbb-000000000014	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/fc/Dining_table_in_Standen_House_2024-10-08.jpg/960px-Dining_table_in_Standen_House_2024-10-08.jpg	0	2026-11-11 19:44:20.054006+00
8f1aef2d-800b-424f-ae6f-87123795731e	bbbbbbbb-bbbb-bbbb-bbbb-000000000014	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/38/Markedshallen_%28Dining_table%29.jpg/960px-Markedshallen_%28Dining_table%29.jpg	1	2026-11-11 19:44:20.054006+00
c646723f-bec5-4075-a7b3-be24abe0047c	bbbbbbbb-bbbb-bbbb-bbbb-000000000014	https://thumb.wikimedia.org/wikipedia/commons/thumb/6/6f/East_Slovak_Museum_in_Ko%C5%A1ice_-_Experience_Gothic_exhibit_-_Wooden_dining_table%2C_15th_century%2C_Spi%C5%A1_region.jpg/960px-East_Slovak_Museum_in_Ko%C5%A1ice_-_Experience_Gothic_exhibit_-_Wooden_dining_table%2C_15th_century%2C_Spi%C5%A1_region.jpg	2	2026-11-11 19:44:20.054006+00
9b760930-37a8-4c65-b34d-961caf6f9f1d	bbbbbbbb-bbbb-bbbb-bbbb-000000000014	https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a6/Dining_table_1.jpg/960px-Dining_table_1.jpg	3	2026-11-11 19:44:20.054006+00
04ed6892-fdfa-4b24-9ac5-0785ac9af8fd	aaaaaaaa-aaaa-aaaa-aaaa-000000000007	https://thumb.wikimedia.org/wikipedia/commons/thumb/6/60/Bottom_view_of_a_Dell_D-Dock_PD01X_docking_station.jpg/960px-Bottom_view_of_a_Dell_D-Dock_PD01X_docking_station.jpg	0	2026-11-11 19:44:20.054006+00
3032ce27-bd0c-4902-b088-a9a588dd4ef7	aaaaaaaa-aaaa-aaaa-aaaa-000000000007	https://thumb.wikimedia.org/wikipedia/commons/thumb/c/cc/Top_view_of_a_Dell_D-Dock_PD01X_with_docking_controls.jpg/960px-Top_view_of_a_Dell_D-Dock_PD01X_with_docking_controls.jpg	1	2026-11-11 19:44:20.054006+00
1f7d42a7-31c8-476b-a5bd-5a64daa76bbe	aaaaaaaa-aaaa-aaaa-aaaa-000000000007	https://thumb.wikimedia.org/wikipedia/commons/thumb/2/25/Rear_ports_of_a_Dell_D-Dock_PD01X_Expansion_Station.jpg/960px-Rear_ports_of_a_Dell_D-Dock_PD01X_Expansion_Station.jpg	2	2026-11-11 19:44:20.054006+00
3016fe80-0b09-4522-b101-f2c39f1c4816	aaaaaaaa-aaaa-aaaa-aaaa-000000000007	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f6/Docking_station_1.jpg/960px-Docking_station_1.jpg	3	2026-11-11 19:44:20.054006+00
0d7f0e73-0760-45dc-a5a3-2f91e3df6e88	cccccccc-cccc-cccc-cccc-000000000004	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f1/Woman%27s_shirt_from_Kutch%2C_Gujarat%2C_India%2C_IMA_55114.jpg/960px-Woman%27s_shirt_from_Kutch%2C_Gujarat%2C_India%2C_IMA_55114.jpg	0	2026-11-11 19:44:20.054006+00
96a9d7d9-9cb5-40d0-9f52-d9edcdf35388	cccccccc-cccc-cccc-cccc-000000000004	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/0e/Jun_Takahashi_dress_for_Undercover_%2851492%29.jpg/960px-Jun_Takahashi_dress_for_Undercover_%2851492%29.jpg	1	2026-11-11 19:44:20.054006+00
aea3882d-a2cc-4557-9575-6311f184fa55	cccccccc-cccc-cccc-cccc-000000000004	https://thumb.wikimedia.org/wikipedia/commons/thumb/1/16/KIM_MATA_HIPOL_%28DRESS%29.jpg/960px-KIM_MATA_HIPOL_%28DRESS%29.jpg	2	2026-11-11 19:44:20.054006+00
f5cf7837-1c12-4415-acb5-398ea9846b93	cccccccc-cccc-cccc-cccc-000000000004	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/33/Ladies_dresses_in_a_shop.jpg/960px-Ladies_dresses_in_a_shop.jpg	3	2026-11-11 19:44:20.054006+00
b5248232-7473-45ee-b525-a98f2f6f618e	dddddddd-dddd-dddd-dddd-000000000003	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f1/Colorful_dumbbells_of_various_sizes.jpg/960px-Colorful_dumbbells_of_various_sizes.jpg	0	2026-11-11 19:44:20.054006+00
35e2380d-19f3-42e4-9306-c46efcb4b458	dddddddd-dddd-dddd-dddd-000000000003	https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a7/Purple_dumbbells_on_a_pile.jpg/960px-Purple_dumbbells_on_a_pile.jpg	1	2026-11-11 19:44:20.054006+00
76e1bd1d-9dce-4cb9-88ef-f0dc994a46c2	dddddddd-dddd-dddd-dddd-000000000003	https://thumb.wikimedia.org/wikipedia/commons/thumb/1/19/Colorful_pile_of_dumbbells_at_the_gym.jpg/960px-Colorful_pile_of_dumbbells_at_the_gym.jpg	2	2026-11-11 19:44:20.054006+00
6e5967ac-f02e-46f1-a70f-1573035bc7a1	dddddddd-dddd-dddd-dddd-000000000003	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/79/Colorful_pile_of_dumbbells_at_the_gym_2.jpg/960px-Colorful_pile_of_dumbbells_at_the_gym_2.jpg	3	2026-11-11 19:44:20.054006+00
30f7970c-4cfb-41c1-9d28-9f24d7883edb	eeeeeeee-eeee-eeee-eeee-000000000002	https://upload.wikimedia.org/wikipedia/commons/b/be/Dune_by_Frank_Herbert_first_edition_cover.jpg	0	2026-11-11 19:44:20.054006+00
1735b59e-df1e-4a7c-ac53-d68d0a374ae5	eeeeeeee-eeee-eeee-eeee-000000000002	https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d5/Dune_Messiah_%281969%29_cover.jpg/960px-Dune_Messiah_%281969%29_cover.jpg	1	2026-11-11 19:44:20.054006+00
ad56e298-5b78-419d-b259-a36448331444	eeeeeeee-eeee-eeee-eeee-000000000002	https://upload.wikimedia.org/wikipedia/commons/8/88/Map_of_Arrakis_from_Dune_first_edition_dust_jacket.jpg	2	2026-11-11 19:44:20.054006+00
67c26689-dc16-4ad5-8994-3bc39fbc761d	aaaaaaaa-aaaa-aaaa-aaaa-000000000008	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/0a/Bose_QuietComfort_25_Acoustic_Noise_Cancelling_Headphones_with_Carry_Case.jpg/960px-Bose_QuietComfort_25_Acoustic_Noise_Cancelling_Headphones_with_Carry_Case.jpg	0	2026-11-11 19:44:20.054006+00
66210a56-9d4b-4bbf-8f72-dd2722560e2c	aaaaaaaa-aaaa-aaaa-aaaa-000000000008	https://thumb.wikimedia.org/wikipedia/commons/thumb/9/90/ActiveSound_wireless_earbuds_by_Hykker_%28POJM200483%29.jpg/960px-ActiveSound_wireless_earbuds_by_Hykker_%28POJM200483%29.jpg	1	2026-11-11 19:44:20.054006+00
7eb31d30-79fd-45d2-beaa-e44b0c1d42e4	aaaaaaaa-aaaa-aaaa-aaaa-000000000008	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/04/Cycling_Amsterdam_03.jpg/960px-Cycling_Amsterdam_03.jpg	2	2026-11-11 19:44:20.054006+00
90559799-ed5d-4324-992d-9db097c12f8a	aaaaaaaa-aaaa-aaaa-aaaa-000000000008	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/73/Yamaha_TW-E3A_Earbuds_Customize%2C_Japan%3B_April_2021_%2801%29.jpg/960px-Yamaha_TW-E3A_Earbuds_Customize%2C_Japan%3B_April_2021_%2801%29.jpg	3	2026-11-11 19:44:20.054006+00
54350622-0754-4501-93ff-b058f83b7141	eeeeeeee-eeee-eeee-eeee-000000000003	https://upload.wikimedia.org/wikipedia/commons/0/09/Tara_Westover_receives_the_National_Humanities_Medal_on_March_21%2C_2023%2C_in_the_East_Room_of_the_White_House_-_P20230321ES-0780_%28cropped%29.jpg	0	2026-11-11 19:44:20.054006+00
223a4160-2313-4b4e-885f-e83ed016b6f6	eeeeeeee-eeee-eeee-eeee-000000000003	https://upload.wikimedia.org/wikipedia/commons/9/9b/Tara_Westover_photo.jpg	1	2026-11-11 19:44:20.054006+00
4afff5e8-93ca-437e-8b5d-a84605c9343b	eeeeeeee-eeee-eeee-eeee-000000000003	https://thumb.wikimedia.org/wikipedia/commons/thumb/d/dc/President_Joe_Biden_presents_the_2021_National_Humanities_Medal_to_Tara_Westover_at_the_National_Arts_and_Humanities_Medal_Ceremony%2C_Tuesday%2C_March_21%2C_2023%2C_in_the_East_Room_of_the_White_House.jpg/960px-thumbnail.jpg	2	2026-11-11 19:44:20.054006+00
352c7d3b-ff95-4966-82c2-ea9cd11a7838	eeeeeeee-eeee-eeee-eeee-000000000003	https://thumb.wikimedia.org/wikipedia/commons/thumb/4/42/Honorees_react_during_President_Joe_Biden%E2%80%99s_remarks_at_the_National_Arts_and_Humanities_Medal_Ceremony%2C_Tuesday%2C_March_21%2C_2023%2C_in_the_East_Room_of_the_White_House.jpg/960px-thumbnail.jpg	3	2026-11-11 19:44:20.054006+00
406aceed-e25f-417b-99d8-06dbb60942f5	aaaaaaaa-aaaa-aaaa-aaaa-000000000009	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/38/Seagate_ST33232A_hard_disk_inner_view.jpg/960px-Seagate_ST33232A_hard_disk_inner_view.jpg	0	2026-11-11 19:44:20.054006+00
a1fe21da-8638-4421-8d46-7e5d6a9f517e	aaaaaaaa-aaaa-aaaa-aaaa-000000000009	https://thumb.wikimedia.org/wikipedia/commons/thumb/8/8f/Hard_disk_head_crash.jpg/960px-Hard_disk_head_crash.jpg	1	2026-11-11 19:44:20.054006+00
ce6d535f-551b-49ac-8650-fcd754ccc7a2	aaaaaaaa-aaaa-aaaa-aaaa-000000000009	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/33/2019_Rozebrane_dyski_twarde.jpg/960px-2019_Rozebrane_dyski_twarde.jpg	2	2026-11-11 19:44:20.054006+00
636852cb-5f28-4328-9873-0b7bd18c9a22	aaaaaaaa-aaaa-aaaa-aaaa-000000000009	https://thumb.wikimedia.org/wikipedia/commons/thumb/9/97/35-Desktop-Hard-Drive.jpg/960px-35-Desktop-Hard-Drive.jpg	3	2026-11-11 19:44:20.054006+00
434965c8-8749-41da-ad5a-90480ae7f3fd	aaaaaaaa-aaaa-aaaa-aaaa-000000000010	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/7d/Samsung_T5_Portable_External_SSD_%28Solid_State_Drive%29_%2843544308485%29.jpg/960px-Samsung_T5_Portable_External_SSD_%28Solid_State_Drive%29_%2843544308485%29.jpg	0	2026-11-11 19:44:20.054006+00
40953705-4f60-4a9f-b4e6-ad017f9edfe7	aaaaaaaa-aaaa-aaaa-aaaa-000000000010	https://upload.wikimedia.org/wikipedia/commons/9/91/S.S.D.jpg	1	2026-11-11 19:44:20.054006+00
35d14098-5c6e-4b1b-bf83-f2106e421b95	aaaaaaaa-aaaa-aaaa-aaaa-000000000010	https://thumb.wikimedia.org/wikipedia/commons/thumb/4/42/2023_Dysk_przeno%C5%9Bny_Adata_Elite_SE760.jpg/960px-2023_Dysk_przeno%C5%9Bny_Adata_Elite_SE760.jpg	2	2026-11-11 19:44:20.054006+00
7337fd79-25c6-46f7-8afe-db04666cce45	aaaaaaaa-aaaa-aaaa-aaaa-000000000010	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/03/SanDisk_Extreme_Portable_SSD_-_1TB%2C_USB-C_%2841036158305%29.jpg/960px-SanDisk_Extreme_Portable_SSD_-_1TB%2C_USB-C_%2841036158305%29.jpg	3	2026-11-11 19:44:20.054006+00
6da58a98-4a67-4b51-9e28-c0289d209c49	bbbbbbbb-bbbb-bbbb-bbbb-000000000015	https://thumb.wikimedia.org/wikipedia/commons/thumb/4/4b/Floor_lamp_1_2016-07-06.jpg/960px-Floor_lamp_1_2016-07-06.jpg	0	2026-11-11 19:44:20.054006+00
8f8af4e2-eb48-4925-a387-6d486db23b7b	bbbbbbbb-bbbb-bbbb-bbbb-000000000015	https://thumb.wikimedia.org/wikipedia/commons/thumb/e/eb/Floor_Lamp_MET_DT8293.jpg/960px-Floor_Lamp_MET_DT8293.jpg	1	2026-11-11 19:44:20.054006+00
63f0ffad-d21e-459a-b4d1-f5344bc724ef	bbbbbbbb-bbbb-bbbb-bbbb-000000000015	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/31/Baltic_Rail_Terminal_Square_1A_2014-09_1411219087.JPG/960px-Baltic_Rail_Terminal_Square_1A_2014-09_1411219087.JPG	2	2026-11-11 19:44:20.054006+00
46483898-4aa9-4188-98af-1f171d3ad187	bbbbbbbb-bbbb-bbbb-bbbb-000000000015	https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b0/Vladimir_Kara-Murza_asv2024-09_img1.jpg/960px-Vladimir_Kara-Murza_asv2024-09_img1.jpg	3	2026-11-11 19:44:20.054006+00
cf92f725-fbda-471e-a8f8-34144f493881	dddddddd-dddd-dddd-dddd-000000000004	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/0c/Ryan_Valentine_scores.jpg/960px-Ryan_Valentine_scores.jpg	0	2026-11-11 19:44:20.054006+00
03edfcf2-bd66-4bf2-a0fd-6e3bc34d4d80	dddddddd-dddd-dddd-dddd-000000000004	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f2/Daniel_Sou%C4%8Dek_U21_Czech_Republic_vs_Greece_10-10-2019.jpg/960px-Daniel_Sou%C4%8Dek_U21_Czech_Republic_vs_Greece_10-10-2019.jpg	1	2026-11-11 19:44:20.054006+00
2ff2e0a1-dcdc-4952-9060-7f17da9a8951	dddddddd-dddd-dddd-dddd-000000000004	https://thumb.wikimedia.org/wikipedia/commons/thumb/2/21/Jakarta_old_football.jpg/960px-Jakarta_old_football.jpg	2	2026-11-11 19:44:20.054006+00
54571b49-3c98-4b99-b592-4a93c0e86f54	dddddddd-dddd-dddd-dddd-000000000004	https://thumb.wikimedia.org/wikipedia/commons/thumb/c/c5/Valais_Cup_2013_-_OM-FC_Porto_13-07-2013_-_Brice_Samba_en_extension.jpg/960px-Valais_Cup_2013_-_OM-FC_Porto_13-07-2013_-_Brice_Samba_en_extension.jpg	3	2026-11-11 19:44:20.054006+00
07a85962-ee87-435b-8bc7-ed871e0957b4	cccccccc-cccc-cccc-cccc-000000000005	https://thumb.wikimedia.org/wikipedia/commons/thumb/c/c8/Colour-blocking_with_burgundy_gloves_and_tights_to_contrast_with_an_amber_dress_and_beret.jpg/960px-Colour-blocking_with_burgundy_gloves_and_tights_to_contrast_with_an_amber_dress_and_beret.jpg	0	2026-11-11 19:44:20.054006+00
748e7338-fda8-4ac4-b1d2-3957abbceb16	cccccccc-cccc-cccc-cccc-000000000005	https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d9/Colour-blocking_with_burgundy_gloves_and_tights_to_contrast_with_an_amber_dress_and_beret_-_tights_are_not_visible.jpg/960px-Colour-blocking_with_burgundy_gloves_and_tights_to_contrast_with_an_amber_dress_and_beret_-_tights_are_not_visible.jpg	1	2026-11-11 19:44:20.054006+00
22726f49-86a4-480d-b688-16b70d7ef7e5	cccccccc-cccc-cccc-cccc-000000000005	https://thumb.wikimedia.org/wikipedia/commons/thumb/6/69/Leather_gloves_and_ear_warmers.jpg/960px-Leather_gloves_and_ear_warmers.jpg	2	2026-11-11 19:44:20.054006+00
cd8cf1aa-2966-4ce2-ba8b-ac6b0edec1ae	cccccccc-cccc-cccc-cccc-000000000005	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/36/Colour-blocking_with_burgundy_gloves_and_tights_to_contrast_with_an_amber_dress.jpg/960px-Colour-blocking_with_burgundy_gloves_and_tights_to_contrast_with_an_amber_dress.jpg	3	2026-11-11 19:44:20.054006+00
c0e09b7b-9f10-47f7-a762-163234f8959b	aaaaaaaa-aaaa-aaaa-aaaa-000000000011	https://thumb.wikimedia.org/wikipedia/commons/thumb/e/e2/HDMI_Cable_1.JPG/960px-HDMI_Cable_1.JPG	0	2026-11-11 19:44:20.054006+00
b4a02384-1bb0-4cf1-9845-0837578f3603	aaaaaaaa-aaaa-aaaa-aaaa-000000000011	https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a2/Cable_TV_Box_Connectors_-_RCA_USB_HDMI_Cable_Input_Plugs.jpg/960px-Cable_TV_Box_Connectors_-_RCA_USB_HDMI_Cable_Input_Plugs.jpg	1	2026-11-11 19:44:20.054006+00
1192a402-9251-45dc-87f3-bb52e07db29e	aaaaaaaa-aaaa-aaaa-aaaa-000000000011	https://thumb.wikimedia.org/wikipedia/commons/thumb/c/c2/HDMI-Connector.jpg/960px-HDMI-Connector.jpg	2	2026-11-11 19:44:20.054006+00
10a86d5a-50ac-4816-8254-2ecb1a9eea52	aaaaaaaa-aaaa-aaaa-aaaa-000000000011	https://thumb.wikimedia.org/wikipedia/commons/thumb/9/90/HDMI_Cable.JPG/960px-HDMI_Cable.JPG	3	2026-11-11 19:44:20.054006+00
06a5d0fc-36fb-4062-ac63-6086c8d3a047	cccccccc-cccc-cccc-cccc-000000000006	https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b4/Madras_plaid_baseball_cap_by_Polo_Ralph_Lauren.jpg/960px-Madras_plaid_baseball_cap_by_Polo_Ralph_Lauren.jpg	0	2026-11-11 19:44:20.054006+00
b7536be9-1702-49de-b806-be26490e3178	cccccccc-cccc-cccc-cccc-000000000006	https://upload.wikimedia.org/wikipedia/commons/e/eb/Baseball_cap.jpg	1	2026-11-11 19:44:20.054006+00
5348f6e1-0274-41ef-a840-444d5b5b8b48	cccccccc-cccc-cccc-cccc-000000000006	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/7e/Baseball_Cap_-_NSA_%28National_Security_Agency%29_-_United_States_of_America.jpg/960px-Baseball_Cap_-_NSA_%28National_Security_Agency%29_-_United_States_of_America.jpg	2	2026-11-11 19:44:20.054006+00
19cdf365-9984-42b9-a1fc-92e59ccb03f9	cccccccc-cccc-cccc-cccc-000000000006	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/06/Baseball_Cap_PaRacorD_%C2%AE.jpg/960px-Baseball_Cap_PaRacorD_%C2%AE.jpg	3	2026-11-11 19:44:20.054006+00
3df4d561-27b1-410e-a7d1-14a614600abf	aaaaaaaa-aaaa-aaaa-aaaa-000000000012	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/78/Gaming_computers_%281%29.jpg/960px-Gaming_computers_%281%29.jpg	0	2026-11-11 19:44:20.054006+00
cc6a7086-a0ea-4a03-b4e8-63c53b39a30e	aaaaaaaa-aaaa-aaaa-aaaa-000000000012	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f9/Gaming_computer_with_bat_decals.jpg/960px-Gaming_computer_with_bat_decals.jpg	1	2026-11-11 19:44:20.054006+00
7f23c70b-0ed3-4dd4-9dc9-f537e81fa680	aaaaaaaa-aaaa-aaaa-aaaa-000000000012	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/7a/RGB_gaming_headset_on_desk_with_ambient_lighting.jpg/960px-RGB_gaming_headset_on_desk_with_ambient_lighting.jpg	2	2026-11-11 19:44:20.054006+00
702f35bd-c4a2-4003-bc8e-22cf51cda5ed	aaaaaaaa-aaaa-aaaa-aaaa-000000000012	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/31/Gaming_PC-Setup_-_Astaroth-_The_Completed_System.jpg/960px-Gaming_PC-Setup_-_Astaroth-_The_Completed_System.jpg	3	2026-11-11 19:44:20.054006+00
d83b6200-9742-4d7e-a0db-fe678ed67dfe	aaaaaaaa-aaaa-aaaa-aaaa-000000000013	https://thumb.wikimedia.org/wikipedia/commons/thumb/5/56/2023_S%C5%82uchawki_Sony_WI-XB400_%281%29.jpg/960px-2023_S%C5%82uchawki_Sony_WI-XB400_%281%29.jpg	0	2026-11-11 19:44:20.054006+00
d7e8078a-83aa-42b9-911b-f556f5dca843	aaaaaaaa-aaaa-aaaa-aaaa-000000000013	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/32/2023_S%C5%82uchawki_Sony_WI-XB400_%283%29.jpg/960px-2023_S%C5%82uchawki_Sony_WI-XB400_%283%29.jpg	1	2026-11-11 19:44:20.054006+00
4601f481-00c5-4fc8-81d2-00a456699931	aaaaaaaa-aaaa-aaaa-aaaa-000000000013	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/0a/Bose_QuietComfort_25_Acoustic_Noise_Cancelling_Headphones_with_Carry_Case.jpg/960px-Bose_QuietComfort_25_Acoustic_Noise_Cancelling_Headphones_with_Carry_Case.jpg	2	2026-11-11 19:44:20.054006+00
58b28aa1-34b5-4ddb-9e16-525fa6ec4b7a	aaaaaaaa-aaaa-aaaa-aaaa-000000000013	https://thumb.wikimedia.org/wikipedia/commons/thumb/4/42/2023_S%C5%82uchawki_Sony_WI-XB400_%282%29.jpg/960px-2023_S%C5%82uchawki_Sony_WI-XB400_%282%29.jpg	3	2026-11-11 19:44:20.054006+00
49cfd10a-c47b-417b-9be7-6ffd299b5364	dddddddd-dddd-dddd-dddd-000000000005	https://thumb.wikimedia.org/wikipedia/commons/thumb/8/81/Jack_Wolfskin%2C_OutDoor_2018%2C_Friedrichshafen_%281X7A0425%29.jpg/960px-Jack_Wolfskin%2C_OutDoor_2018%2C_Friedrichshafen_%281X7A0425%29.jpg	0	2026-11-11 19:44:20.054006+00
3f6914f4-1dd8-42c7-b274-28cb92211109	dddddddd-dddd-dddd-dddd-000000000005	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/7b/Old_friends.jpg/960px-Old_friends.jpg	1	2026-11-11 19:44:20.054006+00
7955825e-cb77-4e01-a8ef-3341c30a3b7a	dddddddd-dddd-dddd-dddd-000000000005	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/08/Mammut%2C_OutDoor_2018%2C_Friedrichshafen_%281X7A0213%29.jpg/960px-Mammut%2C_OutDoor_2018%2C_Friedrichshafen_%281X7A0213%29.jpg	2	2026-11-11 19:44:20.054006+00
7cf33dae-b746-4e60-a0af-076453c56139	dddddddd-dddd-dddd-dddd-000000000005	https://thumb.wikimedia.org/wikipedia/commons/thumb/2/29/Hiking_shoes_Lowa.jpg/960px-Hiking_shoes_Lowa.jpg	3	2026-11-11 19:44:20.054006+00
8c55bec5-9436-4860-a030-2a3b4bd6b90c	cccccccc-cccc-cccc-cccc-000000000007	https://thumb.wikimedia.org/wikipedia/commons/thumb/a/ad/Hooded_crow_%E2%80%93_Wrona_zwyczajna_%E2%80%93_Corvus_corone_cornix.jpg/960px-Hooded_crow_%E2%80%93_Wrona_zwyczajna_%E2%80%93_Corvus_corone_cornix.jpg	0	2026-11-11 19:44:20.054006+00
c652dc44-930e-4422-8387-a2cd3e600285	cccccccc-cccc-cccc-cccc-000000000007	https://thumb.wikimedia.org/wikipedia/commons/thumb/c/c2/Hoodie_m7agar.jpg/960px-Hoodie_m7agar.jpg	1	2026-11-11 19:44:20.054006+00
3bd169d1-8b51-4493-a8b4-ceb14b7a7cc9	cccccccc-cccc-cccc-cccc-000000000007	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/06/%22Vaccinated%22_hoodie_on_sale%2C_reference_to_COVID-19.jpg/960px-%22Vaccinated%22_hoodie_on_sale%2C_reference_to_COVID-19.jpg	2	2026-11-11 19:44:20.054006+00
a045ca27-1944-4d19-a111-7dbc6a7482e2	cccccccc-cccc-cccc-cccc-000000000007	https://upload.wikimedia.org/wikipedia/commons/1/16/Young_man_with_hoodie.jpg	3	2026-11-11 19:44:20.054006+00
163fd3fe-de86-4f90-ad38-b433d7a71a1e	bbbbbbbb-bbbb-bbbb-bbbb-000000000016	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/31/Instant_Pot_DUO60_pressure_cooker.jpg/960px-Instant_Pot_DUO60_pressure_cooker.jpg	0	2026-11-11 19:44:20.054006+00
ed8f842f-eb0b-418f-9638-1b2e420df7ab	bbbbbbbb-bbbb-bbbb-bbbb-000000000016	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/3b/Pressure_multicooker.jpg/500px-Pressure_multicooker.jpg	1	2026-11-11 19:44:20.054006+00
df474317-18ff-42c3-a5ee-3cef968cebd7	bbbbbbbb-bbbb-bbbb-bbbb-000000000016	https://upload.wikimedia.org/wikipedia/commons/c/c7/Pressure_valve_of_multicooker.jpg	2	2026-11-11 19:44:20.054006+00
3257d110-ced4-4b71-88cb-d8e80d6282b2	bbbbbbbb-bbbb-bbbb-bbbb-000000000016	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/36/The_Ladies%27_home_journal_%281948%29_%2814582013530%29.jpg/960px-The_Ladies%27_home_journal_%281948%29_%2814582013530%29.jpg	3	2026-11-11 19:44:20.054006+00
3af48ff6-037f-41dd-84b5-936027dc06ef	cccccccc-cccc-cccc-cccc-000000000008	https://upload.wikimedia.org/wikipedia/commons/d/df/MA-1_Jacket_in_petrol.jpg	0	2026-11-11 19:44:20.054006+00
befc620c-0e42-4745-a48a-b1ffd8c08e1d	cccccccc-cccc-cccc-cccc-000000000008	https://thumb.wikimedia.org/wikipedia/commons/thumb/2/2a/20180502_Engle_jacket_Cosmosphere.jpg/960px-20180502_Engle_jacket_Cosmosphere.jpg	1	2026-11-11 19:44:20.054006+00
9f34b903-bff2-4660-b2c4-027c16266c5c	cccccccc-cccc-cccc-cccc-000000000008	https://thumb.wikimedia.org/wikipedia/commons/thumb/c/cf/20180502_Conrad_jacket_Cosmosphere.jpg/960px-20180502_Conrad_jacket_Cosmosphere.jpg	2	2026-11-11 19:44:20.054006+00
8f64addf-5f2f-4e0d-ae6d-ee7f31f454e1	cccccccc-cccc-cccc-cccc-000000000008	https://thumb.wikimedia.org/wikipedia/commons/thumb/2/2e/BHC-Fliegerjacke.jpg/960px-BHC-Fliegerjacke.jpg	3	2026-11-11 19:44:20.054006+00
10ffaccf-4bb9-498b-bc73-7614b5092d32	cccccccc-cccc-cccc-cccc-000000000009	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/30/Max_Giesinger_-_2021-09-12_-_Mannheim_-_Sven_Mandel_-_1D_X_MK_II_-_0229_-_B70I3683_%28re-developed%29.jpg/960px-Max_Giesinger_-_2021-09-12_-_Mannheim_-_Sven_Mandel_-_1D_X_MK_II_-_0229_-_B70I3683_%28re-developed%29.jpg	0	2026-11-11 19:44:20.054006+00
1acc1d36-4b3a-428d-a042-3aff7d1829c6	cccccccc-cccc-cccc-cccc-000000000009	https://thumb.wikimedia.org/wikipedia/commons/thumb/1/14/Child_bicycle_Managing_ports_Altagracia.jpg/960px-Child_bicycle_Managing_ports_Altagracia.jpg	1	2026-11-11 19:44:20.054006+00
5a96314b-d3dc-46e3-80a8-a5e921f0e5ca	cccccccc-cccc-cccc-cccc-000000000009	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/fd/Reprise_jeans_envers.jpg/960px-Reprise_jeans_envers.jpg	2	2026-11-11 19:44:20.054006+00
62444f10-e401-44a3-a68d-6b9d7520f87d	cccccccc-cccc-cccc-cccc-000000000009	https://upload.wikimedia.org/wikipedia/commons/0/02/Blue_Jeans_from_the_rear.jpg	3	2026-11-11 19:44:20.054006+00
f3ed9ad7-1aaa-4f7e-ae7d-42740c94f793	dddddddd-dddd-dddd-dddd-000000000006	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/77/Ghanaian_kid_%28skipping_rope%29_02.jpg/960px-Ghanaian_kid_%28skipping_rope%29_02.jpg	0	2026-11-11 19:44:20.054006+00
b2ad6b8c-f7b4-4f6a-ac98-af019518c166	dddddddd-dddd-dddd-dddd-000000000006	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/fd/Ghanaian_kid_%28skipping_rope%29_03.jpg/960px-Ghanaian_kid_%28skipping_rope%29_03.jpg	1	2026-11-11 19:44:20.054006+00
c97d2737-e677-4c36-a78e-687791036008	dddddddd-dddd-dddd-dddd-000000000006	https://thumb.wikimedia.org/wikipedia/commons/thumb/5/5f/Skipping_rope_agesuit_altersanzug_alterssimulationsanzug_ageexplorer_gundolf_meyer-hentschel.jpg/960px-Skipping_rope_agesuit_altersanzug_alterssimulationsanzug_ageexplorer_gundolf_meyer-hentschel.jpg	2	2026-11-11 19:44:20.054006+00
4fb7c902-32a4-40d1-a3f1-3c2dd3e7ce3e	dddddddd-dddd-dddd-dddd-000000000006	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/3e/Jump_Rope_Field.jpg/960px-Jump_Rope_Field.jpg	3	2026-11-11 19:44:20.054006+00
d14833dd-d8ab-412b-ae1e-2a0f2b2dca58	dddddddd-dddd-dddd-dddd-000000000007	https://thumb.wikimedia.org/wikipedia/commons/thumb/8/87/Kettlebell_truebalance_romeike.jpg/960px-Kettlebell_truebalance_romeike.jpg	0	2026-11-11 19:44:20.054006+00
278d0b80-5160-4f54-b1e4-72301af6ebb2	dddddddd-dddd-dddd-dddd-000000000007	https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d9/Kettlebell_Dead_Snatch_Squat_2_-_Pull.jpg/960px-Kettlebell_Dead_Snatch_Squat_2_-_Pull.jpg	1	2026-11-11 19:44:20.054006+00
09c65c1d-6fc6-4388-9d7f-89c1533a0d84	dddddddd-dddd-dddd-dddd-000000000007	https://thumb.wikimedia.org/wikipedia/commons/thumb/e/e5/Kettlebell_Bent_Press.jpg/960px-Kettlebell_Bent_Press.jpg	2	2026-11-11 19:44:20.054006+00
5a1c471b-c745-4e3c-b7ad-7afe891997cd	dddddddd-dddd-dddd-dddd-000000000007	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/77/12_KG_kettlebell_at_Results_Gym.jpg/960px-12_KG_kettlebell_at_Results_Gym.jpg	3	2026-11-11 19:44:20.054006+00
95d1e0c1-c1fc-4ba4-86e1-0ef7f929c83d	bbbbbbbb-bbbb-bbbb-bbbb-000000000017	https://thumb.wikimedia.org/wikipedia/commons/thumb/2/20/Leather_knife_case_and_a_set_of_knives_by_Les_Cuirs_d%27Agathe_and_La_Forge_du_Lion_du_Nord_%28DSC07523%29.jpg/960px-Leather_knife_case_and_a_set_of_knives_by_Les_Cuirs_d%27Agathe_and_La_Forge_du_Lion_du_Nord_%28DSC07523%29.jpg	0	2026-11-11 19:44:20.054006+00
32c9aa62-26b8-4526-85a7-a89cb47e97a8	bbbbbbbb-bbbb-bbbb-bbbb-000000000017	https://thumb.wikimedia.org/wikipedia/commons/thumb/5/59/Leather_knife_case_and_a_set_of_knives_by_Les_Cuirs_d%27Agathe_and_La_Forge_du_Lion_du_Nord_%28DSC07517%29.jpg/960px-Leather_knife_case_and_a_set_of_knives_by_Les_Cuirs_d%27Agathe_and_La_Forge_du_Lion_du_Nord_%28DSC07517%29.jpg	1	2026-11-11 19:44:20.054006+00
0145eb70-de31-439d-8f6b-3b45623f250d	bbbbbbbb-bbbb-bbbb-bbbb-000000000017	https://thumb.wikimedia.org/wikipedia/commons/thumb/9/98/Leather_knife_case_and_a_set_of_knives_by_Les_Cuirs_d%27Agathe_and_La_Forge_du_Lion_du_Nord_%28DSC07518%29.jpg/960px-Leather_knife_case_and_a_set_of_knives_by_Les_Cuirs_d%27Agathe_and_La_Forge_du_Lion_du_Nord_%28DSC07518%29.jpg	2	2026-11-11 19:44:20.054006+00
3e00cd18-17b1-4485-9acc-0edc466b1fd4	bbbbbbbb-bbbb-bbbb-bbbb-000000000017	https://thumb.wikimedia.org/wikipedia/commons/thumb/e/ef/Kitchen_utensils-01.jpg/960px-Kitchen_utensils-01.jpg	3	2026-11-11 19:44:20.054006+00
02c4dd9f-14d0-4167-a541-23a0be3237de	aaaaaaaa-aaaa-aaaa-aaaa-000000000014	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/0e/IBM_Thinkpad_R51.jpg/960px-IBM_Thinkpad_R51.jpg	0	2026-11-11 19:44:20.054006+00
4729c762-ec5e-48c9-bf7f-cca7a6711479	aaaaaaaa-aaaa-aaaa-aaaa-000000000014	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/37/Schenker_VIA14_Laptop_asv2021-01.jpg/960px-Schenker_VIA14_Laptop_asv2021-01.jpg	1	2026-11-11 19:44:20.054006+00
476c11d1-3db7-4c7a-9b42-e71deb27ce36	aaaaaaaa-aaaa-aaaa-aaaa-000000000014	https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d6/HP_Pavilion_Computer_laptop_keyboard_closeup.jpg/960px-HP_Pavilion_Computer_laptop_keyboard_closeup.jpg	2	2026-11-11 19:44:20.054006+00
cab6e072-2685-4dc7-a01f-ef21bec1fc4f	aaaaaaaa-aaaa-aaaa-aaaa-000000000014	https://thumb.wikimedia.org/wikipedia/commons/thumb/b/bd/Laptop_computer.jpeg/960px-Laptop_computer.jpeg	3	2026-11-11 19:44:20.054006+00
9b84752c-66bd-42f5-8fe3-56108d4c3fde	aaaaaaaa-aaaa-aaaa-aaaa-000000000015	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/0e/IBM_Thinkpad_R51.jpg/960px-IBM_Thinkpad_R51.jpg	0	2026-11-11 19:44:20.054006+00
7a0c7df2-0de6-442f-ab45-93e1546a5a04	aaaaaaaa-aaaa-aaaa-aaaa-000000000015	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/37/Schenker_VIA14_Laptop_asv2021-01.jpg/960px-Schenker_VIA14_Laptop_asv2021-01.jpg	1	2026-11-11 19:44:20.054006+00
2028e412-90f7-4b4e-93df-a1911764690d	aaaaaaaa-aaaa-aaaa-aaaa-000000000015	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/06/Notebook_computer.jpeg/960px-Notebook_computer.jpeg	2	2026-11-11 19:44:20.054006+00
e874b34a-3680-4786-a4df-bba2d7490042	aaaaaaaa-aaaa-aaaa-aaaa-000000000015	https://thumb.wikimedia.org/wikipedia/commons/thumb/8/88/Notebook_computer.jpg/960px-Notebook_computer.jpg	3	2026-11-11 19:44:20.054006+00
f42d53db-5d4b-4229-b827-54f3efa5fb6c	aaaaaaaa-aaaa-aaaa-aaaa-000000000016	https://thumb.wikimedia.org/wikipedia/commons/thumb/1/14/Laptop_cooler.JPG/960px-Laptop_cooler.JPG	0	2026-11-11 19:44:20.054006+00
25d44b80-474d-4bb4-91d2-4ce5bd633362	aaaaaaaa-aaaa-aaaa-aaaa-000000000016	https://thumb.wikimedia.org/wikipedia/commons/thumb/c/cd/Passive_Laptop_cooler.jpg/960px-Passive_Laptop_cooler.jpg	1	2026-11-11 19:44:20.054006+00
2db8e14c-edcb-4778-a603-97ff27485db5	aaaaaaaa-aaaa-aaaa-aaaa-000000000016	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/06/Laptop_coolers.jpg/960px-Laptop_coolers.jpg	2	2026-11-11 19:44:20.054006+00
79c75b26-fbaf-4549-94d1-a2f6dd429aca	aaaaaaaa-aaaa-aaaa-aaaa-000000000016	https://thumb.wikimedia.org/wikipedia/commons/thumb/e/e5/Small_dust_micro_macro_photography_on_cooling_fan_of_laptop_cooler.jpg/960px-Small_dust_micro_macro_photography_on_cooling_fan_of_laptop_cooler.jpg	3	2026-11-11 19:44:20.054006+00
a985d712-48ff-48a7-ae64-50aa8a7fcc7c	aaaaaaaa-aaaa-aaaa-aaaa-000000000017	https://thumb.wikimedia.org/wikipedia/commons/thumb/5/53/Asus_x21_ultrabook.jpg/960px-Asus_x21_ultrabook.jpg	0	2026-11-11 19:44:20.054006+00
afde61ae-ec02-405c-af08-3b5a851e7b42	aaaaaaaa-aaaa-aaaa-aaaa-000000000017	https://upload.wikimedia.org/wikipedia/commons/a/a6/Laptop_and_Ultrabook_Computers_in_Moscow_Retail_Store.jpg	1	2026-11-11 19:44:20.054006+00
87d5d20e-3d40-4a97-9b12-e0170dcb2910	aaaaaaaa-aaaa-aaaa-aaaa-000000000017	https://thumb.wikimedia.org/wikipedia/commons/thumb/4/40/CES_2012_-_Samsung_Ultrabook_%286937823349%29.jpg/960px-CES_2012_-_Samsung_Ultrabook_%286937823349%29.jpg	2	2026-11-11 19:44:20.054006+00
5a174aa0-2124-448a-9d0a-9a4c6e65c53e	aaaaaaaa-aaaa-aaaa-aaaa-000000000017	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/fe/Adjusting_Ultrabook_Setting_Using_Touchscreen_%28cropped%29.jpg/960px-Adjusting_Ultrabook_Setting_Using_Touchscreen_%28cropped%29.jpg	3	2026-11-11 19:44:20.054006+00
580f4f62-a1c3-4307-8a3d-f3f148727c2a	aaaaaaaa-aaaa-aaaa-aaaa-000000000018	https://thumb.wikimedia.org/wikipedia/commons/thumb/e/ec/Kurzweil_Synthesizer_and_MIDI_Keyboard_Controller_PC3K6.jpg/960px-Kurzweil_Synthesizer_and_MIDI_Keyboard_Controller_PC3K6.jpg	0	2026-11-11 19:44:20.054006+00
7285e37b-6f29-454d-b532-883d5dde1729	aaaaaaaa-aaaa-aaaa-aaaa-000000000018	https://thumb.wikimedia.org/wikipedia/commons/thumb/a/ac/Edirol_PCR-300_MIDI_Keyboard_Controller_%28USB_%2B_MIDI%29_%26_PRiMAX_MIDISTORM_Music_Keyboard_M-4900.jpg/960px-Edirol_PCR-300_MIDI_Keyboard_Controller_%28USB_%2B_MIDI%29_%26_PRiMAX_MIDISTORM_Music_Keyboard_M-4900.jpg	1	2026-11-11 19:44:20.054006+00
b6cb1f87-a51c-49b7-bf00-02d0cf7de756	aaaaaaaa-aaaa-aaaa-aaaa-000000000018	https://thumb.wikimedia.org/wikipedia/commons/thumb/8/88/Alesis_Q25_MIDI_Controller.jpg/960px-Alesis_Q25_MIDI_Controller.jpg	2	2026-11-11 19:44:20.054006+00
50e563b0-4e56-474b-8e7f-6ca67bf01676	aaaaaaaa-aaaa-aaaa-aaaa-000000000018	https://thumb.wikimedia.org/wikipedia/commons/thumb/8/84/Steinberg_CC121_Cubase_control_surface_and_M-Audio_Oxygen_MIDI_keyboard_controller_%282013-03-02_by_martinezdj%29.jpg/960px-Steinberg_CC121_Cubase_control_surface_and_M-Audio_Oxygen_MIDI_keyboard_controller_%282013-03-02_by_martinezdj%29.jpg	3	2026-11-11 19:44:20.054006+00
f75c310b-d46c-452f-a083-5ec9a75096f3	bbbbbbbb-bbbb-bbbb-bbbb-000000000018	https://thumb.wikimedia.org/wikipedia/commons/thumb/1/11/Miranda_en_la_Carraca_by_Arturo_Michelena.jpg/960px-Miranda_en_la_Carraca_by_Arturo_Michelena.jpg	0	2026-11-11 19:44:20.054006+00
3d0fe975-2498-419d-9991-38723275cda7	bbbbbbbb-bbbb-bbbb-bbbb-000000000018	https://thumb.wikimedia.org/wikipedia/commons/thumb/c/c3/Nebelwerfer_and_Land_Mattress.jpg/960px-Nebelwerfer_and_Land_Mattress.jpg	1	2026-11-11 19:44:20.054006+00
57b81195-08c6-4c90-b700-2f9d90ef3628	bbbbbbbb-bbbb-bbbb-bbbb-000000000018	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/79/Land_Mattress_plaque.jpg/960px-Land_Mattress_plaque.jpg	2	2026-11-11 19:44:20.054006+00
898010a1-d4af-4c44-8033-cd7274d9afce	bbbbbbbb-bbbb-bbbb-bbbb-000000000018	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/76/Mattress_in_Coventry_July_29_2021.jpg/960px-Mattress_in_Coventry_July_29_2021.jpg	3	2026-11-11 19:44:20.054006+00
d8ab33a5-6b20-4862-ba26-f7bcd0c4347c	11111111-1111-4111-8111-111111111111	https://thumb.wikimedia.org/wikipedia/commons/thumb/5/5a/Mechanical_Keyboard.jpg/960px-Mechanical_Keyboard.jpg	0	2026-11-11 19:44:20.054006+00
2d2ad5f9-20ca-494f-b298-1ec7baf3ae90	11111111-1111-4111-8111-111111111111	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/7f/Beautiful_Mechanical_Keyboard.jpg/960px-Beautiful_Mechanical_Keyboard.jpg	1	2026-11-11 19:44:20.054006+00
492b623d-57fd-4e44-892d-30c33a79b726	11111111-1111-4111-8111-111111111111	https://upload.wikimedia.org/wikipedia/commons/4/4b/Microcenter_keyboard_isle.jpg	2	2026-11-11 19:44:20.054006+00
15ef4c92-0c4f-4d2c-b1e0-e52e9dd0dea7	11111111-1111-4111-8111-111111111111	https://thumb.wikimedia.org/wikipedia/commons/thumb/2/2e/Mechanical_keyboard_caps_at_Comic_Fiesta_2024.jpg/960px-Mechanical_keyboard_caps_at_Comic_Fiesta_2024.jpg	3	2026-11-11 19:44:20.054006+00
2569f342-725f-4e96-a6f9-a6fbe8f00915	aaaaaaaa-aaaa-aaaa-aaaa-000000000019	https://thumb.wikimedia.org/wikipedia/commons/thumb/5/5a/Mechanical_Keyboard.jpg/960px-Mechanical_Keyboard.jpg	0	2026-11-11 19:44:20.054006+00
82df5d2b-e2ad-475e-909b-97b801472d17	aaaaaaaa-aaaa-aaaa-aaaa-000000000019	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/7f/Beautiful_Mechanical_Keyboard.jpg/960px-Beautiful_Mechanical_Keyboard.jpg	1	2026-11-11 19:44:20.054006+00
23d10240-b2d2-4b9b-a11b-d59be92c75f3	aaaaaaaa-aaaa-aaaa-aaaa-000000000019	https://upload.wikimedia.org/wikipedia/commons/4/4b/Microcenter_keyboard_isle.jpg	2	2026-11-11 19:44:20.054006+00
5f2f3074-6955-48cc-ac6c-c0c2e35a7573	aaaaaaaa-aaaa-aaaa-aaaa-000000000019	https://thumb.wikimedia.org/wikipedia/commons/thumb/2/2e/Mechanical_keyboard_caps_at_Comic_Fiesta_2024.jpg/960px-Mechanical_keyboard_caps_at_Comic_Fiesta_2024.jpg	3	2026-11-11 19:44:20.054006+00
7539cf58-a745-4560-891d-2f62853feca5	dddddddd-dddd-dddd-dddd-000000000008	https://thumb.wikimedia.org/wikipedia/commons/thumb/8/86/Medicine_Ball_2_2017-01-13.jpg/960px-Medicine_Ball_2_2017-01-13.jpg	0	2026-11-11 19:44:20.054006+00
690eaffc-2c03-4a62-9d2a-2edf7ffea07a	dddddddd-dddd-dddd-dddd-000000000008	https://thumb.wikimedia.org/wikipedia/commons/thumb/d/dd/Ballistic_training_medicine_ball_throw.jpg/960px-Ballistic_training_medicine_ball_throw.jpg	1	2026-11-11 19:44:20.054006+00
ea792652-a9fc-4448-826e-1e017b7f0f43	dddddddd-dddd-dddd-dddd-000000000008	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/70/Medicine_Ball_Plank.jpg/960px-Medicine_Ball_Plank.jpg	2	2026-11-11 19:44:20.054006+00
8c8e4a09-04f8-4140-a336-e2e148e6a455	dddddddd-dddd-dddd-dddd-000000000008	https://thumb.wikimedia.org/wikipedia/commons/thumb/5/50/MEDICINE-BALL-EXERCISES-FOR-MEN-1.jpg/960px-MEDICINE-BALL-EXERCISES-FOR-MEN-1.jpg	3	2026-11-11 19:44:20.054006+00
37640a1c-1a92-4205-975d-f8ff15eb7516	aaaaaaaa-aaaa-aaaa-aaaa-000000000020	https://thumb.wikimedia.org/wikipedia/commons/thumb/e/e2/Rode_NT-USB.jpg/960px-Rode_NT-USB.jpg	0	2026-11-11 19:44:20.054006+00
6899b938-3941-42a5-8134-4760d72f595b	aaaaaaaa-aaaa-aaaa-aaaa-000000000020	https://thumb.wikimedia.org/wikipedia/commons/thumb/c/cf/Rode_NT-USB_Mini_microphone_003.jpg/960px-Rode_NT-USB_Mini_microphone_003.jpg	1	2026-11-11 19:44:20.054006+00
c6de3757-dc56-4b39-8e72-386d3aa4c7ab	aaaaaaaa-aaaa-aaaa-aaaa-000000000020	https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b0/Rode_NT-USB_Mini_microphone_001.jpg/960px-Rode_NT-USB_Mini_microphone_001.jpg	2	2026-11-11 19:44:20.054006+00
74c9c2cb-d873-494c-9455-aaff60fb0b74	aaaaaaaa-aaaa-aaaa-aaaa-000000000020	https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a7/Rode_NT-USB_Mini_microphone_002.jpg/960px-Rode_NT-USB_Mini_microphone_002.jpg	3	2026-11-11 19:44:20.054006+00
a1790de0-602a-4bf0-b45f-8f2349933351	bbbbbbbb-bbbb-bbbb-bbbb-000000000019	https://thumb.wikimedia.org/wikipedia/commons/thumb/b/bb/Microwave_Oven.jpg/960px-Microwave_Oven.jpg	0	2026-11-11 19:44:20.054006+00
0acb05ea-f0fe-43d0-85b1-09d10fb2493d	bbbbbbbb-bbbb-bbbb-bbbb-000000000019	https://thumb.wikimedia.org/wikipedia/commons/thumb/9/95/Panasonic_MICROWAVE_OVEN_NN-GM333W.jpg/960px-Panasonic_MICROWAVE_OVEN_NN-GM333W.jpg	1	2026-11-11 19:44:20.054006+00
ae97ffed-276e-452c-80f2-153ffd296c32	bbbbbbbb-bbbb-bbbb-bbbb-000000000019	https://upload.wikimedia.org/wikipedia/commons/1/12/Microwave_oven_flashon.jpg	2	2026-11-11 19:44:20.054006+00
1aa05dd8-d971-4b10-a9b0-0a69a36dacb6	bbbbbbbb-bbbb-bbbb-bbbb-000000000019	https://thumb.wikimedia.org/wikipedia/commons/thumb/8/8f/Panasonic_MICROWAVE_OVEN_NN-GM333W_%282%29.jpg/960px-Panasonic_MICROWAVE_OVEN_NN-GM333W_%282%29.jpg	3	2026-11-11 19:44:20.054006+00
46d4d79b-de64-49cd-a676-6a06462566b6	aaaaaaaa-aaaa-aaaa-aaaa-000000000021	https://thumb.wikimedia.org/wikipedia/commons/thumb/c/c3/Amiga500_system.jpg/960px-Amiga500_system.jpg	0	2026-11-11 19:44:20.054006+00
08c7c474-f416-4774-8637-11c522829f50	aaaaaaaa-aaaa-aaaa-aaaa-000000000021	https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a8/Atari_800_XL_home_computer_with_monitor_and_tape_program_recorder_XC12.jpg/960px-Atari_800_XL_home_computer_with_monitor_and_tape_program_recorder_XC12.jpg	1	2026-11-11 19:44:20.054006+00
bd900df0-8313-4d12-98c3-d540491ea44f	aaaaaaaa-aaaa-aaaa-aaaa-000000000021	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f3/Computer_monitor_screen_image_simulated.jpg/960px-Computer_monitor_screen_image_simulated.jpg	2	2026-11-11 19:44:20.054006+00
e703b390-4b8a-41cf-909a-0f9396e026d0	aaaaaaaa-aaaa-aaaa-aaaa-000000000021	https://thumb.wikimedia.org/wikipedia/commons/thumb/9/9d/Smaky_100_IMG_4149.jpg/960px-Smaky_100_IMG_4149.jpg	3	2026-11-11 19:44:20.054006+00
68de0721-3ddd-4537-bc1c-90fdccba5e5a	aaaaaaaa-aaaa-aaaa-aaaa-000000000022	https://thumb.wikimedia.org/wikipedia/commons/thumb/1/18/DiRT_Rally_%E3%82%924K_HD%E3%81%A7%E3%83%99%E3%83%B3%E3%83%81%E3%83%9E%E3%83%BC%E3%82%AF_-Lenovo_ThinkCentre_M75q-1_Tiny_%2B_135W_AC_Adapter-_%2850065100496%29.jpg/960px-DiRT_Rally_%E3%82%924K_HD%E3%81%A7%E3%83%99%E3%83%B3%E3%83%81%E3%83%9E%E3%83%BC%E3%82%AF_-Lenovo_ThinkCentre_M75q-1_Tiny_%2B_135W_AC_Adapter-_%2850065100496%29.jpg	0	2026-11-11 19:44:20.054006+00
aa56de2b-f7f2-41fa-8eb8-6bca11276aae	aaaaaaaa-aaaa-aaaa-aaaa-000000000022	https://thumb.wikimedia.org/wikipedia/commons/thumb/2/27/Belleville_Personal_Computer%2C_8080%2C_2MHz%2C_1980._DSC_0663_%286685119805%29.jpg/960px-Belleville_Personal_Computer%2C_8080%2C_2MHz%2C_1980._DSC_0663_%286685119805%29.jpg	1	2026-11-11 19:44:20.054006+00
cae0883e-493e-4d2f-8c9c-3f1d222f6f65	aaaaaaaa-aaaa-aaaa-aaaa-000000000022	https://thumb.wikimedia.org/wikipedia/commons/thumb/2/29/Home_computer_con_interfaccia_a_riga_di_comando_-_Museo_scienza_tecnologia_Milano_09238.jpg/960px-Home_computer_con_interfaccia_a_riga_di_comando_-_Museo_scienza_tecnologia_Milano_09238.jpg	2	2026-11-11 19:44:20.054006+00
1e3cf0ba-0ddd-4cf7-82e7-73758dffe552	aaaaaaaa-aaaa-aaaa-aaaa-000000000022	https://thumb.wikimedia.org/wikipedia/commons/thumb/5/5b/Kohakus_World_Basement_4K.jpg/960px-Kohakus_World_Basement_4K.jpg	3	2026-11-11 19:44:20.054006+00
46ecf573-b9a0-4606-b6f3-81714a769430	aaaaaaaa-aaaa-aaaa-aaaa-000000000023	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/0c/WMID-EA2706%2C_Medieval%2C_Bar_mount_and_pendent_loop_%28FindID_526918%29.jpg/960px-WMID-EA2706%2C_Medieval%2C_Bar_mount_and_pendent_loop_%28FindID_526918%29.jpg	0	2026-11-11 19:44:20.054006+00
97a8ff57-83b6-4491-96c9-0c4866d2b8cd	aaaaaaaa-aaaa-aaaa-aaaa-000000000023	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/79/%27Pinus_sylvestris%27_Scots_Pine_at_Staplefield%2C_West_Sussex%2C_England_03.JPG/960px-%27Pinus_sylvestris%27_Scots_Pine_at_Staplefield%2C_West_Sussex%2C_England_03.JPG	1	2026-11-11 19:44:20.054006+00
5840f26b-9e56-4f6e-9854-be2e7c576635	aaaaaaaa-aaaa-aaaa-aaaa-000000000023	https://thumb.wikimedia.org/wikipedia/commons/thumb/a/ab/Viet_Ha_Beer_at_the_Bia_hoi%2C_Tay_Ho%2C_Vietnam_%286923012212%29.jpg/960px-Viet_Ha_Beer_at_the_Bia_hoi%2C_Tay_Ho%2C_Vietnam_%286923012212%29.jpg	2	2026-11-11 19:44:20.054006+00
d193f491-47fe-465e-815f-7d2b670ef2af	aaaaaaaa-aaaa-aaaa-aaaa-000000000023	https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a1/Helen_%26_Mark_at_their_local_Bia_hoi%2C_Hanoi_%287069090993%29.jpg/960px-Helen_%26_Mark_at_their_local_Bia_hoi%2C_Hanoi_%287069090993%29.jpg	3	2026-11-11 19:44:20.054006+00
dce8f1f6-995c-4027-a6c0-a48d2fbdd10d	aaaaaaaa-aaaa-aaaa-aaaa-000000000024	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/3f/SNES-Mouse-and-Pad.jpg/960px-SNES-Mouse-and-Pad.jpg	0	2026-11-11 19:44:20.054006+00
41866b89-9f05-4f12-890a-083ac4c3bef0	aaaaaaaa-aaaa-aaaa-aaaa-000000000024	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/07/Philips-CDi-Mouse-wPad.jpg/960px-Philips-CDi-Mouse-wPad.jpg	1	2026-11-11 19:44:20.054006+00
4b45119e-352c-404a-b003-80160080bc99	aaaaaaaa-aaaa-aaaa-aaaa-000000000024	https://thumb.wikimedia.org/wikipedia/commons/thumb/8/88/Sport_on_the_Nilgiris_and_in_Wynaad_%281911%29_%2814595834078%29.jpg/960px-Sport_on_the_Nilgiris_and_in_Wynaad_%281911%29_%2814595834078%29.jpg	2	2026-11-11 19:44:20.054006+00
52ac5042-083a-4d49-a0b9-511f8622d6d2	55555555-5555-4555-8555-555555555555	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/0a/Bose_QuietComfort_25_Acoustic_Noise_Cancelling_Headphones_with_Carry_Case.jpg/960px-Bose_QuietComfort_25_Acoustic_Noise_Cancelling_Headphones_with_Carry_Case.jpg	0	2026-11-11 19:44:20.054006+00
58756250-bec2-45c6-9e2b-3506ed908990	55555555-5555-4555-8555-555555555555	https://thumb.wikimedia.org/wikipedia/commons/thumb/4/4b/Sony-WH-1000XM3-kabellose-Bluetooth-Noise-Cancelling-Kopfhoerer.jpg/960px-Sony-WH-1000XM3-kabellose-Bluetooth-Noise-Cancelling-Kopfhoerer.jpg	1	2026-11-11 19:44:20.054006+00
f0c359cd-df4b-4f7c-a38a-45822b214736	55555555-5555-4555-8555-555555555555	https://thumb.wikimedia.org/wikipedia/commons/thumb/8/86/Bose_QuietComfort_25_Acoustic_Noise_Cancelling_Headphones.jpg/960px-Bose_QuietComfort_25_Acoustic_Noise_Cancelling_Headphones.jpg	2	2026-11-11 19:44:20.054006+00
9a3d1522-0a0d-4a58-a26c-b1579242da76	55555555-5555-4555-8555-555555555555	https://thumb.wikimedia.org/wikipedia/commons/thumb/e/eb/Sony-WH-1000XM3-kabellose-Bluetooth-Noise-Cancelling-Kopfhoerer.2.jpg/960px-Sony-WH-1000XM3-kabellose-Bluetooth-Noise-Cancelling-Kopfhoerer.2.jpg	3	2026-11-11 19:44:20.054006+00
2abba041-a669-408d-8664-6405e28f0110	bbbbbbbb-bbbb-bbbb-bbbb-000000000020	https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d4/Buerostuhl_%28fcm%29.jpg/960px-Buerostuhl_%28fcm%29.jpg	0	2026-11-11 19:44:20.054006+00
546c6d1e-16c6-4a24-96bf-7b2e9707191a	bbbbbbbb-bbbb-bbbb-bbbb-000000000020	https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b4/Desk_chair_by_Frank_Lloyd_Wright%2C_V%26A_London.jpg/960px-Desk_chair_by_Frank_Lloyd_Wright%2C_V%26A_London.jpg	1	2026-11-11 19:44:20.054006+00
d898975a-916a-4930-8287-8aee0547a90b	bbbbbbbb-bbbb-bbbb-bbbb-000000000020	https://thumb.wikimedia.org/wikipedia/commons/thumb/1/1c/Desk_chair_%28fauteuil_de_bureau%29_MET_174288.jpg/960px-Desk_chair_%28fauteuil_de_bureau%29_MET_174288.jpg	2	2026-11-11 19:44:20.054006+00
b69be7b9-78be-44b4-938c-244d133d3fe3	bbbbbbbb-bbbb-bbbb-bbbb-000000000020	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/76/Desk_chair_%28fauteuil_de_bureau%29_MET_DP278961.jpg/960px-Desk_chair_%28fauteuil_de_bureau%29_MET_DP278961.jpg	3	2026-11-11 19:44:20.054006+00
2d6cd7da-66a2-4cbd-8ce0-47c08983350a	cccccccc-cccc-cccc-cccc-000000000010	https://thumb.wikimedia.org/wikipedia/commons/thumb/6/62/%E6%A2%9D%E7%B4%8B%E7%87%88%E7%B1%A0%E8%A4%B2.jpg/960px-%E6%A2%9D%E7%B4%8B%E7%87%88%E7%B1%A0%E8%A4%B2.jpg	0	2026-11-11 19:44:20.054006+00
3cdacc25-b23d-4712-9215-144831265dab	cccccccc-cccc-cccc-cccc-000000000010	https://thumb.wikimedia.org/wikipedia/commons/thumb/5/5b/Kimono_Woman_with_Monpe_%26_Basket_in_Japan_%281915_by_Elstner_Hilton%29.jpg/960px-Kimono_Woman_with_Monpe_%26_Basket_in_Japan_%281915_by_Elstner_Hilton%29.jpg	1	2026-11-11 19:44:20.054006+00
3a599fc5-f86b-4a88-b827-ef3fb05db2dd	cccccccc-cccc-cccc-cccc-000000000010	https://thumb.wikimedia.org/wikipedia/commons/thumb/6/6f/Basic_trousers_for_men.jpg/960px-Basic_trousers_for_men.jpg	2	2026-11-11 19:44:20.054006+00
b2b85dd8-c9b2-4e6f-9257-9ad6afeacfcd	cccccccc-cccc-cccc-cccc-000000000010	https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b3/Back_side_Chino_trousers_with_welt_pocket.jpg/960px-Back_side_Chino_trousers_with_welt_pocket.jpg	3	2026-11-11 19:44:20.054006+00
dbcb6655-1c2a-4a7c-9760-61d94a01185f	aaaaaaaa-aaaa-aaaa-aaaa-000000000025	https://thumb.wikimedia.org/wikipedia/commons/thumb/a/ae/Phone_charger_2026.jpg/960px-Phone_charger_2026.jpg	0	2026-11-11 19:44:20.054006+00
8d7c3303-9f94-4ca9-9d8c-5512ae2fa690	aaaaaaaa-aaaa-aaaa-aaaa-000000000025	https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b3/Panasonic_ENELOOP_PRO_Ni-MH_BATTERIES_%26_BATTERY_CHARGER_BQ-CC55C_%282%29.jpg/960px-Panasonic_ENELOOP_PRO_Ni-MH_BATTERIES_%26_BATTERY_CHARGER_BQ-CC55C_%282%29.jpg	1	2026-11-11 19:44:20.054006+00
abf76014-635b-4f07-b8d6-3191b0f23dc4	aaaaaaaa-aaaa-aaaa-aaaa-000000000025	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f9/Battery_charger_set_C76A_Pro_in_Tashkent_Region.jpg/960px-Battery_charger_set_C76A_Pro_in_Tashkent_Region.jpg	2	2026-11-11 19:44:20.054006+00
2155736e-4b51-4875-956f-ac9e85355ea2	aaaaaaaa-aaaa-aaaa-aaaa-000000000025	https://thumb.wikimedia.org/wikipedia/commons/thumb/c/cc/Panasonic_ENELOOP_PRO_Ni-MH_BATTERIES_%26_BATTERY_CHARGER_BQ-CC55C.jpg/960px-Panasonic_ENELOOP_PRO_Ni-MH_BATTERIES_%26_BATTERY_CHARGER_BQ-CC55C.jpg	3	2026-11-11 19:44:20.054006+00
dfb6a5c1-6418-4801-87c4-8e0248feb36f	aaaaaaaa-aaaa-aaaa-aaaa-000000000026	https://thumb.wikimedia.org/wikipedia/commons/thumb/1/17/Ea_f20_microphone.jpg/960px-Ea_f20_microphone.jpg	0	2026-11-11 19:44:20.054006+00
8c4fa4d9-9885-4185-a132-36957f1c1bb3	aaaaaaaa-aaaa-aaaa-aaaa-000000000026	https://thumb.wikimedia.org/wikipedia/commons/thumb/2/21/Condenser_Microphone_with_Pop_Filter_and_Reflection_Filter.jpg/960px-Condenser_Microphone_with_Pop_Filter_and_Reflection_Filter.jpg	1	2026-11-11 19:44:20.054006+00
63047105-988c-428c-95bc-a3e1bf3028ef	aaaaaaaa-aaaa-aaaa-aaaa-000000000026	https://thumb.wikimedia.org/wikipedia/commons/thumb/c/c0/Microphone_%C3%A9lectrostatique.jpg/960px-Microphone_%C3%A9lectrostatique.jpg	2	2026-11-11 19:44:20.054006+00
bc318d2a-97ff-4eca-8256-177ca7330c39	aaaaaaaa-aaaa-aaaa-aaaa-000000000026	https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b6/Reflection_Filter_%26_Pop_Filter.jpg/960px-Reflection_Filter_%26_Pop_Filter.jpg	3	2026-11-11 19:44:20.054006+00
e08129b3-8df4-487a-9dcf-20cb3ca55e7a	bbbbbbbb-bbbb-bbbb-bbbb-000000000021	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/7c/020210821_Copper_cookware%2C_early_20th_century%2C_bourgeois_kitchen%2C_Galicia%2C_Sanok.jpg/960px-020210821_Copper_cookware%2C_early_20th_century%2C_bourgeois_kitchen%2C_Galicia%2C_Sanok.jpg	0	2026-11-11 19:44:20.054006+00
d3702dbf-bb8c-4299-90fb-9714e357ca57	bbbbbbbb-bbbb-bbbb-bbbb-000000000021	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/fc/020210821_150808_Copper_cookware%2C_early_20th_century%2C_bourgeois_kitchen%2C_Galicia%2C_Sanok.jpg/960px-020210821_150808_Copper_cookware%2C_early_20th_century%2C_bourgeois_kitchen%2C_Galicia%2C_Sanok.jpg	1	2026-11-11 19:44:20.054006+00
f2db1531-c5f7-4f43-853e-5b24921abf7a	44444444-4444-4444-8444-444444444444	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f6/Docking_station_1.jpg/960px-Docking_station_1.jpg	3	2026-11-11 19:44:20.054006+00
d83c5de7-ff82-4ade-8a7a-58adb719f9f7	bbbbbbbb-bbbb-bbbb-bbbb-000000000021	https://thumb.wikimedia.org/wikipedia/commons/thumb/5/50/020210817_144819_Copper_cookware%2C_early_20th_century%2C_bourgeois_kitchen%2C_Poland.jpg/960px-020210817_144819_Copper_cookware%2C_early_20th_century%2C_bourgeois_kitchen%2C_Poland.jpg	2	2026-11-11 19:44:20.054006+00
b936c732-130a-4331-befb-47eabd5505f5	bbbbbbbb-bbbb-bbbb-bbbb-000000000021	https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d6/020210818_132907_Copper_cookware%2C_early_20th_century%2C_manor_house_kitchen%2C_Galicia%2C_Poland.jpg/960px-020210818_132907_Copper_cookware%2C_early_20th_century%2C_manor_house_kitchen%2C_Galicia%2C_Poland.jpg	3	2026-11-11 19:44:20.054006+00
213b5644-adf2-4007-854b-bea03032bbf5	aaaaaaaa-aaaa-aaaa-aaaa-000000000027	https://thumb.wikimedia.org/wikipedia/commons/thumb/4/4a/Turbo_Akku_Vac_Easy_Home_VC_618WP_-_battery_pack-1376.jpg/960px-Turbo_Akku_Vac_Easy_Home_VC_618WP_-_battery_pack-1376.jpg	0	2026-11-11 19:44:20.054006+00
10e5e1ff-622b-42ae-92f0-02744e9fd2f5	aaaaaaaa-aaaa-aaaa-aaaa-000000000027	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/7c/Lucid_battery_pack_01.jpg/960px-Lucid_battery_pack_01.jpg	1	2026-11-11 19:44:20.054006+00
ef896489-b588-4ccf-8697-98dec4bcfd35	aaaaaaaa-aaaa-aaaa-aaaa-000000000027	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/39/Lucid_battery_pack_-_detail.jpg/960px-Lucid_battery_pack_-_detail.jpg	2	2026-11-11 19:44:20.054006+00
719f3181-7c91-4f66-a88a-d4405122e75a	aaaaaaaa-aaaa-aaaa-aaaa-000000000027	https://thumb.wikimedia.org/wikipedia/commons/thumb/6/6f/Canon_LP-EL_BATTERY_PACK_%282%29.jpg/960px-Canon_LP-EL_BATTERY_PACK_%282%29.jpg	3	2026-11-11 19:44:20.054006+00
39b2789e-df14-408b-a281-5292daa0570b	aaaaaaaa-aaaa-aaaa-aaaa-000000000028	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/06/RGB_LED_Strip.jpg/960px-RGB_LED_Strip.jpg	0	2026-11-11 19:44:20.054006+00
f828a5e3-3ac0-4b55-852d-312ea421bd6e	aaaaaaaa-aaaa-aaaa-aaaa-000000000028	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f7/LED_strip_closeup.jpg/960px-LED_strip_closeup.jpg	1	2026-11-11 19:44:20.054006+00
99ac6859-51e6-4245-9ec6-7958bda88bfc	aaaaaaaa-aaaa-aaaa-aaaa-000000000028	https://thumb.wikimedia.org/wikipedia/commons/thumb/6/68/Ta%C5%9Bma_LED_RGB.jpg/960px-Ta%C5%9Bma_LED_RGB.jpg	2	2026-11-11 19:44:20.054006+00
7f0de4f8-1d8f-4291-985c-aee4a7e88064	aaaaaaaa-aaaa-aaaa-aaaa-000000000028	https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d7/Chip_RGB_LED.jpg/960px-Chip_RGB_LED.jpg	3	2026-11-11 19:44:20.054006+00
823a375b-c8e4-479c-9001-f07cfa238fd0	dddddddd-dddd-dddd-dddd-000000000009	https://thumb.wikimedia.org/wikipedia/commons/thumb/4/40/Grid_Layout_of_Blue_Barry%27s_Exercise_Mats_and_Resistance_Bands.jpg/960px-Grid_Layout_of_Blue_Barry%27s_Exercise_Mats_and_Resistance_Bands.jpg	0	2026-11-11 19:44:20.054006+00
26b71f0e-e15e-41f5-864c-a3c3e57a23e6	dddddddd-dddd-dddd-dddd-000000000009	https://thumb.wikimedia.org/wikipedia/commons/thumb/8/80/Woman_exercising_with_resistance_band_in_a_gym_setting.jpg/960px-Woman_exercising_with_resistance_band_in_a_gym_setting.jpg	1	2026-11-11 19:44:20.054006+00
ff7cd51b-8c25-4686-9c8b-85d754eacc07	dddddddd-dddd-dddd-dddd-000000000009	https://upload.wikimedia.org/wikipedia/commons/a/a2/EFTA00000622_-_Neatly_organized_home_gym_closet_featuring_exercise_balls_foam_rollers_and_a_resistance_band_system.jpg	2	2026-11-11 19:44:20.054006+00
0d72d0a5-f70a-4d16-ad7c-f10e6e0ce2c0	dddddddd-dddd-dddd-dddd-000000000009	https://thumb.wikimedia.org/wikipedia/commons/thumb/5/52/EFTA00000621_-_Well-lit_fitness_room_with_wooden_flooring_featuring_exercise_equipment_including_a_weight_machine_stationary_bike_and_resistance_bands.jpg/960px-EFTA00000621_-_Well-lit_fitness_room_with_wooden_flooring_featuring_exercise_equipment_including_a_weight_machine_stationary_bike_and_resistance_bands.jpg	3	2026-11-11 19:44:20.054006+00
3ac3b58f-d772-405c-b9eb-c86b1c5dbea2	cccccccc-cccc-cccc-cccc-000000000011	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/7c/2015_K%C5%82odzko%2C_ul._Dusznicka%2C_myjnia_samochodowa_02.jpg/960px-2015_K%C5%82odzko%2C_ul._Dusznicka%2C_myjnia_samochodowa_02.jpg	0	2026-11-11 19:44:20.054006+00
83dd8f59-0739-4471-b1a6-e05bd47fe458	cccccccc-cccc-cccc-cccc-000000000011	https://upload.wikimedia.org/wikipedia/commons/3/39/Sandals_%28drawing%29.jpg	1	2026-11-11 19:44:20.054006+00
38a2fd06-dc90-4f78-8d85-fd459482a814	cccccccc-cccc-cccc-cccc-000000000011	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/7f/The_main_pool_at_Sandals%2C_Negril_%28171419342%29.jpg/960px-The_main_pool_at_Sandals%2C_Negril_%28171419342%29.jpg	2	2026-11-11 19:44:20.054006+00
a4c637ca-8a61-4570-938b-edc8a350522d	cccccccc-cccc-cccc-cccc-000000000011	https://thumb.wikimedia.org/wikipedia/commons/thumb/6/67/Man_in_sandals_side_view.jpg/960px-Man_in_sandals_side_view.jpg	3	2026-11-11 19:44:20.054006+00
c370d2e1-f0a5-4e59-bd9e-53e210efa334	cccccccc-cccc-cccc-cccc-000000000012	https://thumb.wikimedia.org/wikipedia/commons/thumb/5/54/Colored_silk_scarf_from_India_01.jpg/960px-Colored_silk_scarf_from_India_01.jpg	0	2026-11-11 19:44:20.054006+00
3bf4d0e6-e068-401c-a0dd-3c1d3dab4b2d	cccccccc-cccc-cccc-cccc-000000000012	https://thumb.wikimedia.org/wikipedia/commons/thumb/c/cb/Colored_silk_scarf_from_India_04.jpg/960px-Colored_silk_scarf_from_India_04.jpg	1	2026-11-11 19:44:20.054006+00
786620b2-daf7-41a8-b9a8-23a486df417b	cccccccc-cccc-cccc-cccc-000000000012	https://upload.wikimedia.org/wikipedia/commons/5/50/Headscarf.jpg	2	2026-11-11 19:44:20.054006+00
a005f361-dcae-4c62-b14e-ae52ed929b00	cccccccc-cccc-cccc-cccc-000000000012	https://thumb.wikimedia.org/wikipedia/commons/thumb/9/94/Bruno_Doucey_par_Claude_Truong-Ngoc_d%C3%A9cembre_2014.jpg/960px-Bruno_Doucey_par_Claude_Truong-Ngoc_d%C3%A9cembre_2014.jpg	3	2026-11-11 19:44:20.054006+00
359aaeb5-63ca-44ff-999d-3256e56498f3	cccccccc-cccc-cccc-cccc-000000000013	https://thumb.wikimedia.org/wikipedia/commons/thumb/6/65/Pristine_abandoned_dress_shoes%2C_Rovisco_Pais_Avenue%2C_Lisbon%2C_Portugal_julesvernex2.jpg/960px-Pristine_abandoned_dress_shoes%2C_Rovisco_Pais_Avenue%2C_Lisbon%2C_Portugal_julesvernex2.jpg	0	2026-11-11 19:44:20.054006+00
9f86b4a8-e65d-4ca1-a75e-02c389e55375	cccccccc-cccc-cccc-cccc-000000000013	https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b7/Calzoleria_Toscana_shoes.jpg/960px-Calzoleria_Toscana_shoes.jpg	1	2026-11-11 19:44:20.054006+00
e1b31282-9007-4125-b0a3-34bfd22ec1c8	cccccccc-cccc-cccc-cccc-000000000013	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/fa/1er_Prix_Cr%C3%A9atif_2012.JPG/960px-1er_Prix_Cr%C3%A9atif_2012.JPG	2	2026-11-11 19:44:20.054006+00
39012861-71d7-4f3c-bd84-acf98b3b8d2b	cccccccc-cccc-cccc-cccc-000000000013	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/09/ENB_Artisan_men%27s_dress_shoes_with_seal_fur.jpg/960px-ENB_Artisan_men%27s_dress_shoes_with_seal_fur.jpg	3	2026-11-11 19:44:20.054006+00
345eb3ee-9664-4c29-a156-761d3c1e7c82	cccccccc-cccc-cccc-cccc-000000000014	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f3/Sky_blue_board_shorts.jpg/960px-Sky_blue_board_shorts.jpg	0	2026-11-11 19:44:20.054006+00
00caebaf-526f-45ae-a3c5-88f9f5f42de4	cccccccc-cccc-cccc-cccc-000000000014	https://upload.wikimedia.org/wikipedia/commons/c/c6/Freeballer_board_shorts_01.jpg	1	2026-11-11 19:44:20.054006+00
dab2f1af-8fcf-4339-97d4-0eb4f23b8803	cccccccc-cccc-cccc-cccc-000000000014	https://upload.wikimedia.org/wikipedia/commons/5/5b/Sanwin_Swim_Shorts_%28cropped%29.jpg	2	2026-11-11 19:44:20.054006+00
444d4640-23ab-47bd-a923-3d8c4138cbde	cccccccc-cccc-cccc-cccc-000000000014	https://upload.wikimedia.org/wikipedia/commons/6/60/Male_swim_shorts_inside_out_showing_modesty_gusset.jpg	3	2026-11-11 19:44:20.054006+00
f14b75d3-eac8-43f3-9386-03fab9290804	dddddddd-dddd-dddd-dddd-000000000010	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/33/Zawody_w_skateboardingu_Street_of_Mediateka%2C_Zag%C5%82%C4%99biowska_Mediateka%2C_ulica_Ko%C5%9Bcielna%2C_Sosnowiec%2C_22_czerwca_2024_01.jpg/960px-Zawody_w_skateboardingu_Street_of_Mediateka%2C_Zag%C5%82%C4%99biowska_Mediateka%2C_ulica_Ko%C5%9Bcielna%2C_Sosnowiec%2C_22_czerwca_2024_01.jpg	0	2026-11-11 19:44:20.054006+00
abe9fb47-8d4f-4dcd-98c0-8d19322be0a4	dddddddd-dddd-dddd-dddd-000000000010	https://thumb.wikimedia.org/wikipedia/commons/thumb/e/ec/Zawody_w_skateboardingu_Street_of_Mediateka%2C_Zag%C5%82%C4%99biowska_Mediateka%2C_ulica_Ko%C5%9Bcielna%2C_Sosnowiec%2C_22_czerwca_2024_04.jpg/960px-Zawody_w_skateboardingu_Street_of_Mediateka%2C_Zag%C5%82%C4%99biowska_Mediateka%2C_ulica_Ko%C5%9Bcielna%2C_Sosnowiec%2C_22_czerwca_2024_04.jpg	1	2026-11-11 19:44:20.054006+00
a780f422-34ee-4a62-9e98-794c53804d3e	dddddddd-dddd-dddd-dddd-000000000010	https://upload.wikimedia.org/wikipedia/commons/e/e0/Penny_Skateboard_at_a_skateboard_and_surfer_shop_in_Shibuya%2C_Tokyo.jpg	2	2026-11-11 19:44:20.054006+00
922bad69-2d58-4bf6-ac1a-3d947c4ce6c7	dddddddd-dddd-dddd-dddd-000000000010	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/73/Skateboard_upside.jpg/960px-Skateboard_upside.jpg	3	2026-11-11 19:44:20.054006+00
8f8f9c26-e8da-400a-876d-341184085531	dddddddd-dddd-dddd-dddd-000000000011	https://thumb.wikimedia.org/wikipedia/commons/thumb/2/28/Nomad%2C_OutDoor_2018%2C_Friedrichshafen_%281X7A0320%29.jpg/960px-Nomad%2C_OutDoor_2018%2C_Friedrichshafen_%281X7A0320%29.jpg	0	2026-11-11 19:44:20.054006+00
f71f655c-5ff7-4245-87c4-eb6c5cb90378	dddddddd-dddd-dddd-dddd-000000000011	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/0c/OutDoor_2018%2C_Friedrichshafen_%281X7A0298%29.jpg/960px-OutDoor_2018%2C_Friedrichshafen_%281X7A0298%29.jpg	1	2026-11-11 19:44:20.054006+00
51502eba-61fa-43d7-bb06-adeaa4534c46	dddddddd-dddd-dddd-dddd-000000000011	https://thumb.wikimedia.org/wikipedia/commons/thumb/2/2e/Mummy_bag.jpg/960px-Mummy_bag.jpg	2	2026-11-11 19:44:20.054006+00
c3364d8f-ad6a-4f9f-9fc1-61d50f7a1296	dddddddd-dddd-dddd-dddd-000000000011	https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a9/Sleeping_bag_for_space.JPG/960px-Sleeping_bag_for_space.JPG	3	2026-11-11 19:44:20.054006+00
6c5cdfcd-0a52-45e8-b4c7-c804dc9aaad1	cccccccc-cccc-cccc-cccc-000000000015	https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b8/Mizuno_Wave_Ibuki_2.jpg/960px-Mizuno_Wave_Ibuki_2.jpg	0	2026-11-11 19:44:20.054006+00
3ba01006-b947-4306-baa5-506762999a5b	cccccccc-cccc-cccc-cccc-000000000015	https://thumb.wikimedia.org/wikipedia/commons/thumb/8/8b/Asics_Gel-Cumulus_22.jpg/960px-Asics_Gel-Cumulus_22.jpg	1	2026-11-11 19:44:20.054006+00
15f79af7-f3dc-4ddb-bf19-aaf942996de8	cccccccc-cccc-cccc-cccc-000000000015	https://thumb.wikimedia.org/wikipedia/commons/thumb/e/e1/Brooks_Ghost_14_GTX.jpg/960px-Brooks_Ghost_14_GTX.jpg	2	2026-11-11 19:44:20.054006+00
81789d57-fcc2-458d-8c47-cc5f608c4108	cccccccc-cccc-cccc-cccc-000000000015	https://thumb.wikimedia.org/wikipedia/commons/thumb/e/e7/OutDoor_2018%2C_Friedrichshafen_%281X7A0304%29.jpg/960px-OutDoor_2018%2C_Friedrichshafen_%281X7A0304%29.jpg	3	2026-11-11 19:44:20.054006+00
3d7eeefb-a55b-4f4d-98c6-487b5bd673de	cccccccc-cccc-cccc-cccc-000000000016	https://thumb.wikimedia.org/wikipedia/commons/thumb/1/18/Socks_the_Cat_Explores.jpg/960px-Socks_the_Cat_Explores.jpg	0	2026-11-11 19:44:20.054006+00
4d2a6260-9a87-4269-9875-8aa37b68c1e5	cccccccc-cccc-cccc-cccc-000000000016	https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d9/Socks_for_sale_in_Tokyo.jpg/960px-Socks_for_sale_in_Tokyo.jpg	1	2026-11-11 19:44:20.054006+00
772805ad-63f5-49ab-b864-2f104b7937c5	cccccccc-cccc-cccc-cccc-000000000016	https://thumb.wikimedia.org/wikipedia/commons/thumb/1/1d/A_man_selling_socks_in_the_street.jpg/960px-A_man_selling_socks_in_the_street.jpg	2	2026-11-11 19:44:20.054006+00
27140700-b85c-4ebe-ba80-cf01679be55c	cccccccc-cccc-cccc-cccc-000000000016	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/02/Foot_in_Toe_Socks_Relaxing_on_a_Cushion.jpg/960px-Foot_in_Toe_Socks_Relaxing_on_a_Cushion.jpg	3	2026-11-11 19:44:20.054006+00
9feda1ab-c7ad-49a0-a3dc-07c6ab36036d	bbbbbbbb-bbbb-bbbb-bbbb-000000000022	https://thumb.wikimedia.org/wikipedia/commons/thumb/b/be/Reception_lounge_at_Amantaka_luxury_Resort_%26_Hotel_at_blue_hour_in_Luang_Prabang_Laos.jpg/960px-Reception_lounge_at_Amantaka_luxury_Resort_%26_Hotel_at_blue_hour_in_Luang_Prabang_Laos.jpg	0	2026-11-11 19:44:20.054006+00
51aeea61-5e97-4fa8-9bb5-e7ce35a61a27	bbbbbbbb-bbbb-bbbb-bbbb-000000000022	https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b1/Lobby_lounge_of_Amantaka_Suite_Amantaka_luxury_Resort_%26_Hotel_Luang_Prabang_Laos.jpg/960px-Lobby_lounge_of_Amantaka_Suite_Amantaka_luxury_Resort_%26_Hotel_Luang_Prabang_Laos.jpg	1	2026-11-11 19:44:20.054006+00
dc60b085-c4f6-4157-a62d-8d437ab63851	bbbbbbbb-bbbb-bbbb-bbbb-000000000022	https://thumb.wikimedia.org/wikipedia/commons/thumb/e/ee/Gustave_Caillebotte_-_Nude_on_a_Couch_-_Google_Art_Project.jpg/960px-Gustave_Caillebotte_-_Nude_on_a_Couch_-_Google_Art_Project.jpg	2	2026-11-11 19:44:20.054006+00
c581ab0c-1b13-4b5f-bb3b-4e261fee6d89	bbbbbbbb-bbbb-bbbb-bbbb-000000000022	https://thumb.wikimedia.org/wikipedia/commons/thumb/2/23/Sofa%2C_Couch%2C_Studio%2C_Rostov-on-Don%2C_Russia.jpg/960px-Sofa%2C_Couch%2C_Studio%2C_Rostov-on-Don%2C_Russia.jpg	3	2026-11-11 19:44:20.054006+00
41decd99-a1ba-493d-a674-7773c17dc782	aaaaaaaa-aaaa-aaaa-aaaa-000000000029	https://thumb.wikimedia.org/wikipedia/commons/thumb/6/67/JBL_Flip_3_bluetooth_speaker_%28DSCF2653%29.jpg/960px-JBL_Flip_3_bluetooth_speaker_%28DSCF2653%29.jpg	0	2026-11-11 19:44:20.054006+00
6d2fb0b3-4a7b-46b1-ad11-80f06480aa6a	aaaaaaaa-aaaa-aaaa-aaaa-000000000029	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/72/Size_comparison_of_JBL_Xtreme_Bluetooth_speaker_%2828_cm_long%29_with_TV_remote_control.jpg/960px-Size_comparison_of_JBL_Xtreme_Bluetooth_speaker_%2828_cm_long%29_with_TV_remote_control.jpg	1	2026-11-11 19:44:20.054006+00
5d554ac8-4d9d-485b-bc5d-ed74d6dbcda9	aaaaaaaa-aaaa-aaaa-aaaa-000000000029	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/32/JBL_GO2_Bluetooth_speaker_00_10_27_681000_%28turned_upright%29.jpeg/960px-JBL_GO2_Bluetooth_speaker_00_10_27_681000_%28turned_upright%29.jpeg	2	2026-11-11 19:44:20.054006+00
f989a414-acfe-4038-89a3-1f1b3bb52959	aaaaaaaa-aaaa-aaaa-aaaa-000000000029	https://thumb.wikimedia.org/wikipedia/commons/thumb/9/9d/UE_Boom_2.jpg/960px-UE_Boom_2.jpg	3	2026-11-11 19:44:20.054006+00
d0259ac9-7744-4cd9-9403-0bcbe689ee50	dddddddd-dddd-dddd-dddd-000000000012	https://thumb.wikimedia.org/wikipedia/commons/thumb/c/c1/Magnetic_resistance_stationary_bicycle_exercise_bike.JPG/960px-Magnetic_resistance_stationary_bicycle_exercise_bike.JPG	0	2026-11-11 19:44:20.054006+00
75919022-36d0-4243-a23a-4cdf965979df	dddddddd-dddd-dddd-dddd-000000000012	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/ff/Hicc_-_exercise_bike.jpg/960px-Hicc_-_exercise_bike.jpg	1	2026-11-11 19:44:20.054006+00
4abfece7-6248-4ed6-b00d-cfbc58b814f3	dddddddd-dddd-dddd-dddd-000000000012	https://upload.wikimedia.org/wikipedia/commons/0/00/Fahrrad-Ergometer_01_KMJ.jpg	2	2026-11-11 19:44:20.054006+00
d3cf71d4-de75-41ec-852c-10ffd2c3dde2	dddddddd-dddd-dddd-dddd-000000000012	https://thumb.wikimedia.org/wikipedia/commons/thumb/b/ba/Exercise_bike_%282667274066%29.jpg/960px-Exercise_bike_%282667274066%29.jpg	3	2026-11-11 19:44:20.054006+00
5d70a134-4ee2-4008-954b-4e5aa762a7f7	aaaaaaaa-aaaa-aaaa-aaaa-000000000030	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/0f/Quested_HM412_main_monitor%2C_Studio_9000%2C_PatchWerk_Recording_Studios%2C_2007.jpg/960px-Quested_HM412_main_monitor%2C_Studio_9000%2C_PatchWerk_Recording_Studios%2C_2007.jpg	0	2026-11-11 19:44:20.054006+00
193ba8cf-3596-4c89-a1ad-4f379383b7c7	aaaaaaaa-aaaa-aaaa-aaaa-000000000030	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f6/Tannoy_Little_Gold_monitor_-_professional_studio_loudspeaker_box.jpg/960px-Tannoy_Little_Gold_monitor_-_professional_studio_loudspeaker_box.jpg	1	2026-11-11 19:44:20.054006+00
a171c5ab-b0c7-4e01-840c-a5e4e8cbd082	aaaaaaaa-aaaa-aaaa-aaaa-000000000030	https://upload.wikimedia.org/wikipedia/commons/b/bb/Bass_reflex_opening_of_professional_studio_loudspeaker_box_Tannoy_Little_Gold_Monitor.jpg	2	2026-11-11 19:44:20.054006+00
c0fdbc73-f623-4ab4-915e-eb3c9a06e85e	aaaaaaaa-aaaa-aaaa-aaaa-000000000030	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/08/2017_03_31_Dolby_Atmos_Studio_TVN_Group.jpg/960px-2017_03_31_Dolby_Atmos_Studio_TVN_Group.jpg	3	2026-11-11 19:44:20.054006+00
eca6f840-eb63-44f1-8037-7a29196885ad	cccccccc-cccc-cccc-cccc-000000000017	https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b5/2_%D8%A7%D9%84%D8%AD%D8%AF%D8%A7%D8%AF.jpg/960px-2_%D8%A7%D9%84%D8%AD%D8%AF%D8%A7%D8%AF.jpg	0	2026-11-11 19:44:20.054006+00
ae073b86-6940-428d-933f-b0cbb9254ce5	cccccccc-cccc-cccc-cccc-000000000017	https://thumb.wikimedia.org/wikipedia/commons/thumb/9/9d/2008-07-27_Sunglasses.jpg/960px-2008-07-27_Sunglasses.jpg	1	2026-11-11 19:44:20.054006+00
9a7c823c-17e0-4cac-b89b-4265efb21c85	cccccccc-cccc-cccc-cccc-000000000017	https://thumb.wikimedia.org/wikipedia/commons/thumb/2/21/210120-Z-NI803-1657_%2850884694388%29.jpg/960px-210120-Z-NI803-1657_%2850884694388%29.jpg	2	2026-11-11 19:44:20.054006+00
17281f21-ad0f-4677-8dbc-a9d669b57b62	cccccccc-cccc-cccc-cccc-000000000017	https://thumb.wikimedia.org/wikipedia/commons/thumb/e/e9/Sunglasses_in_Mexico.jpg/960px-Sunglasses_in_Mexico.jpg	3	2026-11-11 19:44:20.054006+00
c746d21a-8a59-4fad-82c0-7f8dc38781e6	cccccccc-cccc-cccc-cccc-000000000018	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/30/Max_Giesinger_-_2021-09-12_-_Mannheim_-_Sven_Mandel_-_1D_X_MK_II_-_0229_-_B70I3683_%28re-developed%29.jpg/960px-Max_Giesinger_-_2021-09-12_-_Mannheim_-_Sven_Mandel_-_1D_X_MK_II_-_0229_-_B70I3683_%28re-developed%29.jpg	0	2026-11-11 19:44:20.054006+00
cc9ab787-2a52-4425-a1f9-5282c432abc4	cccccccc-cccc-cccc-cccc-000000000018	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/32/Ambigram_Ideal%2C_polysymmetrical_logo_printed_on_a_green_T-shirt.jpg/960px-Ambigram_Ideal%2C_polysymmetrical_logo_printed_on_a_green_T-shirt.jpg	1	2026-11-11 19:44:20.054006+00
1063e348-884b-4ec8-8dc3-e6b9a286da64	cccccccc-cccc-cccc-cccc-000000000018	https://thumb.wikimedia.org/wikipedia/commons/thumb/a/aa/Ambigram_Zen_Yes_text_with_meditation_pictogram%2C_embroidered_on_a_blue_T-shirt.jpg/960px-Ambigram_Zen_Yes_text_with_meditation_pictogram%2C_embroidered_on_a_blue_T-shirt.jpg	2	2026-11-11 19:44:20.054006+00
8962801a-7a62-474d-b83a-d33e3f27331e	cccccccc-cccc-cccc-cccc-000000000018	https://upload.wikimedia.org/wikipedia/commons/c/c9/T-shirt_%28drawing%29.jpg	3	2026-11-11 19:44:20.054006+00
739239b8-0742-483a-82ea-c0877aed5741	bbbbbbbb-bbbb-bbbb-bbbb-000000000023	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/3b/Shattered_light_fixture_3.jpg/960px-Shattered_light_fixture_3.jpg	0	2026-11-11 19:44:20.054006+00
31101c9a-e0bd-45bd-9385-2e2f048ab903	bbbbbbbb-bbbb-bbbb-bbbb-000000000023	https://thumb.wikimedia.org/wikipedia/commons/thumb/8/88/Ceiling_light_fixture_in_Hofstatt_Mall%2C_Munich%2C_February_2017.jpg/960px-Ceiling_light_fixture_in_Hofstatt_Mall%2C_Munich%2C_February_2017.jpg	1	2026-11-11 19:44:20.054006+00
b0fccc46-609a-41f3-b2bb-d0d54a47d68b	bbbbbbbb-bbbb-bbbb-bbbb-000000000023	https://thumb.wikimedia.org/wikipedia/commons/thumb/8/8d/M%C3%BCnster%2C_St.-Lamberti-Kirche%2C_Westportal_--_2021_--_9082.jpg/960px-M%C3%BCnster%2C_St.-Lamberti-Kirche%2C_Westportal_--_2021_--_9082.jpg	2	2026-11-11 19:44:20.054006+00
9a2090de-e97c-4e84-8c16-565a5e1de483	bbbbbbbb-bbbb-bbbb-bbbb-000000000023	https://thumb.wikimedia.org/wikipedia/commons/thumb/9/95/Staircase_of_the_BGK_Building_%2826620981474%29.jpg/960px-Staircase_of_the_BGK_Building_%2826620981474%29.jpg	3	2026-11-11 19:44:20.054006+00
5d26cf0a-bf80-4cc7-8872-49986f02a5e0	dddddddd-dddd-dddd-dddd-000000000013	https://thumb.wikimedia.org/wikipedia/commons/thumb/e/e9/Campement_-_20150802_15h44_%2810723%29.jpg/960px-Campement_-_20150802_15h44_%2810723%29.jpg	0	2026-11-11 19:44:20.054006+00
3b271a76-6fa4-470d-b37e-82ba84f2528d	dddddddd-dddd-dddd-dddd-000000000013	https://thumb.wikimedia.org/wikipedia/commons/thumb/8/87/Saami_Family_1900.jpg/960px-Saami_Family_1900.jpg	1	2026-11-11 19:44:20.054006+00
2a3ad0e7-62e9-4ce1-862d-4439111ae610	dddddddd-dddd-dddd-dddd-000000000013	https://thumb.wikimedia.org/wikipedia/commons/thumb/4/4d/Tent_as_decoration_at_a_Haldi_ceremony_in_South_India_02.jpg/960px-Tent_as_decoration_at_a_Haldi_ceremony_in_South_India_02.jpg	2	2026-11-11 19:44:20.054006+00
ee27df84-fd28-4021-ad1c-ab7c1da3407d	dddddddd-dddd-dddd-dddd-000000000013	https://thumb.wikimedia.org/wikipedia/commons/thumb/5/5c/Zagedan_Ridge%2C_Camping_tent%2C_Caucasus_Mountains.jpg/960px-Zagedan_Ridge%2C_Camping_tent%2C_Caucasus_Mountains.jpg	3	2026-11-11 19:44:20.054006+00
39ba1d85-fd9d-47fc-b2a6-acd849856244	eeeeeeee-eeee-eeee-eeee-000000000004	https://thumb.wikimedia.org/wikipedia/commons/thumb/5/50/International_books_at_Kent_Library.jpg/960px-International_books_at_Kent_Library.jpg	0	2026-11-11 19:44:20.054006+00
62734fae-7316-408a-b1ac-a449fd5b66ab	eeeeeeee-eeee-eeee-eeee-000000000004	https://thumb.wikimedia.org/wikipedia/commons/thumb/e/e9/Interior_view._The_Leadhills_Miners_Library_books.jpg/960px-Interior_view._The_Leadhills_Miners_Library_books.jpg	1	2026-11-11 19:44:20.054006+00
d7184db6-db3b-4f71-9720-b96c6183edf9	eeeeeeee-eeee-eeee-eeee-000000000004	https://thumb.wikimedia.org/wikipedia/commons/thumb/e/e0/Sias_Library_Interior.jpg/960px-Sias_Library_Interior.jpg	2	2026-11-11 19:44:20.054006+00
931015f2-39b0-4a4d-8b4c-75c39f85fa03	eeeeeeee-eeee-eeee-eeee-000000000004	https://thumb.wikimedia.org/wikipedia/commons/thumb/1/13/Nisshin_City_Library_interior_1F_childrens_books_ac.jpg/960px-Nisshin_City_Library_interior_1F_childrens_books_ac.jpg	3	2026-11-11 19:44:20.054006+00
d12a441d-d269-4463-88c0-30acc7fa8432	eeeeeeee-eeee-eeee-eeee-000000000005	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/3d/Doctors_and_patients%3B_or%2C_Anecdotes_of_the_medical_world_and_curiosities_of_medicine_%281873%29_%2814762122486%29.jpg/960px-Doctors_and_patients%3B_or%2C_Anecdotes_of_the_medical_world_and_curiosities_of_medicine_%281873%29_%2814762122486%29.jpg	0	2026-11-11 19:44:20.054006+00
8744325b-8d78-4db7-a65f-820c5e6c43a3	eeeeeeee-eeee-eeee-eeee-000000000005	https://thumb.wikimedia.org/wikipedia/commons/thumb/c/c9/The_Bookshelf_for_boys_and_girls_Children%27s_Book_of_Fact_and_Fancy_%281912%29_%2814586825988%29.jpg/960px-The_Bookshelf_for_boys_and_girls_Children%27s_Book_of_Fact_and_Fancy_%281912%29_%2814586825988%29.jpg	1	2026-11-11 19:44:20.054006+00
8399ce51-c043-460e-af8e-69be6128349c	eeeeeeee-eeee-eeee-eeee-000000000005	https://thumb.wikimedia.org/wikipedia/commons/thumb/8/8f/The_southerner_-_a_romance_of_the_real_Lincoln_%281913%29_%2814580170268%29.jpg/960px-The_southerner_-_a_romance_of_the_real_Lincoln_%281913%29_%2814580170268%29.jpg	2	2026-11-11 19:44:20.054006+00
2b09dbab-c363-4d81-83fb-14240cd3b183	eeeeeeee-eeee-eeee-eeee-000000000005	https://thumb.wikimedia.org/wikipedia/commons/thumb/5/55/The_morning_watch_or_Thoughts_for_the_quiet_hour.._%281897%29_%2814592959567%29.jpg/960px-The_morning_watch_or_Thoughts_for_the_quiet_hour.._%281897%29_%2814592959567%29.jpg	3	2026-11-11 19:44:20.054006+00
f3654b8f-6fb5-4cd3-b626-6200285f1e24	bbbbbbbb-bbbb-bbbb-bbbb-000000000024	https://thumb.wikimedia.org/wikipedia/commons/thumb/4/47/Hoteltoaster_01_%28sk%29.jpg/960px-Hoteltoaster_01_%28sk%29.jpg	0	2026-11-11 19:44:20.054006+00
87de36e5-1262-40cd-94bc-37bd4f2e8180	bbbbbbbb-bbbb-bbbb-bbbb-000000000024	https://thumb.wikimedia.org/wikipedia/commons/thumb/c/c4/Toaster_with_molten_plastic_casing.jpg/960px-Toaster_with_molten_plastic_casing.jpg	1	2026-11-11 19:44:20.054006+00
fdefea81-ef3f-4fe0-873c-699d3d9bed67	bbbbbbbb-bbbb-bbbb-bbbb-000000000024	https://thumb.wikimedia.org/wikipedia/commons/thumb/6/66/Queer_Toaster.jpg/960px-Queer_Toaster.jpg	2	2026-11-11 19:44:20.054006+00
c2a480f8-f5cb-4e6e-bfd9-3a26f5934f69	bbbbbbbb-bbbb-bbbb-bbbb-000000000024	https://thumb.wikimedia.org/wikipedia/commons/thumb/5/53/Toaster_with_molten_plastic_casing_2.jpg/960px-Toaster_with_molten_plastic_casing_2.jpg	3	2026-11-11 19:44:20.054006+00
f084fa20-eb05-4431-97a6-c1fcd04a253c	dddddddd-dddd-dddd-dddd-000000000014	https://thumb.wikimedia.org/wikipedia/commons/thumb/2/2f/Cyberith_VidMill_Video_Production_Treadmill_used_for_Volumetric_Capture.jpg/960px-Cyberith_VidMill_Video_Production_Treadmill_used_for_Volumetric_Capture.jpg	0	2026-11-11 19:44:20.054006+00
53638dbf-f887-4df9-80fa-32a3036b3994	dddddddd-dddd-dddd-dddd-000000000014	https://thumb.wikimedia.org/wikipedia/commons/thumb/9/95/Exercise_Treadmill_Convey_Motion.jpg/960px-Exercise_Treadmill_Convey_Motion.jpg	1	2026-11-11 19:44:20.054006+00
96630a78-6598-4328-9e4d-10ec972d4c43	dddddddd-dddd-dddd-dddd-000000000014	https://thumb.wikimedia.org/wikipedia/commons/thumb/1/1f/Treadmill_from_Viking_Sport%2C_front.jpg/960px-Treadmill_from_Viking_Sport%2C_front.jpg	2	2026-11-11 19:44:20.054006+00
220c5480-f36c-47dd-9426-fc26ab78a9bb	dddddddd-dddd-dddd-dddd-000000000014	https://thumb.wikimedia.org/wikipedia/commons/thumb/c/c5/Treadmill_from_Viking_Sport%2C_folded.jpg/960px-Treadmill_from_Viking_Sport%2C_folded.jpg	3	2026-11-11 19:44:20.054006+00
3c6bdb56-135d-46f5-884b-5db8ae657ed1	aaaaaaaa-aaaa-aaaa-aaaa-000000000031	https://thumb.wikimedia.org/wikipedia/commons/thumb/1/19/2023_Pendrive%27y_Verbatim_Store_%E2%80%98n%E2%80%99_Click_16_GB_%281%29.jpg/960px-2023_Pendrive%27y_Verbatim_Store_%E2%80%98n%E2%80%99_Click_16_GB_%281%29.jpg	0	2026-11-11 19:44:20.054006+00
39c1d1b3-3bf7-401e-af9a-b7363a8440e6	aaaaaaaa-aaaa-aaaa-aaaa-000000000031	https://thumb.wikimedia.org/wikipedia/commons/thumb/6/6f/Kingston_Technology_DataTraveler_G4_USB_flash_drive_USB_3.0_32_Gb.jpg/960px-Kingston_Technology_DataTraveler_G4_USB_flash_drive_USB_3.0_32_Gb.jpg	1	2026-11-11 19:44:20.054006+00
e3a37ce9-4851-4deb-83f3-309fed795acf	aaaaaaaa-aaaa-aaaa-aaaa-000000000031	https://thumb.wikimedia.org/wikipedia/commons/thumb/2/22/SanDisk_Memory_Stick_PRO_256MB.jpg/960px-SanDisk_Memory_Stick_PRO_256MB.jpg	2	2026-11-11 19:44:20.054006+00
51e995ff-8c95-4f6f-b256-8806c5c891ae	aaaaaaaa-aaaa-aaaa-aaaa-000000000031	https://thumb.wikimedia.org/wikipedia/commons/thumb/e/e7/Supertalent_USB-Stick.jpg/960px-Supertalent_USB-Stick.jpg	3	2026-11-11 19:44:20.054006+00
a5733267-5c45-4283-a4df-3ba765ff28cf	44444444-4444-4444-8444-444444444444	https://thumb.wikimedia.org/wikipedia/commons/thumb/6/60/Bottom_view_of_a_Dell_D-Dock_PD01X_docking_station.jpg/960px-Bottom_view_of_a_Dell_D-Dock_PD01X_docking_station.jpg	0	2026-11-11 19:44:20.054006+00
728a32e8-8c27-4182-b519-231e09ee36c0	44444444-4444-4444-8444-444444444444	https://thumb.wikimedia.org/wikipedia/commons/thumb/c/cc/Top_view_of_a_Dell_D-Dock_PD01X_with_docking_controls.jpg/960px-Top_view_of_a_Dell_D-Dock_PD01X_with_docking_controls.jpg	1	2026-11-11 19:44:20.054006+00
22660122-90e0-4cdd-89c8-d752582c0ff8	44444444-4444-4444-8444-444444444444	https://thumb.wikimedia.org/wikipedia/commons/thumb/2/25/Rear_ports_of_a_Dell_D-Dock_PD01X_Expansion_Station.jpg/960px-Rear_ports_of_a_Dell_D-Dock_PD01X_Expansion_Station.jpg	2	2026-11-11 19:44:20.054006+00
22bdb410-5f9e-4bb6-b776-6355a3e4ded0	aaaaaaaa-aaaa-aaaa-aaaa-000000000032	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/04/2023_Hub_USB_2.0.jpg/960px-2023_Hub_USB_2.0.jpg	0	2026-11-11 19:44:20.054006+00
53c8cbaf-709b-48b7-aebb-fd54eb0d72db	aaaaaaaa-aaaa-aaaa-aaaa-000000000032	https://thumb.wikimedia.org/wikipedia/commons/thumb/c/cc/2023_Hub_USB_2.0_%281%29.jpg/960px-2023_Hub_USB_2.0_%281%29.jpg	1	2026-11-11 19:44:20.054006+00
82a2086d-2bbc-45d1-8929-d047b5d6dcc2	aaaaaaaa-aaaa-aaaa-aaaa-000000000032	https://thumb.wikimedia.org/wikipedia/commons/thumb/d/dd/2023_Hub_USB_2.0_%282%29.jpg/960px-2023_Hub_USB_2.0_%282%29.jpg	2	2026-11-11 19:44:20.054006+00
7f9d1455-5a2d-4d52-aec9-cd2ac45c7420	aaaaaaaa-aaaa-aaaa-aaaa-000000000032	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/36/USB_Hub_Internals.jpg/960px-USB_Hub_Internals.jpg	3	2026-11-11 19:44:20.054006+00
4dca1039-fa70-4153-9114-3c2ac4e1c77a	bbbbbbbb-bbbb-bbbb-bbbb-000000000025	https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b5/Philips_medical_wall_lamp_designed_by_Charlotte_Perriand.jpg/960px-Philips_medical_wall_lamp_designed_by_Charlotte_Perriand.jpg	0	2026-11-11 19:44:20.054006+00
d800d12f-4891-4627-bb56-ac24ebcb64ed	bbbbbbbb-bbbb-bbbb-bbbb-000000000025	https://thumb.wikimedia.org/wikipedia/commons/thumb/8/8b/Wall_lamp_of_Farm%C3%A0cia_Bol%C3%B3s_-_Barcelona_2014.jpg/960px-Wall_lamp_of_Farm%C3%A0cia_Bol%C3%B3s_-_Barcelona_2014.jpg	1	2026-11-11 19:44:20.054006+00
f004029d-87d5-4097-85ee-d7b9f1cef56f	bbbbbbbb-bbbb-bbbb-bbbb-000000000025	https://thumb.wikimedia.org/wikipedia/commons/thumb/b/bd/Creative_wall_lamp.jpg/960px-Creative_wall_lamp.jpg	2	2026-11-11 19:44:20.054006+00
c25d235e-3205-4ae2-bd25-0a3c09a7fdb5	bbbbbbbb-bbbb-bbbb-bbbb-000000000025	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/00/Wall_Bracket_for_a_Lamp_MET_DP166737.jpg/960px-Wall_Bracket_for_a_Lamp_MET_DP166737.jpg	3	2026-11-11 19:44:20.054006+00
f3085352-d16c-447a-83d1-238fc972c880	cccccccc-cccc-cccc-cccc-000000000019	https://thumb.wikimedia.org/wikipedia/commons/thumb/c/ce/Leather_bi-fold_wallet_by_Eugene_Pik_%282021%29_04.jpg/960px-Leather_bi-fold_wallet_by_Eugene_Pik_%282021%29_04.jpg	0	2026-11-11 19:44:20.054006+00
028a437b-66c0-46d4-9f0e-bccfd5919b72	cccccccc-cccc-cccc-cccc-000000000019	https://thumb.wikimedia.org/wikipedia/commons/thumb/e/ea/Leather_bi-fold_wallet_by_Eugene_Pik_%282021%29_06.jpg/960px-Leather_bi-fold_wallet_by_Eugene_Pik_%282021%29_06.jpg	1	2026-11-11 19:44:20.054006+00
4ed7682e-b926-4a13-983f-5af6c3b849ff	cccccccc-cccc-cccc-cccc-000000000019	https://thumb.wikimedia.org/wikipedia/commons/thumb/1/15/Aarong_leather_wallet.jpg/960px-Aarong_leather_wallet.jpg	2	2026-11-11 19:44:20.054006+00
df71ad0d-5e40-4afc-97e7-9c156038bfc8	cccccccc-cccc-cccc-cccc-000000000019	https://thumb.wikimedia.org/wikipedia/commons/thumb/d/dd/Varanidae_skin_leather_wallet%2C_held_by_Polish_customs_authorities%2C_Warsaw_Chopin_Airport%2C_April%2C_2019.jpg/960px-Varanidae_skin_leather_wallet%2C_held_by_Polish_customs_authorities%2C_Warsaw_Chopin_Airport%2C_April%2C_2019.jpg	3	2026-11-11 19:44:20.054006+00
6e6c0aae-5735-4f17-b450-aa10d81ba926	cccccccc-cccc-cccc-cccc-000000000020	https://thumb.wikimedia.org/wikipedia/commons/thumb/c/c5/Junghans_Mega.jpg/960px-Junghans_Mega.jpg	0	2026-11-11 19:44:20.054006+00
9e1af707-3d05-471f-8f33-8e35f25282a4	cccccccc-cccc-cccc-cccc-000000000020	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/35/Damasko_DC56_-_German_analog_chronograph_wristwatch_with_mechanical_movement_type_Valjoux_7750.jpg/960px-Damasko_DC56_-_German_analog_chronograph_wristwatch_with_mechanical_movement_type_Valjoux_7750.jpg	1	2026-11-11 19:44:20.054006+00
14d92ae0-d2dc-4996-a5e9-b88c4e4ef0dd	cccccccc-cccc-cccc-cccc-000000000020	https://upload.wikimedia.org/wikipedia/commons/7/71/Lenzkirch02.jpg	2	2026-11-11 19:44:20.054006+00
d13560d9-8fda-4a85-ae8e-5c678bcb03ed	cccccccc-cccc-cccc-cccc-000000000020	https://thumb.wikimedia.org/wikipedia/commons/thumb/9/95/OMEGA_SEAMASTER_120M_ANALOG_DIGITAL.jpg/960px-OMEGA_SEAMASTER_120M_ANALOG_DIGITAL.jpg	3	2026-11-11 19:44:20.054006+00
b7c39dbe-231c-4f56-b8cb-5a5f145a4e9e	aaaaaaaa-aaaa-aaaa-aaaa-000000000033	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f5/Fancy_webcam_on_computer_screen.jpg/960px-Fancy_webcam_on_computer_screen.jpg	0	2026-11-11 19:44:20.054006+00
9dea0030-1a14-40db-b904-34e65eb82d43	aaaaaaaa-aaaa-aaaa-aaaa-000000000033	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/7f/Webcam_on_computer_screen.jpg/960px-Webcam_on_computer_screen.jpg	1	2026-11-11 19:44:20.054006+00
1f4a1beb-f86a-43b4-9547-17f743fe145c	aaaaaaaa-aaaa-aaaa-aaaa-000000000033	https://thumb.wikimedia.org/wikipedia/commons/thumb/6/63/USB_webcam_for_PC.jpg/960px-USB_webcam_for_PC.jpg	2	2026-11-11 19:44:20.054006+00
efd0e74b-123b-47da-8f6c-3e887c01a711	aaaaaaaa-aaaa-aaaa-aaaa-000000000033	https://thumb.wikimedia.org/wikipedia/commons/thumb/5/55/Webcam_01.jpg/960px-Webcam_01.jpg	3	2026-11-11 19:44:20.054006+00
6f9e6aa0-ae53-4d09-a5c4-e05a3e47754c	22222222-2222-4222-8222-222222222222	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/77/A_wireless_computer_mouse.jpg/960px-A_wireless_computer_mouse.jpg	0	2026-11-11 19:44:20.054006+00
280ef423-b64a-4ceb-b06f-3e18a3c7bf68	22222222-2222-4222-8222-222222222222	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f1/A_black_wireless_computer_mouse.jpg/960px-A_black_wireless_computer_mouse.jpg	1	2026-11-11 19:44:20.054006+00
5da28299-a9a6-49e3-8d40-e229f82424d1	22222222-2222-4222-8222-222222222222	https://thumb.wikimedia.org/wikipedia/commons/thumb/1/1b/Wireless_computer_keyboard_with_mouse_an_USB_receiver.jpg/960px-Wireless_computer_keyboard_with_mouse_an_USB_receiver.jpg	2	2026-11-11 19:44:20.054006+00
873cdf32-b87a-49de-a943-7ff5d24b07bb	22222222-2222-4222-8222-222222222222	https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a1/Wireless_Logilink_Computer_mouse_with_USB_Micro_Dongle.JPG/960px-Wireless_Logilink_Computer_mouse_with_USB_Micro_Dongle.JPG	3	2026-11-11 19:44:20.054006+00
1a4e64c0-1b1a-4d85-a915-2d65693aa200	aaaaaaaa-aaaa-aaaa-aaaa-000000000034	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/77/A_wireless_computer_mouse.jpg/960px-A_wireless_computer_mouse.jpg	0	2026-11-11 19:44:20.054006+00
022deea4-8fde-4822-a44b-4edd63498b9e	aaaaaaaa-aaaa-aaaa-aaaa-000000000034	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f1/A_black_wireless_computer_mouse.jpg/960px-A_black_wireless_computer_mouse.jpg	1	2026-11-11 19:44:20.054006+00
182d1760-28be-4483-80ca-7ea2b951199e	aaaaaaaa-aaaa-aaaa-aaaa-000000000034	https://thumb.wikimedia.org/wikipedia/commons/thumb/1/1b/Wireless_computer_keyboard_with_mouse_an_USB_receiver.jpg/960px-Wireless_computer_keyboard_with_mouse_an_USB_receiver.jpg	2	2026-11-11 19:44:20.054006+00
d2ba6e86-3df7-476e-849f-47498cb1424f	aaaaaaaa-aaaa-aaaa-aaaa-000000000034	https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a1/Wireless_Logilink_Computer_mouse_with_USB_Micro_Dongle.JPG/960px-Wireless_Logilink_Computer_mouse_with_USB_Micro_Dongle.JPG	3	2026-11-11 19:44:20.054006+00
11b7074e-f021-43e3-856f-d7fe4ccbc420	aaaaaaaa-aaaa-aaaa-aaaa-000000000035	https://upload.wikimedia.org/wikipedia/commons/0/04/XLR_cable_connectors.jpg	0	2026-11-11 19:44:20.054006+00
d142ac2e-ba8c-483a-8c76-8a8afcc13512	aaaaaaaa-aaaa-aaaa-aaaa-000000000035	https://upload.wikimedia.org/wikipedia/commons/7/7c/XLR-3_%28DMX%29_Cable_-_5ft_%2833767054668%29.jpg	1	2026-11-11 19:44:20.054006+00
c88fa28a-db11-41a5-89b9-5103c0c07220	aaaaaaaa-aaaa-aaaa-aaaa-000000000035	https://upload.wikimedia.org/wikipedia/commons/4/48/XLR-3_%28DMX%29_Cable_-_25ft_%2846920440884%29.jpg	2	2026-11-11 19:44:20.054006+00
f9a7574b-f7c8-4c95-a1ad-0347aa0d4b4a	aaaaaaaa-aaaa-aaaa-aaaa-000000000035	https://upload.wikimedia.org/wikipedia/commons/5/53/XLR-3_%28DMX%29_Cable_-_25ft_%2840677839153%29.jpg	3	2026-11-11 19:44:20.054006+00
ffed8a2a-1075-4377-99e1-ca3b24a4ddd6	dddddddd-dddd-dddd-dddd-000000000015	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f3/Yoga_Mat_Made_of_Carpet_Underlay.jpg/960px-Yoga_Mat_Made_of_Carpet_Underlay.jpg	0	2026-11-11 19:44:20.054006+00
bc0b1b88-ccc9-443b-83d3-734bc16d3d9f	dddddddd-dddd-dddd-dddd-000000000015	https://thumb.wikimedia.org/wikipedia/commons/thumb/4/40/Woman_on_a_yoga_mat_next_to_a_window_doing_lower_back_exercises_-_50401795697.jpg/960px-Woman_on_a_yoga_mat_next_to_a_window_doing_lower_back_exercises_-_50401795697.jpg	1	2026-11-11 19:44:20.054006+00
76485140-d2e7-4aaa-8884-b8a841e18a86	dddddddd-dddd-dddd-dddd-000000000015	https://thumb.wikimedia.org/wikipedia/commons/thumb/5/50/Yoga_mat_and_water_bottle_in_a_living_room.jpg/960px-Yoga_mat_and_water_bottle_in_a_living_room.jpg	2	2026-11-11 19:44:20.054006+00
a2b66d3b-f885-44f3-8449-5e1f9ff9c371	dddddddd-dddd-dddd-dddd-000000000015	https://thumb.wikimedia.org/wikipedia/commons/thumb/6/6d/Yoga_mat.jpg/960px-Yoga_mat.jpg	3	2026-11-11 19:44:20.054006+00
\.


--
-- Data for Name: products; Type: TABLE DATA; Schema: product; Owner: product_service
--

COPY product.products (id, sku, name, price_amount, price_currency, active, created_at, updated_at, version, category_id, seller_name, specifications, last_known_quantity, description, list_price, sale_price, discount_percent, thumbnail_url, video_urls, view_360_url, weight_grams, dimensions_length, dimensions_width, dimensions_height, color, material, average_rating, rating_count, review_count, rating_distribution, is_featured, seller_rating, is_seller_verified) FROM stdin;
33333333-3333-4333-8333-333333333333	SKU-MONITOR-27	27-inch 4K Monitor	449.00	USD	t	2026-11-08 12:33:29.733229+00	2026-11-11 19:44:20.054006+00	0	\N	\N	\N	\N	\N	\N	\N	\N	https://thumb.wikimedia.org/wikipedia/commons/thumb/1/18/DiRT_Rally_%E3%82%924K_HD%E3%81%A7%E3%83%99%E3%83%B3%E3%83%81%E3%83%9E%E3%83%BC%E3%82%AF_-Lenovo_ThinkCentre_M75q-1_Tiny_%2B_135W_AC_Adapter-_%2850065100496%29.jpg/960px-DiRT_Rally_%E3%82%924K_HD%E3%81%A7%E3%83%99%E3%83%B3%E3%83%81%E3%83%9E%E3%83%BC%E3%82%AF_-Lenovo_ThinkCentre_M75q-1_Tiny_%2B_135W_AC_Adapter-_%2850065100496%29.jpg	\N	\N	\N	\N	\N	\N	\N	\N	\N	0	0	\N	f	\N	f
bbbbbbbb-bbbb-bbbb-bbbb-000000000001	SKU-HOME-0001	Air Fryer	129.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111204	CookingPro	\N	\N	Air Fryer, sold by CookingPro.	149.49	129.99	13.04	https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d8/Air_fryer_interior.jpg/960px-Air_fryer_interior.jpg	\N	\N	\N	\N	\N	\N	\N	Stainless Steel/Plastic	\N	0	0	\N	f	4.60	t
bbbbbbbb-bbbb-bbbb-bbbb-000000000002	SKU-HOME-0002	Area Rug 8x10	189.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111205	RugMaster	\N	\N	Area Rug 8x10, sold by RugMaster.	218.49	189.99	13.04	https://thumb.wikimedia.org/wikipedia/commons/thumb/c/c4/Navajo_Yei_Rug_03.jpg/960px-Navajo_Yei_Rug_03.jpg	\N	\N	\N	\N	\N	\N	\N	Stainless Steel/Plastic	4.30	95	68	\N	f	4.60	t
eeeeeeee-eeee-eeee-eeee-000000000001	SKU-BOOK-0001	Atomic Habits	17.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111213	BookStore	\N	\N	Atomic Habits, sold by BookStore.	19.79	17.99	9.10	https://thumb.wikimedia.org/wikipedia/commons/thumb/8/87/Atom_odatlar_3.jpg/960px-Atom_odatlar_3.jpg	\N	\N	\N	\N	\N	\N	\N	Paper	\N	0	0	\N	f	4.40	t
aaaaaaaa-aaaa-aaaa-aaaa-000000000001	SKU-ELEC-0001	Audio Interface	179.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111203	ProAudio	\N	\N	Audio Interface, sold by ProAudio.	206.99	179.99	13.04	https://thumb.wikimedia.org/wikipedia/commons/thumb/6/62/AVID_Audio_Interface_%2848964856277%29.jpg/960px-AVID_Audio_Interface_%2848964856277%29.jpg	\N	\N	\N	\N	\N	\N	\N	Plastic/Metal	\N	0	0	\N	t	4.80	t
dddddddd-dddd-dddd-dddd-000000000001	SKU-SPRT-0001	Backpack 65L	129.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111211	OutdoorGear	\N	\N	Backpack 65L, sold by OutdoorGear.	162.49	129.99	20.00	https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a3/A_backpack_with_trekking_poles_and_shoes.jpg/960px-A_backpack_with_trekking_poles_and_shoes.jpg	\N	\N	\N	\N	\N	\N	\N	Nylon/Rubber	\N	0	0	\N	f	4.70	t
cccccccc-cccc-cccc-cccc-000000000001	SKU-FASH-0001	Bag Backpack	89.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111209	BagCo	\N	\N	Bag Backpack, sold by BagCo.	116.99	89.99	23.08	https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a3/A_backpack_with_trekking_poles_and_shoes.jpg/960px-A_backpack_with_trekking_poles_and_shoes.jpg	\N	\N	\N	\N	\N	\N	\N	Cotton/Polyester	\N	0	0	\N	f	4.50	t
bbbbbbbb-bbbb-bbbb-bbbb-000000000003	SKU-HOME-0003	Bed Frame Queen	349.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111205	BedMaster	\N	\N	Bed Frame Queen, sold by BedMaster.	419.99	349.99	16.67	https://thumb.wikimedia.org/wikipedia/commons/thumb/1/1e/Product_965_size.jpg/960px-Product_965_size.jpg	\N	\N	\N	\N	\N	\N	\N	Stainless Steel/Plastic	\N	0	0	\N	f	4.60	t
cccccccc-cccc-cccc-cccc-000000000002	SKU-FASH-0002	Belt Leather	44.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111209	BeltMaster	\N	\N	Belt Leather, sold by BeltMaster.	58.49	44.99	23.08	https://thumb.wikimedia.org/wikipedia/commons/thumb/1/18/Germany_Belt-and-Buckle-02.jpg/960px-Germany_Belt-and-Buckle-02.jpg	\N	\N	\N	\N	\N	\N	\N	Cotton/Polyester	\N	0	0	\N	f	4.50	t
dddddddd-dddd-dddd-dddd-000000000002	SKU-SPRT-0002	Bicycle Mountain	599.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111211	BikeMax	\N	\N	Bicycle Mountain, sold by BikeMax.	749.99	599.99	20.00	https://thumb.wikimedia.org/wikipedia/commons/thumb/2/2e/Mountain_biking.jpg/960px-Mountain_biking.jpg	\N	\N	\N	\N	\N	\N	\N	Nylon/Rubber	\N	0	0	\N	f	4.70	t
bbbbbbbb-bbbb-bbbb-bbbb-000000000004	SKU-HOME-0004	Blender High-Speed	99.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111204	KitchenMax	\N	\N	Blender High-Speed, sold by KitchenMax.	114.99	99.99	13.04	https://thumb.wikimedia.org/wikipedia/commons/thumb/d/dc/Pi%C3%B1a_Coladas_made_in_home_kitchen_blender_-_1.jpg/960px-Pi%C3%B1a_Coladas_made_in_home_kitchen_blender_-_1.jpg	\N	\N	\N	\N	\N	\N	\N	Stainless Steel/Plastic	4.30	95	68	\N	f	4.60	t
bbbbbbbb-bbbb-bbbb-bbbb-000000000005	SKU-HOME-0005	Bookshelf 5-Tier	99.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111205	StoragePro	\N	\N	Bookshelf 5-Tier, sold by StoragePro.	114.99	99.99	13.04	https://thumb.wikimedia.org/wikipedia/commons/thumb/c/c5/Part_of_a_bookshelf_containing_books_by_Aristotle_%281%29.jpg/960px-Part_of_a_bookshelf_containing_books_by_Aristotle_%281%29.jpg	\N	\N	\N	\N	\N	\N	\N	Stainless Steel/Plastic	\N	0	0	\N	f	4.60	t
aaaaaaaa-aaaa-aaaa-aaaa-000000000002	SKU-ELEC-0002	Boom Arm Stand	34.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111203	MountPro	\N	\N	Boom Arm Stand, sold by MountPro.	40.24	34.99	13.05	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/ff/Tripod_microphone_stand.jpg/960px-Tripod_microphone_stand.jpg	\N	\N	\N	\N	\N	\N	\N	Plastic/Metal	\N	0	0	\N	t	4.80	t
cccccccc-cccc-cccc-cccc-000000000003	SKU-FASH-0003	Boots Leather	179.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111208	BootMaster	\N	\N	Boots Leather, sold by BootMaster.	233.99	179.99	23.08	https://thumb.wikimedia.org/wikipedia/commons/thumb/1/1f/Leather_boots_men%27s.jpg/960px-Leather_boots_men%27s.jpg	\N	\N	\N	\N	\N	\N	\N	Cotton/Polyester	\N	0	0	\N	f	4.50	t
aaaaaaaa-aaaa-aaaa-aaaa-000000000003	SKU-ELEC-0003	Cable Management Kit	29.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111202	OrganizerPro	\N	\N	Cable Management Kit, sold by OrganizerPro.	34.49	29.99	13.05	https://thumb.wikimedia.org/wikipedia/commons/thumb/9/90/Cable_Management_Dell_1950.jpg/960px-Cable_Management_Dell_1950.jpg	\N	\N	\N	\N	\N	\N	\N	Plastic/Metal	4.50	120	85	\N	t	4.80	t
bbbbbbbb-bbbb-bbbb-bbbb-000000000006	SKU-HOME-0006	Ceiling Light 60W	34.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111206	LightingPro	\N	\N	Ceiling Light 60W, sold by LightingPro.	40.24	34.99	13.05	https://thumb.wikimedia.org/wikipedia/commons/thumb/8/88/Ceiling_light_fixture_in_Hofstatt_Mall%2C_Munich%2C_February_2017.jpg/960px-Ceiling_light_fixture_in_Hofstatt_Mall%2C_Munich%2C_February_2017.jpg	\N	\N	\N	\N	\N	\N	\N	Stainless Steel/Plastic	4.30	95	68	\N	f	4.60	t
bbbbbbbb-bbbb-bbbb-bbbb-000000000007	SKU-HOME-0007	Coffee Maker	79.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111204	KitchenMax	\N	\N	Coffee Maker, sold by KitchenMax.	91.99	79.99	13.04	https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b9/Our_new_coffee_maker_%282006%29_Hamilton_Beach.jpg/960px-Our_new_coffee_maker_%282006%29_Hamilton_Beach.jpg	\N	\N	\N	\N	\N	\N	\N	Stainless Steel/Plastic	\N	0	0	\N	f	4.60	t
bbbbbbbb-bbbb-bbbb-bbbb-000000000008	SKU-HOME-0008	Coffee Table	149.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111205	FurnitureCorp	\N	\N	Coffee Table, sold by FurnitureCorp.	172.49	149.99	13.04	https://thumb.wikimedia.org/wikipedia/commons/thumb/8/8c/Miele%2C_IFA_2018%2C_Berlin_%28P1070072%29.jpg/960px-Miele%2C_IFA_2018%2C_Berlin_%28P1070072%29.jpg	\N	\N	\N	\N	\N	\N	\N	Stainless Steel/Plastic	4.30	95	68	\N	f	4.60	t
aaaaaaaa-aaaa-aaaa-aaaa-000000000004	SKU-ELEC-0004	Cooling Pad Laptop	49.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111202	CoolTech	\N	\N	Cooling Pad Laptop, sold by CoolTech.	57.49	49.99	13.05	https://thumb.wikimedia.org/wikipedia/commons/thumb/1/14/Laptop_cooler.JPG/960px-Laptop_cooler.JPG	\N	\N	\N	\N	\N	\N	\N	Plastic/Metal	\N	0	0	\N	t	4.80	t
bbbbbbbb-bbbb-bbbb-bbbb-000000000009	SKU-HOME-0009	Curtain Rod Set	29.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111206	WindowDressing	\N	\N	Curtain Rod Set, sold by WindowDressing.	34.49	29.99	13.05	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/ff/French_Return_Drapery_Rods.jpg/960px-French_Return_Drapery_Rods.jpg	\N	\N	\N	\N	\N	\N	\N	Stainless Steel/Plastic	\N	0	0	\N	f	4.60	t
bbbbbbbb-bbbb-bbbb-bbbb-000000000010	SKU-HOME-0010	Cutting Board Set	24.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111204	KitchenMax	\N	\N	Cutting Board Set, sold by KitchenMax.	28.74	24.99	13.05	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/74/Pacific_oyster_from_Brofjorden_on_a_chopping_board_in_Tuntorp.jpg/960px-Pacific_oyster_from_Brofjorden_on_a_chopping_board_in_Tuntorp.jpg	\N	\N	\N	\N	\N	\N	\N	Stainless Steel/Plastic	4.30	95	68	\N	f	4.60	t
bbbbbbbb-bbbb-bbbb-bbbb-000000000011	SKU-HOME-0011	Desk Home Office	179.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111205	OfficeFurniture	\N	\N	Desk Home Office, sold by OfficeFurniture.	206.99	179.99	13.04	https://thumb.wikimedia.org/wikipedia/commons/thumb/1/13/Abbotsford_House_Study_Room.jpg/960px-Abbotsford_House_Study_Room.jpg	\N	\N	\N	\N	\N	\N	\N	Stainless Steel/Plastic	\N	0	0	\N	f	4.60	t
bbbbbbbb-bbbb-bbbb-bbbb-000000000012	SKU-HOME-0012	Desk Lamp LED	54.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111206	LightingPro	\N	\N	Desk Lamp LED, sold by LightingPro.	63.24	54.99	13.05	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/3b/Shattered_light_fixture_3.jpg/960px-Shattered_light_fixture_3.jpg	\N	\N	\N	\N	\N	\N	\N	Stainless Steel/Plastic	4.30	95	68	\N	f	4.60	t
aaaaaaaa-aaaa-aaaa-aaaa-000000000005	SKU-ELEC-0005	Desk Organizer	39.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111202	OrganizerPro	\N	\N	Desk Organizer, sold by OrganizerPro.	45.99	39.99	13.05	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/3f/Drawing%2C_Design_for_Desk_Organizer_with_Stationery%2C_1900%E2%80%9305_%28CH_18684169%29.jpg/960px-Drawing%2C_Design_for_Desk_Organizer_with_Stationery%2C_1900%E2%80%9305_%28CH_18684169%29.jpg	\N	\N	\N	\N	\N	\N	\N	Plastic/Metal	\N	0	0	\N	t	4.80	t
aaaaaaaa-aaaa-aaaa-aaaa-000000000006	SKU-ELEC-0006	Desktop PC Gaming	1899.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111201	TechStore	\N	\N	Desktop PC Gaming, sold by TechStore.	2279.99	1899.99	16.67	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/78/Gaming_computers_%281%29.jpg/960px-Gaming_computers_%281%29.jpg	\N	\N	\N	\N	\N	\N	\N	Plastic/Metal	4.50	120	85	\N	f	4.80	t
bbbbbbbb-bbbb-bbbb-bbbb-000000000013	SKU-HOME-0013	Dining Chairs Set 4	199.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111205	FurnitureCorp	\N	\N	Dining Chairs Set 4, sold by FurnitureCorp.	229.99	199.99	13.04	https://thumb.wikimedia.org/wikipedia/commons/thumb/8/85/Wright_dining_chair_side_-_HNT.jpg/960px-Wright_dining_chair_side_-_HNT.jpg	\N	\N	\N	\N	\N	\N	\N	Stainless Steel/Plastic	\N	0	0	\N	f	4.60	t
bbbbbbbb-bbbb-bbbb-bbbb-000000000014	SKU-HOME-0014	Dining Table	299.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111205	FurnitureCorp	\N	\N	Dining Table, sold by FurnitureCorp.	359.99	299.99	16.67	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/fc/Dining_table_in_Standen_House_2024-10-08.jpg/960px-Dining_table_in_Standen_House_2024-10-08.jpg	\N	\N	\N	\N	\N	\N	\N	Stainless Steel/Plastic	4.30	95	68	\N	f	4.60	t
aaaaaaaa-aaaa-aaaa-aaaa-000000000007	SKU-ELEC-0007	Docking Station	199.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111202	DockMaster	\N	\N	Docking Station, sold by DockMaster.	229.99	199.99	13.04	https://thumb.wikimedia.org/wikipedia/commons/thumb/6/60/Bottom_view_of_a_Dell_D-Dock_PD01X_docking_station.jpg/960px-Bottom_view_of_a_Dell_D-Dock_PD01X_docking_station.jpg	\N	\N	\N	\N	\N	\N	\N	Plastic/Metal	\N	0	0	\N	f	4.80	t
cccccccc-cccc-cccc-cccc-000000000004	SKU-FASH-0004	Dress Casual	59.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111207	DressStyle	\N	\N	Dress Casual, sold by DressStyle.	77.99	59.99	23.08	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f1/Woman%27s_shirt_from_Kutch%2C_Gujarat%2C_India%2C_IMA_55114.jpg/960px-Woman%27s_shirt_from_Kutch%2C_Gujarat%2C_India%2C_IMA_55114.jpg	\N	\N	\N	\N	\N	\N	\N	Cotton/Polyester	4.40	110	78	\N	f	4.50	t
dddddddd-dddd-dddd-dddd-000000000003	SKU-SPRT-0003	Dumbbells Set	79.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111210	FitGear	\N	\N	Dumbbells Set, sold by FitGear.	99.99	79.99	20.00	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f1/Colorful_dumbbells_of_various_sizes.jpg/960px-Colorful_dumbbells_of_various_sizes.jpg	\N	\N	\N	\N	\N	\N	\N	Nylon/Rubber	4.60	100	70	\N	f	4.70	t
eeeeeeee-eeee-eeee-eeee-000000000002	SKU-BOOK-0002	Dune	15.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111212	BookStore	\N	\N	Dune, sold by BookStore.	17.59	15.99	9.10	https://upload.wikimedia.org/wikipedia/commons/b/be/Dune_by_Frank_Herbert_first_edition_cover.jpg	\N	\N	\N	\N	\N	\N	\N	Paper	4.50	75	55	\N	f	4.40	t
aaaaaaaa-aaaa-aaaa-aaaa-000000000008	SKU-ELEC-0008	Earbuds Pro	199.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111203	AudioElite	\N	\N	Earbuds Pro, sold by AudioElite.	229.99	199.99	13.04	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/0a/Bose_QuietComfort_25_Acoustic_Noise_Cancelling_Headphones_with_Carry_Case.jpg/960px-Bose_QuietComfort_25_Acoustic_Noise_Cancelling_Headphones_with_Carry_Case.jpg	\N	\N	\N	\N	\N	\N	\N	Plastic/Metal	\N	0	0	\N	f	4.80	t
eeeeeeee-eeee-eeee-eeee-000000000003	SKU-BOOK-0003	Educated	18.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111213	BookStore	\N	\N	Educated, sold by BookStore.	20.89	18.99	9.10	https://upload.wikimedia.org/wikipedia/commons/0/09/Tara_Westover_receives_the_National_Humanities_Medal_on_March_21%2C_2023%2C_in_the_East_Room_of_the_White_House_-_P20230321ES-0780_%28cropped%29.jpg	\N	\N	\N	\N	\N	\N	\N	Paper	\N	0	0	\N	f	4.40	t
aaaaaaaa-aaaa-aaaa-aaaa-000000000009	SKU-ELEC-0009	External HDD 2TB	79.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111202	StoragePro	\N	\N	External HDD 2TB, sold by StoragePro.	91.99	79.99	13.04	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/38/Seagate_ST33232A_hard_disk_inner_view.jpg/960px-Seagate_ST33232A_hard_disk_inner_view.jpg	\N	\N	\N	\N	\N	\N	\N	Plastic/Metal	4.50	120	85	\N	f	4.80	t
aaaaaaaa-aaaa-aaaa-aaaa-000000000010	SKU-ELEC-0010	External SSD 1TB	129.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111202	StoragePro	\N	\N	External SSD 1TB, sold by StoragePro.	149.49	129.99	13.04	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/7d/Samsung_T5_Portable_External_SSD_%28Solid_State_Drive%29_%2843544308485%29.jpg/960px-Samsung_T5_Portable_External_SSD_%28Solid_State_Drive%29_%2843544308485%29.jpg	\N	\N	\N	\N	\N	\N	\N	Plastic/Metal	\N	0	0	\N	f	4.80	t
bbbbbbbb-bbbb-bbbb-bbbb-000000000015	SKU-HOME-0015	Floor Lamp Arc	129.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111206	LightingPro	\N	\N	Floor Lamp Arc, sold by LightingPro.	149.49	129.99	13.04	https://thumb.wikimedia.org/wikipedia/commons/thumb/4/4b/Floor_lamp_1_2016-07-06.jpg/960px-Floor_lamp_1_2016-07-06.jpg	\N	\N	\N	\N	\N	\N	\N	Stainless Steel/Plastic	\N	0	0	\N	f	4.60	t
dddddddd-dddd-dddd-dddd-000000000004	SKU-SPRT-0004	Football Soccer	24.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111211	SportsEquip	\N	\N	Football Soccer, sold by SportsEquip.	31.24	24.99	20.01	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/0c/Ryan_Valentine_scores.jpg/960px-Ryan_Valentine_scores.jpg	\N	\N	\N	\N	\N	\N	\N	Nylon/Rubber	\N	0	0	\N	f	4.70	t
cccccccc-cccc-cccc-cccc-000000000005	SKU-FASH-0005	Gloves Winter	24.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111209	AccessoryHub	\N	\N	Gloves Winter, sold by AccessoryHub.	32.49	24.99	23.08	https://thumb.wikimedia.org/wikipedia/commons/thumb/c/c8/Colour-blocking_with_burgundy_gloves_and_tights_to_contrast_with_an_amber_dress_and_beret.jpg/960px-Colour-blocking_with_burgundy_gloves_and_tights_to_contrast_with_an_amber_dress_and_beret.jpg	\N	\N	\N	\N	\N	\N	\N	Cotton/Polyester	\N	0	0	\N	f	4.50	t
aaaaaaaa-aaaa-aaaa-aaaa-000000000011	SKU-ELEC-0011	HDMI Cable 2.1	15.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111202	CableGear	\N	\N	HDMI Cable 2.1, sold by CableGear.	18.39	15.99	13.05	https://thumb.wikimedia.org/wikipedia/commons/thumb/e/e2/HDMI_Cable_1.JPG/960px-HDMI_Cable_1.JPG	\N	\N	\N	\N	\N	\N	\N	Plastic/Metal	\N	0	0	\N	f	4.80	t
cccccccc-cccc-cccc-cccc-000000000006	SKU-FASH-0006	Hat Baseball	24.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111209	HeadwearPro	\N	\N	Hat Baseball, sold by HeadwearPro.	32.49	24.99	23.08	https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b4/Madras_plaid_baseball_cap_by_Polo_Ralph_Lauren.jpg/960px-Madras_plaid_baseball_cap_by_Polo_Ralph_Lauren.jpg	\N	\N	\N	\N	\N	\N	\N	Cotton/Polyester	\N	0	0	\N	f	4.50	t
aaaaaaaa-aaaa-aaaa-aaaa-000000000012	SKU-ELEC-0012	Headphones Gaming	99.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111203	SoundMax	\N	\N	Headphones Gaming, sold by SoundMax.	114.99	99.99	13.04	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/78/Gaming_computers_%281%29.jpg/960px-Gaming_computers_%281%29.jpg	\N	\N	\N	\N	\N	\N	\N	Plastic/Metal	4.50	120	85	\N	f	4.80	t
aaaaaaaa-aaaa-aaaa-aaaa-000000000013	SKU-ELEC-0013	Headphones Wireless	199.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111203	SoundMax	\N	\N	Headphones Wireless, sold by SoundMax.	229.99	199.99	13.04	https://thumb.wikimedia.org/wikipedia/commons/thumb/5/56/2023_S%C5%82uchawki_Sony_WI-XB400_%281%29.jpg/960px-2023_S%C5%82uchawki_Sony_WI-XB400_%281%29.jpg	\N	\N	\N	\N	\N	\N	\N	Plastic/Metal	\N	0	0	\N	f	4.80	t
dddddddd-dddd-dddd-dddd-000000000005	SKU-SPRT-0005	Hiking Boots	149.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111211	OutdoorGear	\N	\N	Hiking Boots, sold by OutdoorGear.	187.49	149.99	20.00	https://thumb.wikimedia.org/wikipedia/commons/thumb/8/81/Jack_Wolfskin%2C_OutDoor_2018%2C_Friedrichshafen_%281X7A0425%29.jpg/960px-Jack_Wolfskin%2C_OutDoor_2018%2C_Friedrichshafen_%281X7A0425%29.jpg	\N	\N	\N	\N	\N	\N	\N	Nylon/Rubber	\N	0	0	\N	f	4.70	t
cccccccc-cccc-cccc-cccc-000000000007	SKU-FASH-0007	Hoodie Fleece	49.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111207	CasualWear	\N	\N	Hoodie Fleece, sold by CasualWear.	64.99	49.99	23.08	https://thumb.wikimedia.org/wikipedia/commons/thumb/a/ad/Hooded_crow_%E2%80%93_Wrona_zwyczajna_%E2%80%93_Corvus_corone_cornix.jpg/960px-Hooded_crow_%E2%80%93_Wrona_zwyczajna_%E2%80%93_Corvus_corone_cornix.jpg	\N	\N	\N	\N	\N	\N	\N	Cotton/Polyester	\N	0	0	\N	f	4.50	t
bbbbbbbb-bbbb-bbbb-bbbb-000000000016	SKU-HOME-0016	Instant Pot	99.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111204	PressureCooking	\N	\N	Instant Pot, sold by PressureCooking.	114.99	99.99	13.04	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/31/Instant_Pot_DUO60_pressure_cooker.jpg/960px-Instant_Pot_DUO60_pressure_cooker.jpg	\N	\N	\N	\N	\N	\N	\N	Stainless Steel/Plastic	4.30	95	68	\N	f	4.60	t
cccccccc-cccc-cccc-cccc-000000000008	SKU-FASH-0008	Jacket Bomber	129.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111207	OuterwearPro	\N	\N	Jacket Bomber, sold by OuterwearPro.	168.99	129.99	23.08	https://upload.wikimedia.org/wikipedia/commons/d/df/MA-1_Jacket_in_petrol.jpg	\N	\N	\N	\N	\N	\N	\N	Cotton/Polyester	4.40	110	78	\N	f	4.50	t
cccccccc-cccc-cccc-cccc-000000000009	SKU-FASH-0009	Jeans Slim Fit	69.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111207	DenimCo	\N	\N	Jeans Slim Fit, sold by DenimCo.	90.99	69.99	23.08	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/30/Max_Giesinger_-_2021-09-12_-_Mannheim_-_Sven_Mandel_-_1D_X_MK_II_-_0229_-_B70I3683_%28re-developed%29.jpg/960px-Max_Giesinger_-_2021-09-12_-_Mannheim_-_Sven_Mandel_-_1D_X_MK_II_-_0229_-_B70I3683_%28re-developed%29.jpg	\N	\N	\N	\N	\N	\N	\N	Cotton/Polyester	\N	0	0	\N	f	4.50	t
dddddddd-dddd-dddd-dddd-000000000006	SKU-SPRT-0006	Jump Rope	14.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111210	FitGear	\N	\N	Jump Rope, sold by FitGear.	18.74	14.99	20.01	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/77/Ghanaian_kid_%28skipping_rope%29_02.jpg/960px-Ghanaian_kid_%28skipping_rope%29_02.jpg	\N	\N	\N	\N	\N	\N	\N	Nylon/Rubber	4.60	100	70	\N	f	4.70	t
dddddddd-dddd-dddd-dddd-000000000007	SKU-SPRT-0007	Kettlebell	49.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111210	FitGear	\N	\N	Kettlebell, sold by FitGear.	62.49	49.99	20.00	https://thumb.wikimedia.org/wikipedia/commons/thumb/8/87/Kettlebell_truebalance_romeike.jpg/960px-Kettlebell_truebalance_romeike.jpg	\N	\N	\N	\N	\N	\N	\N	Nylon/Rubber	\N	0	0	\N	f	4.70	t
bbbbbbbb-bbbb-bbbb-bbbb-000000000017	SKU-HOME-0017	Knife Set 7-Piece	39.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111204	CutleryPro	\N	\N	Knife Set 7-Piece, sold by CutleryPro.	45.99	39.99	13.05	https://thumb.wikimedia.org/wikipedia/commons/thumb/2/20/Leather_knife_case_and_a_set_of_knives_by_Les_Cuirs_d%27Agathe_and_La_Forge_du_Lion_du_Nord_%28DSC07523%29.jpg/960px-Leather_knife_case_and_a_set_of_knives_by_Les_Cuirs_d%27Agathe_and_La_Forge_du_Lion_du_Nord_%28DSC07523%29.jpg	\N	\N	\N	\N	\N	\N	\N	Stainless Steel/Plastic	\N	0	0	\N	f	4.60	t
aaaaaaaa-aaaa-aaaa-aaaa-000000000014	SKU-ELEC-0014	Laptop Pro 13	999.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111201	TechStore	\N	\N	Laptop Pro 13, sold by TechStore.	1199.99	999.99	16.67	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/0e/IBM_Thinkpad_R51.jpg/960px-IBM_Thinkpad_R51.jpg	\N	\N	\N	\N	\N	\N	\N	Plastic/Metal	\N	0	0	\N	f	4.80	t
aaaaaaaa-aaaa-aaaa-aaaa-000000000015	SKU-ELEC-0015	Laptop Pro 15	1299.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111201	TechStore	\N	\N	Laptop Pro 15, sold by TechStore.	1559.99	1299.99	16.67	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/0e/IBM_Thinkpad_R51.jpg/960px-IBM_Thinkpad_R51.jpg	\N	\N	\N	\N	\N	\N	\N	Plastic/Metal	4.50	120	85	\N	f	4.80	t
aaaaaaaa-aaaa-aaaa-aaaa-000000000016	SKU-ELEC-0016	Laptop Stand	35.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111202	ErgoPro	\N	\N	Laptop Stand, sold by ErgoPro.	41.39	35.99	13.05	https://thumb.wikimedia.org/wikipedia/commons/thumb/1/14/Laptop_cooler.JPG/960px-Laptop_cooler.JPG	\N	\N	\N	\N	\N	\N	\N	Plastic/Metal	\N	0	0	\N	f	4.80	t
aaaaaaaa-aaaa-aaaa-aaaa-000000000017	SKU-ELEC-0017	Laptop Ultrabook	1399.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111201	TechStore	\N	\N	Laptop Ultrabook, sold by TechStore.	1679.99	1399.99	16.67	https://thumb.wikimedia.org/wikipedia/commons/thumb/5/53/Asus_x21_ultrabook.jpg/960px-Asus_x21_ultrabook.jpg	\N	\N	\N	\N	\N	\N	\N	Plastic/Metal	\N	0	0	\N	f	4.80	t
aaaaaaaa-aaaa-aaaa-aaaa-000000000018	SKU-ELEC-0018	MIDI Keyboard	149.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111203	MusicTech	\N	\N	MIDI Keyboard, sold by MusicTech.	172.49	149.99	13.04	https://thumb.wikimedia.org/wikipedia/commons/thumb/e/ec/Kurzweil_Synthesizer_and_MIDI_Keyboard_Controller_PC3K6.jpg/960px-Kurzweil_Synthesizer_and_MIDI_Keyboard_Controller_PC3K6.jpg	\N	\N	\N	\N	\N	\N	\N	Plastic/Metal	4.50	120	85	\N	f	4.80	t
bbbbbbbb-bbbb-bbbb-bbbb-000000000018	SKU-HOME-0018	Mattress Queen	499.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111205	MattressPro	\N	\N	Mattress Queen, sold by MattressPro.	599.99	499.99	16.67	https://thumb.wikimedia.org/wikipedia/commons/thumb/1/11/Miranda_en_la_Carraca_by_Arturo_Michelena.jpg/960px-Miranda_en_la_Carraca_by_Arturo_Michelena.jpg	\N	\N	\N	\N	\N	\N	\N	Stainless Steel/Plastic	4.30	95	68	\N	f	4.60	t
11111111-1111-4111-8111-111111111111	SKU-KEYBOARD-01	Mechanical Keyboard	129.99	USD	t	2026-11-08 12:33:29.733229+00	2026-11-11 19:44:20.054006+00	0	\N	\N	\N	\N	\N	\N	\N	\N	https://thumb.wikimedia.org/wikipedia/commons/thumb/5/5a/Mechanical_Keyboard.jpg/960px-Mechanical_Keyboard.jpg	\N	\N	\N	\N	\N	\N	\N	\N	\N	0	0	\N	f	\N	f
aaaaaaaa-aaaa-aaaa-aaaa-000000000019	SKU-ELEC-0019	Mechanical Keyboard	129.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111202	KeyMaster	\N	\N	Mechanical Keyboard, sold by KeyMaster.	149.49	129.99	13.04	https://thumb.wikimedia.org/wikipedia/commons/thumb/5/5a/Mechanical_Keyboard.jpg/960px-Mechanical_Keyboard.jpg	\N	\N	\N	\N	\N	\N	\N	Plastic/Metal	\N	0	0	\N	f	4.80	t
dddddddd-dddd-dddd-dddd-000000000008	SKU-SPRT-0008	Medicine Ball	34.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111210	FitGear	\N	\N	Medicine Ball, sold by FitGear.	43.74	34.99	20.00	https://thumb.wikimedia.org/wikipedia/commons/thumb/8/86/Medicine_Ball_2_2017-01-13.jpg/960px-Medicine_Ball_2_2017-01-13.jpg	\N	\N	\N	\N	\N	\N	\N	Nylon/Rubber	\N	0	0	\N	f	4.70	t
aaaaaaaa-aaaa-aaaa-aaaa-000000000020	SKU-ELEC-0020	Microphone USB	99.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111203	AudioElite	\N	\N	Microphone USB, sold by AudioElite.	114.99	99.99	13.04	https://thumb.wikimedia.org/wikipedia/commons/thumb/e/e2/Rode_NT-USB.jpg/960px-Rode_NT-USB.jpg	\N	\N	\N	\N	\N	\N	\N	Plastic/Metal	\N	0	0	\N	f	4.80	t
bbbbbbbb-bbbb-bbbb-bbbb-000000000019	SKU-HOME-0019	Microwave 1000W	89.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111204	KitchenMax	\N	\N	Microwave 1000W, sold by KitchenMax.	103.49	89.99	13.04	https://thumb.wikimedia.org/wikipedia/commons/thumb/b/bb/Microwave_Oven.jpg/960px-Microwave_Oven.jpg	\N	\N	\N	\N	\N	\N	\N	Stainless Steel/Plastic	\N	0	0	\N	f	4.60	t
aaaaaaaa-aaaa-aaaa-aaaa-000000000021	SKU-ELEC-0021	Monitor 1080p 27"	299.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111201	DisplayCorp	\N	\N	Monitor 1080p 27", sold by DisplayCorp.	344.99	299.99	13.04	https://thumb.wikimedia.org/wikipedia/commons/thumb/c/c3/Amiga500_system.jpg/960px-Amiga500_system.jpg	\N	\N	\N	\N	\N	\N	\N	Plastic/Metal	4.50	120	85	\N	f	4.80	t
aaaaaaaa-aaaa-aaaa-aaaa-000000000022	SKU-ELEC-0022	Monitor 4K 32"	799.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111201	DisplayCorp	\N	\N	Monitor 4K 32", sold by DisplayCorp.	959.99	799.99	16.67	https://thumb.wikimedia.org/wikipedia/commons/thumb/1/18/DiRT_Rally_%E3%82%924K_HD%E3%81%A7%E3%83%99%E3%83%B3%E3%83%81%E3%83%9E%E3%83%BC%E3%82%AF_-Lenovo_ThinkCentre_M75q-1_Tiny_%2B_135W_AC_Adapter-_%2850065100496%29.jpg/960px-DiRT_Rally_%E3%82%924K_HD%E3%81%A7%E3%83%99%E3%83%B3%E3%83%81%E3%83%9E%E3%83%BC%E3%82%AF_-Lenovo_ThinkCentre_M75q-1_Tiny_%2B_135W_AC_Adapter-_%2850065100496%29.jpg	\N	\N	\N	\N	\N	\N	\N	Plastic/Metal	\N	0	0	\N	f	4.80	t
aaaaaaaa-aaaa-aaaa-aaaa-000000000023	SKU-ELEC-0023	Monitor Light Bar	59.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111202	ErgoPro	\N	\N	Monitor Light Bar, sold by ErgoPro.	68.99	59.99	13.05	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/0c/WMID-EA2706%2C_Medieval%2C_Bar_mount_and_pendent_loop_%28FindID_526918%29.jpg/960px-WMID-EA2706%2C_Medieval%2C_Bar_mount_and_pendent_loop_%28FindID_526918%29.jpg	\N	\N	\N	\N	\N	\N	\N	Plastic/Metal	\N	0	0	\N	f	4.80	t
aaaaaaaa-aaaa-aaaa-aaaa-000000000024	SKU-ELEC-0024	Mouse Pad Gaming	29.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111202	GamingPro	\N	\N	Mouse Pad Gaming, sold by GamingPro.	34.49	29.99	13.05	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/3f/SNES-Mouse-and-Pad.jpg/960px-SNES-Mouse-and-Pad.jpg	\N	\N	\N	\N	\N	\N	\N	Plastic/Metal	4.50	120	85	\N	f	4.80	t
55555555-5555-4555-8555-555555555555	SKU-HEADSET-01	Noise-Cancelling Headset	279.00	USD	f	2026-11-08 12:33:29.733229+00	2026-11-11 19:44:20.054006+00	0	\N	\N	\N	\N	\N	\N	\N	\N	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/0a/Bose_QuietComfort_25_Acoustic_Noise_Cancelling_Headphones_with_Carry_Case.jpg/960px-Bose_QuietComfort_25_Acoustic_Noise_Cancelling_Headphones_with_Carry_Case.jpg	\N	\N	\N	\N	\N	\N	\N	\N	\N	0	0	\N	f	\N	f
bbbbbbbb-bbbb-bbbb-bbbb-000000000020	SKU-HOME-0020	Office Chair Ergonomic	249.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111205	OfficeFurniture	\N	\N	Office Chair Ergonomic, sold by OfficeFurniture.	299.99	249.99	16.67	https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d4/Buerostuhl_%28fcm%29.jpg/960px-Buerostuhl_%28fcm%29.jpg	\N	\N	\N	\N	\N	\N	\N	Stainless Steel/Plastic	4.30	95	68	\N	f	4.60	t
cccccccc-cccc-cccc-cccc-000000000010	SKU-FASH-0010	Pants Work	79.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111207	BusinessWear	\N	\N	Pants Work, sold by BusinessWear.	103.99	79.99	23.08	https://thumb.wikimedia.org/wikipedia/commons/thumb/6/62/%E6%A2%9D%E7%B4%8B%E7%87%88%E7%B1%A0%E8%A4%B2.jpg/960px-%E6%A2%9D%E7%B4%8B%E7%87%88%E7%B1%A0%E8%A4%B2.jpg	\N	\N	\N	\N	\N	\N	\N	Cotton/Polyester	\N	0	0	\N	f	4.50	t
aaaaaaaa-aaaa-aaaa-aaaa-000000000025	SKU-ELEC-0025	Phone Charger Fast	29.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111202	ChargeMax	\N	\N	Phone Charger Fast, sold by ChargeMax.	34.49	29.99	13.05	https://thumb.wikimedia.org/wikipedia/commons/thumb/a/ae/Phone_charger_2026.jpg/960px-Phone_charger_2026.jpg	\N	\N	\N	\N	\N	\N	\N	Plastic/Metal	\N	0	0	\N	f	4.80	t
aaaaaaaa-aaaa-aaaa-aaaa-000000000026	SKU-ELEC-0026	Pop Filter	19.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111203	AudioElite	\N	\N	Pop Filter, sold by AudioElite.	22.99	19.99	13.05	https://thumb.wikimedia.org/wikipedia/commons/thumb/1/17/Ea_f20_microphone.jpg/960px-Ea_f20_microphone.jpg	\N	\N	\N	\N	\N	\N	\N	Plastic/Metal	\N	0	0	\N	f	4.80	t
bbbbbbbb-bbbb-bbbb-bbbb-000000000021	SKU-HOME-0021	Pots & Pans Set	59.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111204	CookwarePro	\N	\N	Pots & Pans Set, sold by CookwarePro.	68.99	59.99	13.05	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/7c/020210821_Copper_cookware%2C_early_20th_century%2C_bourgeois_kitchen%2C_Galicia%2C_Sanok.jpg/960px-020210821_Copper_cookware%2C_early_20th_century%2C_bourgeois_kitchen%2C_Galicia%2C_Sanok.jpg	\N	\N	\N	\N	\N	\N	\N	Stainless Steel/Plastic	\N	0	0	\N	f	4.60	t
aaaaaaaa-aaaa-aaaa-aaaa-000000000027	SKU-ELEC-0027	Power Bank 20000mAh	39.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111202	PowerCell	\N	\N	Power Bank 20000mAh, sold by PowerCell.	45.99	39.99	13.05	https://thumb.wikimedia.org/wikipedia/commons/thumb/4/4a/Turbo_Akku_Vac_Easy_Home_VC_618WP_-_battery_pack-1376.jpg/960px-Turbo_Akku_Vac_Easy_Home_VC_618WP_-_battery_pack-1376.jpg	\N	\N	\N	\N	\N	\N	\N	Plastic/Metal	4.50	120	85	\N	f	4.80	t
aaaaaaaa-aaaa-aaaa-aaaa-000000000028	SKU-ELEC-0028	RGB Lighting Kit	44.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111202	LightGear	\N	\N	RGB Lighting Kit, sold by LightGear.	51.74	44.99	13.05	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/06/RGB_LED_Strip.jpg/960px-RGB_LED_Strip.jpg	\N	\N	\N	\N	\N	\N	\N	Plastic/Metal	\N	0	0	\N	f	4.80	t
dddddddd-dddd-dddd-dddd-000000000009	SKU-SPRT-0009	Resistance Bands	19.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111210	FitGear	\N	\N	Resistance Bands, sold by FitGear.	24.99	19.99	20.01	https://thumb.wikimedia.org/wikipedia/commons/thumb/4/40/Grid_Layout_of_Blue_Barry%27s_Exercise_Mats_and_Resistance_Bands.jpg/960px-Grid_Layout_of_Blue_Barry%27s_Exercise_Mats_and_Resistance_Bands.jpg	\N	\N	\N	\N	\N	\N	\N	Nylon/Rubber	4.60	100	70	\N	f	4.70	t
cccccccc-cccc-cccc-cccc-000000000011	SKU-FASH-0011	Sandals Comfort	49.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111208	CasualShoes	\N	\N	Sandals Comfort, sold by CasualShoes.	64.99	49.99	23.08	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/7c/2015_K%C5%82odzko%2C_ul._Dusznicka%2C_myjnia_samochodowa_02.jpg/960px-2015_K%C5%82odzko%2C_ul._Dusznicka%2C_myjnia_samochodowa_02.jpg	\N	\N	\N	\N	\N	\N	\N	Cotton/Polyester	\N	0	0	\N	f	4.50	t
cccccccc-cccc-cccc-cccc-000000000012	SKU-FASH-0012	Scarf Winter	34.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111209	AccessoryHub	\N	\N	Scarf Winter, sold by AccessoryHub.	45.49	34.99	23.08	https://thumb.wikimedia.org/wikipedia/commons/thumb/5/54/Colored_silk_scarf_from_India_01.jpg/960px-Colored_silk_scarf_from_India_01.jpg	\N	\N	\N	\N	\N	\N	\N	Cotton/Polyester	4.40	110	78	\N	f	4.50	t
cccccccc-cccc-cccc-cccc-000000000013	SKU-FASH-0013	Shoes Formal	149.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111208	FormalShoes	\N	\N	Shoes Formal, sold by FormalShoes.	194.99	149.99	23.08	https://thumb.wikimedia.org/wikipedia/commons/thumb/6/65/Pristine_abandoned_dress_shoes%2C_Rovisco_Pais_Avenue%2C_Lisbon%2C_Portugal_julesvernex2.jpg/960px-Pristine_abandoned_dress_shoes%2C_Rovisco_Pais_Avenue%2C_Lisbon%2C_Portugal_julesvernex2.jpg	\N	\N	\N	\N	\N	\N	\N	Cotton/Polyester	\N	0	0	\N	f	4.50	t
cccccccc-cccc-cccc-cccc-000000000014	SKU-FASH-0014	Shorts Swim	39.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111207	BeachGear	\N	\N	Shorts Swim, sold by BeachGear.	51.99	39.99	23.08	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f3/Sky_blue_board_shorts.jpg/960px-Sky_blue_board_shorts.jpg	\N	\N	\N	\N	\N	\N	\N	Cotton/Polyester	\N	0	0	\N	f	4.50	t
dddddddd-dddd-dddd-dddd-000000000010	SKU-SPRT-0010	Skateboard Pro	99.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111211	SkateGear	\N	\N	Skateboard Pro, sold by SkateGear.	124.99	99.99	20.00	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/33/Zawody_w_skateboardingu_Street_of_Mediateka%2C_Zag%C5%82%C4%99biowska_Mediateka%2C_ulica_Ko%C5%9Bcielna%2C_Sosnowiec%2C_22_czerwca_2024_01.jpg/960px-Zawody_w_skateboardingu_Street_of_Mediateka%2C_Zag%C5%82%C4%99biowska_Mediateka%2C_ulica_Ko%C5%9Bcielna%2C_Sosnowiec%2C_22_czerwca_2024_01.jpg	\N	\N	\N	\N	\N	\N	\N	Nylon/Rubber	\N	0	0	\N	f	4.70	t
dddddddd-dddd-dddd-dddd-000000000011	SKU-SPRT-0011	Sleeping Bag	79.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111211	OutdoorGear	\N	\N	Sleeping Bag, sold by OutdoorGear.	99.99	79.99	20.00	https://thumb.wikimedia.org/wikipedia/commons/thumb/2/28/Nomad%2C_OutDoor_2018%2C_Friedrichshafen_%281X7A0320%29.jpg/960px-Nomad%2C_OutDoor_2018%2C_Friedrichshafen_%281X7A0320%29.jpg	\N	\N	\N	\N	\N	\N	\N	Nylon/Rubber	\N	0	0	\N	f	4.70	t
cccccccc-cccc-cccc-cccc-000000000015	SKU-FASH-0015	Sneakers Running	119.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111208	SneakerCo	\N	\N	Sneakers Running, sold by SneakerCo.	155.99	119.99	23.08	https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b8/Mizuno_Wave_Ibuki_2.jpg/960px-Mizuno_Wave_Ibuki_2.jpg	\N	\N	\N	\N	\N	\N	\N	Cotton/Polyester	\N	0	0	\N	f	4.50	t
cccccccc-cccc-cccc-cccc-000000000016	SKU-FASH-0016	Socks Pack 6	14.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111209	SockHub	\N	\N	Socks Pack 6, sold by SockHub.	19.49	14.99	23.09	https://thumb.wikimedia.org/wikipedia/commons/thumb/1/18/Socks_the_Cat_Explores.jpg/960px-Socks_the_Cat_Explores.jpg	\N	\N	\N	\N	\N	\N	\N	Cotton/Polyester	4.40	110	78	\N	f	4.50	t
bbbbbbbb-bbbb-bbbb-bbbb-000000000022	SKU-HOME-0022	Sofa 3-Seater	599.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111205	FurnitureCorp	\N	\N	Sofa 3-Seater, sold by FurnitureCorp.	719.99	599.99	16.67	https://thumb.wikimedia.org/wikipedia/commons/thumb/b/be/Reception_lounge_at_Amantaka_luxury_Resort_%26_Hotel_at_blue_hour_in_Luang_Prabang_Laos.jpg/960px-Reception_lounge_at_Amantaka_luxury_Resort_%26_Hotel_at_blue_hour_in_Luang_Prabang_Laos.jpg	\N	\N	\N	\N	\N	\N	\N	Stainless Steel/Plastic	4.30	95	68	\N	f	4.60	t
aaaaaaaa-aaaa-aaaa-aaaa-000000000029	SKU-ELEC-0029	Speaker Bluetooth	49.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111203	SoundMax	\N	\N	Speaker Bluetooth, sold by SoundMax.	57.49	49.99	13.05	https://thumb.wikimedia.org/wikipedia/commons/thumb/6/67/JBL_Flip_3_bluetooth_speaker_%28DSCF2653%29.jpg/960px-JBL_Flip_3_bluetooth_speaker_%28DSCF2653%29.jpg	\N	\N	\N	\N	\N	\N	\N	Plastic/Metal	\N	0	0	\N	f	4.80	t
dddddddd-dddd-dddd-dddd-000000000012	SKU-SPRT-0012	Stationary Bike	449.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111210	FitGear	\N	\N	Stationary Bike, sold by FitGear.	562.49	449.99	20.00	https://thumb.wikimedia.org/wikipedia/commons/thumb/c/c1/Magnetic_resistance_stationary_bicycle_exercise_bike.JPG/960px-Magnetic_resistance_stationary_bicycle_exercise_bike.JPG	\N	\N	\N	\N	\N	\N	\N	Nylon/Rubber	4.60	100	70	\N	f	4.70	t
aaaaaaaa-aaaa-aaaa-aaaa-000000000030	SKU-ELEC-0030	Studio Monitors Pair	299.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111203	ProAudio	\N	\N	Studio Monitors Pair, sold by ProAudio.	344.99	299.99	13.04	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/0f/Quested_HM412_main_monitor%2C_Studio_9000%2C_PatchWerk_Recording_Studios%2C_2007.jpg/960px-Quested_HM412_main_monitor%2C_Studio_9000%2C_PatchWerk_Recording_Studios%2C_2007.jpg	\N	\N	\N	\N	\N	\N	\N	Plastic/Metal	4.50	120	85	\N	f	4.80	t
cccccccc-cccc-cccc-cccc-000000000017	SKU-FASH-0017	Sunglasses	79.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111209	EyeWearPro	\N	\N	Sunglasses, sold by EyeWearPro.	103.99	79.99	23.08	https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b5/2_%D8%A7%D9%84%D8%AD%D8%AF%D8%A7%D8%AF.jpg/960px-2_%D8%A7%D9%84%D8%AD%D8%AF%D8%A7%D8%AF.jpg	\N	\N	\N	\N	\N	\N	\N	Cotton/Polyester	\N	0	0	\N	f	4.50	t
cccccccc-cccc-cccc-cccc-000000000018	SKU-FASH-0018	T-Shirt Cotton	24.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111207	ClothingHub	\N	\N	T-Shirt Cotton, sold by ClothingHub.	32.49	24.99	23.08	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/30/Max_Giesinger_-_2021-09-12_-_Mannheim_-_Sven_Mandel_-_1D_X_MK_II_-_0229_-_B70I3683_%28re-developed%29.jpg/960px-Max_Giesinger_-_2021-09-12_-_Mannheim_-_Sven_Mandel_-_1D_X_MK_II_-_0229_-_B70I3683_%28re-developed%29.jpg	\N	\N	\N	\N	\N	\N	\N	Cotton/Polyester	\N	0	0	\N	f	4.50	t
bbbbbbbb-bbbb-bbbb-bbbb-000000000023	SKU-HOME-0023	Table Lamp	69.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111206	LightingPro	\N	\N	Table Lamp, sold by LightingPro.	80.49	69.99	13.05	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/3b/Shattered_light_fixture_3.jpg/960px-Shattered_light_fixture_3.jpg	\N	\N	\N	\N	\N	\N	\N	Stainless Steel/Plastic	\N	0	0	\N	f	4.60	t
dddddddd-dddd-dddd-dddd-000000000013	SKU-SPRT-0013	Tent Camping 4-Person	199.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111211	OutdoorGear	\N	\N	Tent Camping 4-Person, sold by OutdoorGear.	249.99	199.99	20.00	https://thumb.wikimedia.org/wikipedia/commons/thumb/e/e9/Campement_-_20150802_15h44_%2810723%29.jpg/960px-Campement_-_20150802_15h44_%2810723%29.jpg	\N	\N	\N	\N	\N	\N	\N	Nylon/Rubber	\N	0	0	\N	f	4.70	t
eeeeeeee-eeee-eeee-eeee-000000000004	SKU-BOOK-0004	The Midnight Library	16.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111212	BookStore	\N	\N	The Midnight Library, sold by BookStore.	18.69	16.99	9.10	https://thumb.wikimedia.org/wikipedia/commons/thumb/5/50/International_books_at_Kent_Library.jpg/960px-International_books_at_Kent_Library.jpg	\N	\N	\N	\N	\N	\N	\N	Paper	4.50	75	55	\N	f	4.40	t
eeeeeeee-eeee-eeee-eeee-000000000005	SKU-BOOK-0005	The Silent Patient	16.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111212	BookStore	\N	\N	The Silent Patient, sold by BookStore.	18.69	16.99	9.10	https://thumb.wikimedia.org/wikipedia/commons/thumb/3/3d/Doctors_and_patients%3B_or%2C_Anecdotes_of_the_medical_world_and_curiosities_of_medicine_%281873%29_%2814762122486%29.jpg/960px-Doctors_and_patients%3B_or%2C_Anecdotes_of_the_medical_world_and_curiosities_of_medicine_%281873%29_%2814762122486%29.jpg	\N	\N	\N	\N	\N	\N	\N	Paper	\N	0	0	\N	f	4.40	t
bbbbbbbb-bbbb-bbbb-bbbb-000000000024	SKU-HOME-0024	Toaster 4-Slice	49.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111204	BreakfastGear	\N	\N	Toaster 4-Slice, sold by BreakfastGear.	57.49	49.99	13.05	https://thumb.wikimedia.org/wikipedia/commons/thumb/4/47/Hoteltoaster_01_%28sk%29.jpg/960px-Hoteltoaster_01_%28sk%29.jpg	\N	\N	\N	\N	\N	\N	\N	Stainless Steel/Plastic	4.30	95	68	\N	f	4.60	t
dddddddd-dddd-dddd-dddd-000000000014	SKU-SPRT-0014	Treadmill Folding	599.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111210	FitGear	\N	\N	Treadmill Folding, sold by FitGear.	749.99	599.99	20.00	https://thumb.wikimedia.org/wikipedia/commons/thumb/2/2f/Cyberith_VidMill_Video_Production_Treadmill_used_for_Volumetric_Capture.jpg/960px-Cyberith_VidMill_Video_Production_Treadmill_used_for_Volumetric_Capture.jpg	\N	\N	\N	\N	\N	\N	\N	Nylon/Rubber	\N	0	0	\N	f	4.70	t
aaaaaaaa-aaaa-aaaa-aaaa-000000000031	SKU-ELEC-0031	USB Flash Drive 64GB	19.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111202	MemoryMax	\N	\N	USB Flash Drive 64GB, sold by MemoryMax.	22.99	19.99	13.05	https://thumb.wikimedia.org/wikipedia/commons/thumb/1/19/2023_Pendrive%27y_Verbatim_Store_%E2%80%98n%E2%80%99_Click_16_GB_%281%29.jpg/960px-2023_Pendrive%27y_Verbatim_Store_%E2%80%98n%E2%80%99_Click_16_GB_%281%29.jpg	\N	\N	\N	\N	\N	\N	\N	Plastic/Metal	\N	0	0	\N	f	4.80	t
44444444-4444-4444-8444-444444444444	SKU-DOCK-USBC	USB-C Docking Station	199.95	USD	t	2026-11-08 12:33:29.733229+00	2026-11-11 19:44:20.054006+00	0	\N	\N	\N	\N	\N	\N	\N	\N	https://thumb.wikimedia.org/wikipedia/commons/thumb/6/60/Bottom_view_of_a_Dell_D-Dock_PD01X_docking_station.jpg/960px-Bottom_view_of_a_Dell_D-Dock_PD01X_docking_station.jpg	\N	\N	\N	\N	\N	\N	\N	\N	\N	0	0	\N	f	\N	f
aaaaaaaa-aaaa-aaaa-aaaa-000000000032	SKU-ELEC-0032	USB-C Hub	79.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111202	HubTech	\N	\N	USB-C Hub, sold by HubTech.	91.99	79.99	13.04	https://thumb.wikimedia.org/wikipedia/commons/thumb/0/04/2023_Hub_USB_2.0.jpg/960px-2023_Hub_USB_2.0.jpg	\N	\N	\N	\N	\N	\N	\N	Plastic/Metal	\N	0	0	\N	f	4.80	t
bbbbbbbb-bbbb-bbbb-bbbb-000000000025	SKU-HOME-0025	Wall Lamp LED	44.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111206	LightingPro	\N	\N	Wall Lamp LED, sold by LightingPro.	51.74	44.99	13.05	https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b5/Philips_medical_wall_lamp_designed_by_Charlotte_Perriand.jpg/960px-Philips_medical_wall_lamp_designed_by_Charlotte_Perriand.jpg	\N	\N	\N	\N	\N	\N	\N	Stainless Steel/Plastic	\N	0	0	\N	f	4.60	t
cccccccc-cccc-cccc-cccc-000000000019	SKU-FASH-0019	Wallet Slim	29.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111209	AccessoryHub	\N	\N	Wallet Slim, sold by AccessoryHub.	38.99	29.99	23.08	https://thumb.wikimedia.org/wikipedia/commons/thumb/c/ce/Leather_bi-fold_wallet_by_Eugene_Pik_%282021%29_04.jpg/960px-Leather_bi-fold_wallet_by_Eugene_Pik_%282021%29_04.jpg	\N	\N	\N	\N	\N	\N	\N	Cotton/Polyester	\N	0	0	\N	f	4.50	t
cccccccc-cccc-cccc-cccc-000000000020	SKU-FASH-0020	Watch Analog	199.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111209	TimePiece	\N	\N	Watch Analog, sold by TimePiece.	259.99	199.99	23.08	https://thumb.wikimedia.org/wikipedia/commons/thumb/c/c5/Junghans_Mega.jpg/960px-Junghans_Mega.jpg	\N	\N	\N	\N	\N	\N	\N	Cotton/Polyester	4.40	110	78	\N	f	4.50	t
aaaaaaaa-aaaa-aaaa-aaaa-000000000033	SKU-ELEC-0033	Webcam 1080p	79.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111202	VisionTech	\N	\N	Webcam 1080p, sold by VisionTech.	91.99	79.99	13.04	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f5/Fancy_webcam_on_computer_screen.jpg/960px-Fancy_webcam_on_computer_screen.jpg	\N	\N	\N	\N	\N	\N	\N	Plastic/Metal	4.50	120	85	\N	f	4.80	t
22222222-2222-4222-8222-222222222222	SKU-MOUSE-01	Wireless Mouse	49.50	USD	t	2026-11-08 12:33:29.733229+00	2026-11-11 19:44:20.054006+00	0	\N	\N	\N	\N	\N	\N	\N	\N	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/77/A_wireless_computer_mouse.jpg/960px-A_wireless_computer_mouse.jpg	\N	\N	\N	\N	\N	\N	\N	\N	\N	0	0	\N	f	\N	f
aaaaaaaa-aaaa-aaaa-aaaa-000000000034	SKU-ELEC-0034	Wireless Mouse	49.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111202	MousePro	\N	\N	Wireless Mouse, sold by MousePro.	57.49	49.99	13.05	https://thumb.wikimedia.org/wikipedia/commons/thumb/7/77/A_wireless_computer_mouse.jpg/960px-A_wireless_computer_mouse.jpg	\N	\N	\N	\N	\N	\N	\N	Plastic/Metal	\N	0	0	\N	f	4.80	t
aaaaaaaa-aaaa-aaaa-aaaa-000000000035	SKU-ELEC-0035	XLR Cables	24.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111203	CableGear	\N	\N	XLR Cables, sold by CableGear.	28.74	24.99	13.05	https://upload.wikimedia.org/wikipedia/commons/0/04/XLR_cable_connectors.jpg	\N	\N	\N	\N	\N	\N	\N	Plastic/Metal	\N	0	0	\N	f	4.80	t
dddddddd-dddd-dddd-dddd-000000000015	SKU-SPRT-0015	Yoga Mat	29.99	USD	t	2026-11-11 18:21:08.18535+00	2026-11-11 19:44:20.054006+00	0	11111111-1111-1111-1111-111111111210	FitGear	\N	\N	Yoga Mat, sold by FitGear.	37.49	29.99	20.01	https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f3/Yoga_Mat_Made_of_Carpet_Underlay.jpg/960px-Yoga_Mat_Made_of_Carpet_Underlay.jpg	\N	\N	\N	\N	\N	\N	\N	Nylon/Rubber	4.60	100	70	\N	f	4.70	t
\.


--
-- Name: categories categories_pkey; Type: CONSTRAINT; Schema: product; Owner: product_service
--

ALTER TABLE ONLY product.categories
    ADD CONSTRAINT categories_pkey PRIMARY KEY (id);


--
-- Name: flyway_schema_history flyway_schema_history_pk; Type: CONSTRAINT; Schema: product; Owner: product_service
--

ALTER TABLE ONLY product.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);


--
-- Name: product_images product_images_pkey; Type: CONSTRAINT; Schema: product; Owner: product_service
--

ALTER TABLE ONLY product.product_images
    ADD CONSTRAINT product_images_pkey PRIMARY KEY (id);


--
-- Name: products products_pkey; Type: CONSTRAINT; Schema: product; Owner: product_service
--

ALTER TABLE ONLY product.products
    ADD CONSTRAINT products_pkey PRIMARY KEY (id);


--
-- Name: products products_sku_key; Type: CONSTRAINT; Schema: product; Owner: product_service
--

ALTER TABLE ONLY product.products
    ADD CONSTRAINT products_sku_key UNIQUE (sku);


--
-- Name: flyway_schema_history_s_idx; Type: INDEX; Schema: product; Owner: product_service
--

CREATE INDEX flyway_schema_history_s_idx ON product.flyway_schema_history USING btree (success);


--
-- Name: product_images_product_id_idx; Type: INDEX; Schema: product; Owner: product_service
--

CREATE INDEX product_images_product_id_idx ON product.product_images USING btree (product_id);


--
-- Name: products_average_rating_idx; Type: INDEX; Schema: product; Owner: product_service
--

CREATE INDEX products_average_rating_idx ON product.products USING btree (average_rating);


--
-- Name: products_category_id_idx; Type: INDEX; Schema: product; Owner: product_service
--

CREATE INDEX products_category_id_idx ON product.products USING btree (category_id);


--
-- Name: products_created_at_idx; Type: INDEX; Schema: product; Owner: product_service
--

CREATE INDEX products_created_at_idx ON product.products USING btree (created_at);


--
-- Name: products_is_featured_idx; Type: INDEX; Schema: product; Owner: product_service
--

CREATE INDEX products_is_featured_idx ON product.products USING btree (is_featured) WHERE (is_featured = true);


--
-- Name: products_sale_price_idx; Type: INDEX; Schema: product; Owner: product_service
--

CREATE INDEX products_sale_price_idx ON product.products USING btree (sale_price);


--
-- Name: categories categories_parent_category_id_fkey; Type: FK CONSTRAINT; Schema: product; Owner: product_service
--

ALTER TABLE ONLY product.categories
    ADD CONSTRAINT categories_parent_category_id_fkey FOREIGN KEY (parent_category_id) REFERENCES product.categories(id);


--
-- Name: product_images product_images_product_id_fkey; Type: FK CONSTRAINT; Schema: product; Owner: product_service
--

ALTER TABLE ONLY product.product_images
    ADD CONSTRAINT product_images_product_id_fkey FOREIGN KEY (product_id) REFERENCES product.products(id) ON DELETE CASCADE;


--
-- Name: products products_category_id_fkey; Type: FK CONSTRAINT; Schema: product; Owner: product_service
--

ALTER TABLE ONLY product.products
    ADD CONSTRAINT products_category_id_fkey FOREIGN KEY (category_id) REFERENCES product.categories(id);


--
-- Name: SCHEMA public; Type: ACL; Schema: -; Owner: pg_database_owner
--

REVOKE USAGE ON SCHEMA public FROM PUBLIC;
GRANT ALL ON SCHEMA public TO product_service;


--
-- PostgreSQL database dump complete
--

\unrestrict rDW5ec791rfwUA9ZmRyvBJ2lqTsLO0yCPhcdbXnhv35Wpkh8P6A0180AgajaumY

