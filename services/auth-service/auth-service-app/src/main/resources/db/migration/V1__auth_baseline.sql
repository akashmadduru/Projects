-- =============================================================================
-- auth-service baseline schema.
--
-- Database `auth_db` is created by infra/postgres/init/01-schemas.sql and owned
-- by the `auth_service` role. Everything below lives in the `auth` SCHEMA, not
-- in `public`: a service that owns its database should still not scatter its
-- tables across the default schema, because that is where every tool, every
-- extension and every accidental `CREATE TABLE` also lands.
--
-- Flyway is configured with `schemas: auth` / `default-schema: auth`, so it
-- creates the schema and its own history table here.
--
-- FORWARD-ONLY. There is no down migration: rolling a schema change back in a
-- running system loses the rows written since it was applied. Zero-downtime
-- changes are made additively (add nullable column -> backfill -> start writing
-- -> stop reading the old one -> drop it in a much later release).
-- =============================================================================

-- pgcrypto: gen_random_uuid(). Postgres 13+ has this built in, but the
-- extension is created anyway so the DEFAULTs below do not silently depend on
-- the server version.
-- citext: case-insensitive text, used for every email column.
--
-- Both are TRUSTED extensions from Postgres 13, so the database owner can
-- create them without superuser.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;

CREATE SCHEMA IF NOT EXISTS auth;

-- =============================================================================
-- users - the identity itself, independent of HOW it authenticates.
--
-- This table holds NO credential material. A user may have a password, or a
-- Google identity, or both, or (after a future SSO-only policy change) neither.
-- Keeping the identity separate from the proofs of identity is what makes
-- adding a second IdP a new row in `federated_identities` rather than a
-- migration of this table.
-- =============================================================================
CREATE TABLE auth.users
(
    id             uuid PRIMARY KEY     DEFAULT gen_random_uuid(),

    -- citext: 'Ada@Example.com' and 'ada@example.com' are the same account, and
    -- the UNIQUE constraint enforces that at the storage layer rather than
    -- hoping every code path remembered to lower-case first.
    --
    -- The application ALSO normalises to lower case before writing or querying.
    -- That is not redundancy for its own sake: a JDBC parameter arrives typed as
    -- varchar, and `citext_col = $1::varchar` resolves through the implicit
    -- citext->text cast to a CASE-SENSITIVE text comparison. Normalising in the
    -- application keeps lookups correct regardless; citext keeps the UNIQUE
    -- index correct regardless of the application.
    email          citext      NOT NULL UNIQUE,

    -- Proven ownership of the address. True for Google-provisioned users
    -- (Google asserted it); false for password sign-ups until a verification
    -- flow exists.
    email_verified boolean     NOT NULL DEFAULT false,

    display_name   varchar(200)         NOT NULL,

    status         varchar(20) NOT NULL DEFAULT 'ACTIVE'
        CONSTRAINT users_status_check CHECK (status IN ('ACTIVE', 'DISABLED', 'LOCKED')),

    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),

    -- JPA @Version. Two admins editing the same user concurrently must not
    -- silently lose one of the edits.
    version        bigint      NOT NULL DEFAULT 0
);

COMMENT ON TABLE auth.users IS 'Platform identities. Credentials live in user_credentials / federated_identities.';
COMMENT ON COLUMN auth.users.status IS 'ACTIVE = may authenticate. DISABLED = administratively off. LOCKED = reserved for a future permanent lock; transient brute-force lockout uses user_credentials.locked_until.';

-- =============================================================================
-- user_credentials - the LOCAL password proof, 1:1 with users.
--
-- Separate table, not columns on `users`, for three reasons:
--   1. Social-only users have no row here at all, instead of a nullable
--      password column that every query has to reason about.
--   2. `SELECT * FROM users` in a support tool cannot accidentally return a
--      password hash.
--   3. The brute-force counters are written on every failed login; keeping them
--      out of `users` avoids bumping the users row (and its @Version) on a
--      request that changed nothing about the identity.
-- =============================================================================
CREATE TABLE auth.user_credentials
(
    user_id              uuid PRIMARY KEY
        REFERENCES auth.users (id) ON DELETE CASCADE,

    -- Spring DelegatingPasswordEncoder form: '{argon2id}$argon2id$v=19$m=19456,t=2,p=1$<salt>$<hash>'
    -- ~110 chars today; 255 leaves room for a future algorithm with longer
    -- parameters without a migration.
    password_hash        varchar(255) NOT NULL,

    -- Mirrors the {id} prefix inside password_hash. Denormalised on purpose: it
    -- makes "how many users are still on the old algorithm?" an indexable
    -- question during a hash migration instead of a full-table string scan.
    algorithm            varchar(32)  NOT NULL DEFAULT 'argon2id',

    password_updated_at  timestamptz  NOT NULL DEFAULT now(),

    -- Brute-force counters. THE per-account control, and the only one that
    -- works correctly across replicas (the Bucket4j per-IP throttle is
    -- per-instance and is a soft control only).
    failed_attempts      smallint     NOT NULL DEFAULT 0,
    locked_until         timestamptz,

    must_change_password boolean      NOT NULL DEFAULT false
);

COMMENT ON COLUMN auth.user_credentials.password_hash IS 'Argon2id PHC string with a Spring {id} prefix. NEVER logged, never returned by any endpoint.';

-- =============================================================================
-- federated_identities - a proof of identity issued by an external IdP.
-- =============================================================================
CREATE TABLE auth.federated_identities
(
    id                     uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    user_id                uuid         NOT NULL
        REFERENCES auth.users (id) ON DELETE CASCADE,

    provider               varchar(50)  NOT NULL,

    -- The IdP's SUBJECT identifier (Google's `sub`). NEVER the email address.
    -- Email at Google is mutable and reassignable; `sub` is neither. Matching on
    -- email would mean that whoever holds an address today inherits the account
    -- of whoever held it yesterday.
    provider_user_id       varchar(255) NOT NULL,

    -- Kept for support/debugging only. Nothing authenticates on this column.
    provider_email         citext,

    -- Whether the IdP asserted email_verified at the moment of linking. Audit
    -- evidence for the single decision that could have allowed a takeover.
    email_verified_at_link boolean      NOT NULL DEFAULT false,

    linked_at              timestamptz  NOT NULL DEFAULT now(),
    last_login_at          timestamptz,

    -- One IdP account maps to at most one local user. This is the constraint the
    -- concurrent-first-login race relies on: two simultaneous provisioning
    -- attempts for the same `sub` cannot both succeed, so the loser can safely
    -- retry as a plain login.
    CONSTRAINT federated_identities_provider_subject_key UNIQUE (provider, provider_user_id),

    -- A user links each provider at most once.
    CONSTRAINT federated_identities_user_provider_key UNIQUE (user_id, provider)
);

CREATE INDEX federated_identities_user_id_idx ON auth.federated_identities (user_id);

-- =============================================================================
-- roles / user_roles
--
-- smallint PK, not uuid: this table holds single digits of rows and is joined on
-- every /me call. A 2-byte key keeps the join index tiny and cache-resident.
-- =============================================================================
CREATE TABLE auth.roles
(
    id          smallint PRIMARY KEY,
    name        varchar(50) NOT NULL UNIQUE,
    description varchar(255)
);

-- ROLE_-prefixed because that is the GrantedAuthority form Spring's
-- hasRole('X') looks for. The JWT `roles` claim carries the UNPREFIXED name;
-- the prefix is stripped in exactly one place on the way out (TokenClaims).
INSERT INTO auth.roles (id, name, description)
VALUES (1, 'ROLE_ADMIN', 'Full administrative access, including user management'),
       (2, 'ROLE_CATALOG_MANAGER', 'May create and modify catalog content, but not manage users'),
       (3, 'ROLE_VIEWER', 'Read-only access. The only role granted by self-service registration.');

CREATE TABLE auth.user_roles
(
    user_id    uuid        NOT NULL REFERENCES auth.users (id) ON DELETE CASCADE,
    role_id    smallint    NOT NULL REFERENCES auth.roles (id),
    granted_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role_id)
);

-- The composite PK already indexes (user_id, role_id) - which serves the
-- user -> roles direction. The reverse direction ("who has ROLE_ADMIN?") is an
-- admin-console query, not a hot path, and gets a separate index only if it
-- ever shows up in pg_stat_statements.

-- =============================================================================
-- refresh_tokens - the rotating, reuse-detecting session chain.
--
-- DENORMALISED FAMILY: there is deliberately NO `refresh_token_families` table.
-- family_id is simply the id of the family's root token, copied onto every
-- descendant. Revoking a compromised family is then ONE indexed UPDATE over
-- family_id rather than a parent table write plus a fan-out; and reading a
-- token's family state needs no join on the hot refresh path.
-- =============================================================================
CREATE TABLE auth.refresh_tokens
(
    id                        uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    user_id                   uuid        NOT NULL
        REFERENCES auth.users (id) ON DELETE CASCADE,

    -- SHA-256 hex of the raw token. THE RAW TOKEN IS NEVER STORED.
    --
    -- Unsalted, un-stretched SHA-256 is the CORRECT choice here and not an
    -- oversight: the input is 256 bits of CSPRNG output, so there is no
    -- dictionary to attack and no rainbow table to build. Argon2 on this column
    -- would add ~100ms to every single refresh - the hottest authenticated path
    -- in the platform - and buy nothing. (Contrast user_credentials.password_hash,
    -- where the input is a human-chosen string and a KDF is essential.)
    token_hash                char(64)    NOT NULL,

    -- id of this family's root token. Root rows have family_id = id.
    family_id                 uuid        NOT NULL,

    -- The token this one replaced. NULL for a family root. Kept for forensics:
    -- after a reuse detection, the chain shows exactly where the fork happened.
    parent_id                 uuid
        REFERENCES auth.refresh_tokens (id) ON DELETE SET NULL,

    auth_method               varchar(20) NOT NULL
        CONSTRAINT refresh_tokens_auth_method_check CHECK (auth_method IN ('password', 'google')),

    issued_at                 timestamptz NOT NULL DEFAULT now(),

    -- SLIDING window: each rotation issues a child with a fresh +14d.
    expires_at                timestamptz NOT NULL,

    -- ABSOLUTE cap: the root's issued_at + 30d, COPIED VERBATIM onto every
    -- descendant. It must never be recomputed during rotation - recomputing it
    -- turns the cap into an infinitely renewable session, which is the whole
    -- thing it exists to prevent.
    family_absolute_expires_at timestamptz NOT NULL,

    rotated_at                timestamptz,
    revoked_at                timestamptz,
    revoked_reason            varchar(32)
        CONSTRAINT refresh_tokens_revoked_reason_check
            CHECK (revoked_reason IN ('ROTATED', 'LOGOUT', 'REUSE_DETECTED',
                                      'FAMILY_REVOKED', 'PASSWORD_CHANGE', 'ADMIN', 'EXPIRED')),

    -- Truncated by the application before insert; a hostile client can send a
    -- multi-kilobyte User-Agent.
    user_agent                varchar(400),
    ip_address                inet
);

COMMENT ON COLUMN auth.refresh_tokens.token_hash IS 'SHA-256 hex of the raw opaque token. Raw value exists only in the response cookie.';
COMMENT ON COLUMN auth.refresh_tokens.family_absolute_expires_at IS 'Root issued_at + 30d, copied unchanged to every child. NEVER recompute on rotation.';

-- The lookup that starts every refresh: hash -> row. UNIQUE both enforces
-- correctness (two rows cannot share a hash) and provides the index.
CREATE UNIQUE INDEX refresh_tokens_token_hash_key ON auth.refresh_tokens (token_hash);

-- Reuse detection and logout both revoke a whole family through this.
CREATE INDEX refresh_tokens_family_id_idx ON auth.refresh_tokens (family_id);

-- "All live sessions for this user" - used by password-change revocation and by
-- a future session-management screen. PARTIAL: revoked rows accumulate forever
-- and are never the answer to this question, so keeping them out of the index
-- keeps it proportional to active sessions rather than to lifetime sessions.
CREATE INDEX refresh_tokens_active_user_idx ON auth.refresh_tokens (user_id)
    WHERE revoked_at IS NULL;

-- Drives the housekeeping sweep that deletes dead rows. Partial for the same
-- reason.
CREATE INDEX refresh_tokens_active_expiry_idx ON auth.refresh_tokens (expires_at)
    WHERE revoked_at IS NULL;
