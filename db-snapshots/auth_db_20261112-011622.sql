--
-- PostgreSQL database dump
--

\restrict nbgi7iWLwBy2ZK8506NC4oU3BmWYydB083LrfaLbJb3WKg15DAxD7ppLJYfjGLh

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

DROP DATABASE IF EXISTS auth_db;
--
-- Name: auth_db; Type: DATABASE; Schema: -; Owner: auth_service
--

CREATE DATABASE auth_db WITH TEMPLATE = template0 ENCODING = 'UTF8' LOCALE_PROVIDER = libc LOCALE = 'C';


ALTER DATABASE auth_db OWNER TO auth_service;

\unrestrict nbgi7iWLwBy2ZK8506NC4oU3BmWYydB083LrfaLbJb3WKg15DAxD7ppLJYfjGLh
\connect auth_db
\restrict nbgi7iWLwBy2ZK8506NC4oU3BmWYydB083LrfaLbJb3WKg15DAxD7ppLJYfjGLh

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
-- Name: DATABASE auth_db; Type: COMMENT; Schema: -; Owner: auth_service
--

COMMENT ON DATABASE auth_db IS 'auth-service: users, roles, refresh tokens';


--
-- Name: auth_db; Type: DATABASE PROPERTIES; Schema: -; Owner: auth_service
--

ALTER DATABASE auth_db SET search_path TO 'public';


\unrestrict nbgi7iWLwBy2ZK8506NC4oU3BmWYydB083LrfaLbJb3WKg15DAxD7ppLJYfjGLh
\connect auth_db
\restrict nbgi7iWLwBy2ZK8506NC4oU3BmWYydB083LrfaLbJb3WKg15DAxD7ppLJYfjGLh

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
-- Name: auth; Type: SCHEMA; Schema: -; Owner: auth_service
--

CREATE SCHEMA auth;


ALTER SCHEMA auth OWNER TO auth_service;

--
-- Name: citext; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS citext WITH SCHEMA auth;


--
-- Name: EXTENSION citext; Type: COMMENT; Schema: -; Owner: 
--

COMMENT ON EXTENSION citext IS 'data type for case-insensitive character strings';


--
-- Name: pgcrypto; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA auth;


--
-- Name: EXTENSION pgcrypto; Type: COMMENT; Schema: -; Owner: 
--

COMMENT ON EXTENSION pgcrypto IS 'cryptographic functions';


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: federated_identities; Type: TABLE; Schema: auth; Owner: auth_service
--

CREATE TABLE auth.federated_identities (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    provider character varying(50) NOT NULL,
    provider_user_id character varying(255) NOT NULL,
    provider_email auth.citext,
    email_verified_at_link boolean DEFAULT false NOT NULL,
    linked_at timestamp with time zone DEFAULT now() NOT NULL,
    last_login_at timestamp with time zone
);


ALTER TABLE auth.federated_identities OWNER TO auth_service;

--
-- Name: flyway_schema_history; Type: TABLE; Schema: auth; Owner: auth_service
--

CREATE TABLE auth.flyway_schema_history (
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


ALTER TABLE auth.flyway_schema_history OWNER TO auth_service;

--
-- Name: refresh_tokens; Type: TABLE; Schema: auth; Owner: auth_service
--

CREATE TABLE auth.refresh_tokens (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    token_hash character(64) NOT NULL,
    family_id uuid NOT NULL,
    parent_id uuid,
    auth_method character varying(20) NOT NULL,
    issued_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    family_absolute_expires_at timestamp with time zone NOT NULL,
    rotated_at timestamp with time zone,
    revoked_at timestamp with time zone,
    revoked_reason character varying(32),
    user_agent character varying(400),
    ip_address inet,
    CONSTRAINT refresh_tokens_auth_method_check CHECK (((auth_method)::text = ANY ((ARRAY['password'::character varying, 'google'::character varying])::text[]))),
    CONSTRAINT refresh_tokens_revoked_reason_check CHECK (((revoked_reason)::text = ANY ((ARRAY['ROTATED'::character varying, 'LOGOUT'::character varying, 'REUSE_DETECTED'::character varying, 'FAMILY_REVOKED'::character varying, 'PASSWORD_CHANGE'::character varying, 'ADMIN'::character varying, 'EXPIRED'::character varying])::text[])))
);


ALTER TABLE auth.refresh_tokens OWNER TO auth_service;

--
-- Name: COLUMN refresh_tokens.token_hash; Type: COMMENT; Schema: auth; Owner: auth_service
--

COMMENT ON COLUMN auth.refresh_tokens.token_hash IS 'SHA-256 hex of the raw opaque token. Raw value exists only in the response cookie.';


--
-- Name: COLUMN refresh_tokens.family_absolute_expires_at; Type: COMMENT; Schema: auth; Owner: auth_service
--

COMMENT ON COLUMN auth.refresh_tokens.family_absolute_expires_at IS 'Root issued_at + 30d, copied unchanged to every child. NEVER recompute on rotation.';


--
-- Name: roles; Type: TABLE; Schema: auth; Owner: auth_service
--

CREATE TABLE auth.roles (
    id smallint NOT NULL,
    name character varying(50) NOT NULL,
    description character varying(255)
);


ALTER TABLE auth.roles OWNER TO auth_service;

--
-- Name: user_credentials; Type: TABLE; Schema: auth; Owner: auth_service
--

CREATE TABLE auth.user_credentials (
    user_id uuid NOT NULL,
    password_hash character varying(255) NOT NULL,
    algorithm character varying(32) DEFAULT 'argon2id'::character varying NOT NULL,
    password_updated_at timestamp with time zone DEFAULT now() NOT NULL,
    failed_attempts smallint DEFAULT 0 NOT NULL,
    locked_until timestamp with time zone,
    must_change_password boolean DEFAULT false NOT NULL
);


ALTER TABLE auth.user_credentials OWNER TO auth_service;

--
-- Name: COLUMN user_credentials.password_hash; Type: COMMENT; Schema: auth; Owner: auth_service
--

COMMENT ON COLUMN auth.user_credentials.password_hash IS 'Argon2id PHC string with a Spring {id} prefix. NEVER logged, never returned by any endpoint.';


--
-- Name: user_roles; Type: TABLE; Schema: auth; Owner: auth_service
--

CREATE TABLE auth.user_roles (
    user_id uuid NOT NULL,
    role_id smallint NOT NULL,
    granted_at timestamp with time zone DEFAULT now() NOT NULL
);


ALTER TABLE auth.user_roles OWNER TO auth_service;

--
-- Name: users; Type: TABLE; Schema: auth; Owner: auth_service
--

CREATE TABLE auth.users (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    email auth.citext NOT NULL,
    email_verified boolean DEFAULT false NOT NULL,
    display_name character varying(200) NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT users_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'DISABLED'::character varying, 'LOCKED'::character varying])::text[])))
);


ALTER TABLE auth.users OWNER TO auth_service;

--
-- Name: TABLE users; Type: COMMENT; Schema: auth; Owner: auth_service
--

COMMENT ON TABLE auth.users IS 'Platform identities. Credentials live in user_credentials / federated_identities.';


--
-- Name: COLUMN users.status; Type: COMMENT; Schema: auth; Owner: auth_service
--

COMMENT ON COLUMN auth.users.status IS 'ACTIVE = may authenticate. DISABLED = administratively off. LOCKED = reserved for a future permanent lock; transient brute-force lockout uses user_credentials.locked_until.';


--
-- Data for Name: federated_identities; Type: TABLE DATA; Schema: auth; Owner: auth_service
--

COPY auth.federated_identities (id, user_id, provider, provider_user_id, provider_email, email_verified_at_link, linked_at, last_login_at) FROM stdin;
\.


--
-- Data for Name: flyway_schema_history; Type: TABLE DATA; Schema: auth; Owner: auth_service
--

COPY auth.flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) FROM stdin;
0	\N	<< Flyway Schema Creation >>	SCHEMA	"auth"	\N	auth_service	2026-11-08 12:33:28.960905	0	t
1	1	auth baseline	SQL	V1__auth_baseline.sql	-2010605528	auth_service	2026-11-08 12:33:28.9832	53	t
\.


--
-- Data for Name: refresh_tokens; Type: TABLE DATA; Schema: auth; Owner: auth_service
--

COPY auth.refresh_tokens (id, user_id, token_hash, family_id, parent_id, auth_method, issued_at, expires_at, family_absolute_expires_at, rotated_at, revoked_at, revoked_reason, user_agent, ip_address) FROM stdin;
fd0b7052-4701-48e4-9b79-bc6f8b15f937	78d3855b-0ba7-46ad-8d69-d4deea0eda56	1d92d32e2c6609b986c9d04cfb9642118b644151b8907970a960c2375eedcb0a	fd0b7052-4701-48e4-9b79-bc6f8b15f937	\N	password	2026-11-08 13:24:46.387462+00	2026-11-22 13:24:46.387462+00	2026-12-08 13:24:46.387462+00	2026-11-08 13:46:01.282848+00	2026-11-08 13:46:01.282848+00	ROTATED	Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36	172.18.0.7
5ab344fe-6a58-4ca5-98a7-782bb7eed1eb	78d3855b-0ba7-46ad-8d69-d4deea0eda56	1ea153d599d2e9c1ec75da88bdea41874d36f2b851ded420dfd4c4603728bd6d	fd0b7052-4701-48e4-9b79-bc6f8b15f937	fd0b7052-4701-48e4-9b79-bc6f8b15f937	password	2026-11-08 13:46:01.331793+00	2026-11-22 13:46:01.331793+00	2026-12-08 13:24:46.387462+00	\N	\N	\N	Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36	172.18.0.7
\.


--
-- Data for Name: roles; Type: TABLE DATA; Schema: auth; Owner: auth_service
--

COPY auth.roles (id, name, description) FROM stdin;
1	ROLE_ADMIN	Full administrative access, including user management
2	ROLE_CATALOG_MANAGER	May create and modify catalog content, but not manage users
3	ROLE_VIEWER	Read-only access. The only role granted by self-service registration.
\.


--
-- Data for Name: user_credentials; Type: TABLE DATA; Schema: auth; Owner: auth_service
--

COPY auth.user_credentials (user_id, password_hash, algorithm, password_updated_at, failed_attempts, locked_until, must_change_password) FROM stdin;
78d3855b-0ba7-46ad-8d69-d4deea0eda56	{argon2id}$argon2id$v=19$m=19456,t=2,p=1$6yPMTOCVzmVAIjR7ZOVgfQ$1Ld1/uvVJ94XDPSzHoGnZ1c/jrNKgQHINl19nqTKlxA	argon2id	2026-11-08 13:24:32.827618+00	0	\N	f
\.


--
-- Data for Name: user_roles; Type: TABLE DATA; Schema: auth; Owner: auth_service
--

COPY auth.user_roles (user_id, role_id, granted_at) FROM stdin;
78d3855b-0ba7-46ad-8d69-d4deea0eda56	3	2026-11-08 13:24:32.675504+00
\.


--
-- Data for Name: users; Type: TABLE DATA; Schema: auth; Owner: auth_service
--

COPY auth.users (id, email, email_verified, display_name, status, created_at, updated_at, version) FROM stdin;
78d3855b-0ba7-46ad-8d69-d4deea0eda56	akashmadduru@gmail.com	f	Akash Madduru	ACTIVE	2026-11-08 13:24:32.720732+00	2026-11-08 13:24:32.720732+00	0
\.


--
-- Name: federated_identities federated_identities_pkey; Type: CONSTRAINT; Schema: auth; Owner: auth_service
--

ALTER TABLE ONLY auth.federated_identities
    ADD CONSTRAINT federated_identities_pkey PRIMARY KEY (id);


--
-- Name: federated_identities federated_identities_provider_subject_key; Type: CONSTRAINT; Schema: auth; Owner: auth_service
--

ALTER TABLE ONLY auth.federated_identities
    ADD CONSTRAINT federated_identities_provider_subject_key UNIQUE (provider, provider_user_id);


--
-- Name: federated_identities federated_identities_user_provider_key; Type: CONSTRAINT; Schema: auth; Owner: auth_service
--

ALTER TABLE ONLY auth.federated_identities
    ADD CONSTRAINT federated_identities_user_provider_key UNIQUE (user_id, provider);


--
-- Name: flyway_schema_history flyway_schema_history_pk; Type: CONSTRAINT; Schema: auth; Owner: auth_service
--

ALTER TABLE ONLY auth.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);


--
-- Name: refresh_tokens refresh_tokens_pkey; Type: CONSTRAINT; Schema: auth; Owner: auth_service
--

ALTER TABLE ONLY auth.refresh_tokens
    ADD CONSTRAINT refresh_tokens_pkey PRIMARY KEY (id);


--
-- Name: roles roles_name_key; Type: CONSTRAINT; Schema: auth; Owner: auth_service
--

ALTER TABLE ONLY auth.roles
    ADD CONSTRAINT roles_name_key UNIQUE (name);


--
-- Name: roles roles_pkey; Type: CONSTRAINT; Schema: auth; Owner: auth_service
--

ALTER TABLE ONLY auth.roles
    ADD CONSTRAINT roles_pkey PRIMARY KEY (id);


--
-- Name: user_credentials user_credentials_pkey; Type: CONSTRAINT; Schema: auth; Owner: auth_service
--

ALTER TABLE ONLY auth.user_credentials
    ADD CONSTRAINT user_credentials_pkey PRIMARY KEY (user_id);


--
-- Name: user_roles user_roles_pkey; Type: CONSTRAINT; Schema: auth; Owner: auth_service
--

ALTER TABLE ONLY auth.user_roles
    ADD CONSTRAINT user_roles_pkey PRIMARY KEY (user_id, role_id);


--
-- Name: users users_email_key; Type: CONSTRAINT; Schema: auth; Owner: auth_service
--

ALTER TABLE ONLY auth.users
    ADD CONSTRAINT users_email_key UNIQUE (email);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: auth; Owner: auth_service
--

ALTER TABLE ONLY auth.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- Name: federated_identities_user_id_idx; Type: INDEX; Schema: auth; Owner: auth_service
--

CREATE INDEX federated_identities_user_id_idx ON auth.federated_identities USING btree (user_id);


--
-- Name: flyway_schema_history_s_idx; Type: INDEX; Schema: auth; Owner: auth_service
--

CREATE INDEX flyway_schema_history_s_idx ON auth.flyway_schema_history USING btree (success);


--
-- Name: refresh_tokens_active_expiry_idx; Type: INDEX; Schema: auth; Owner: auth_service
--

CREATE INDEX refresh_tokens_active_expiry_idx ON auth.refresh_tokens USING btree (expires_at) WHERE (revoked_at IS NULL);


--
-- Name: refresh_tokens_active_user_idx; Type: INDEX; Schema: auth; Owner: auth_service
--

CREATE INDEX refresh_tokens_active_user_idx ON auth.refresh_tokens USING btree (user_id) WHERE (revoked_at IS NULL);


--
-- Name: refresh_tokens_family_id_idx; Type: INDEX; Schema: auth; Owner: auth_service
--

CREATE INDEX refresh_tokens_family_id_idx ON auth.refresh_tokens USING btree (family_id);


--
-- Name: refresh_tokens_token_hash_key; Type: INDEX; Schema: auth; Owner: auth_service
--

CREATE UNIQUE INDEX refresh_tokens_token_hash_key ON auth.refresh_tokens USING btree (token_hash);


--
-- Name: federated_identities federated_identities_user_id_fkey; Type: FK CONSTRAINT; Schema: auth; Owner: auth_service
--

ALTER TABLE ONLY auth.federated_identities
    ADD CONSTRAINT federated_identities_user_id_fkey FOREIGN KEY (user_id) REFERENCES auth.users(id) ON DELETE CASCADE;


--
-- Name: refresh_tokens refresh_tokens_parent_id_fkey; Type: FK CONSTRAINT; Schema: auth; Owner: auth_service
--

ALTER TABLE ONLY auth.refresh_tokens
    ADD CONSTRAINT refresh_tokens_parent_id_fkey FOREIGN KEY (parent_id) REFERENCES auth.refresh_tokens(id) ON DELETE SET NULL;


--
-- Name: refresh_tokens refresh_tokens_user_id_fkey; Type: FK CONSTRAINT; Schema: auth; Owner: auth_service
--

ALTER TABLE ONLY auth.refresh_tokens
    ADD CONSTRAINT refresh_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES auth.users(id) ON DELETE CASCADE;


--
-- Name: user_credentials user_credentials_user_id_fkey; Type: FK CONSTRAINT; Schema: auth; Owner: auth_service
--

ALTER TABLE ONLY auth.user_credentials
    ADD CONSTRAINT user_credentials_user_id_fkey FOREIGN KEY (user_id) REFERENCES auth.users(id) ON DELETE CASCADE;


--
-- Name: user_roles user_roles_role_id_fkey; Type: FK CONSTRAINT; Schema: auth; Owner: auth_service
--

ALTER TABLE ONLY auth.user_roles
    ADD CONSTRAINT user_roles_role_id_fkey FOREIGN KEY (role_id) REFERENCES auth.roles(id);


--
-- Name: user_roles user_roles_user_id_fkey; Type: FK CONSTRAINT; Schema: auth; Owner: auth_service
--

ALTER TABLE ONLY auth.user_roles
    ADD CONSTRAINT user_roles_user_id_fkey FOREIGN KEY (user_id) REFERENCES auth.users(id) ON DELETE CASCADE;


--
-- Name: SCHEMA public; Type: ACL; Schema: -; Owner: pg_database_owner
--

REVOKE USAGE ON SCHEMA public FROM PUBLIC;
GRANT ALL ON SCHEMA public TO auth_service;


--
-- PostgreSQL database dump complete
--

\unrestrict nbgi7iWLwBy2ZK8506NC4oU3BmWYydB083LrfaLbJb3WKg15DAxD7ppLJYfjGLh

