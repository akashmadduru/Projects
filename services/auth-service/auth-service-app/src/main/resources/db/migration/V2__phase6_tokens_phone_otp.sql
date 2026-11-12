-- =============================================================================
-- Phase 6: password reset, email verification, phone verification, OTP login.
-- Additive and forward-only.
-- =============================================================================

ALTER TABLE auth.users
    ADD COLUMN phone          varchar(20),
    ADD COLUMN phone_verified boolean NOT NULL DEFAULT false;

-- A phone number may be verified on at most one account. Unverified numbers are
-- unconstrained (anyone can type any number; only proof of possession counts).
CREATE UNIQUE INDEX users_phone_verified_key ON auth.users (phone) WHERE phone_verified;

-- OTP sessions: the refresh_tokens.auth_method check must admit the new method.
ALTER TABLE auth.refresh_tokens DROP CONSTRAINT refresh_tokens_auth_method_check;
ALTER TABLE auth.refresh_tokens
    ADD CONSTRAINT refresh_tokens_auth_method_check CHECK (auth_method IN ('password', 'google', 'otp'));

-- One table for every single-use secret. token_hash is the SHA-256 hex of the raw
-- link token, or of (user_id:purpose:code) for 6-digit codes. Raw values are never
-- stored. At most one live row per (user, purpose): issuing replaces the previous.
CREATE TABLE auth.one_time_tokens
(
    id          uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    user_id     uuid        NOT NULL REFERENCES auth.users (id) ON DELETE CASCADE,
    purpose     varchar(30) NOT NULL
        CONSTRAINT one_time_tokens_purpose_check
            CHECK (purpose IN ('PASSWORD_RESET', 'EMAIL_VERIFY', 'PHONE_VERIFY', 'OTP_LOGIN')),
    token_hash  char(64)    NOT NULL,
    -- The phone number a PHONE_VERIFY code was sent to (so changing the number
    -- invalidates the pending code).
    target      varchar(320),
    attempts    smallint    NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL,
    consumed_at timestamptz
);

CREATE UNIQUE INDEX one_time_tokens_hash_purpose_key ON auth.one_time_tokens (purpose, token_hash);
CREATE INDEX one_time_tokens_user_purpose_idx ON auth.one_time_tokens (user_id, purpose);
CREATE INDEX one_time_tokens_expiry_idx ON auth.one_time_tokens (expires_at);
