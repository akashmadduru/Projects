-- Phase 10: TOTP two-factor auth, MFA login challenges, known devices (login alerts),
-- ROLE_SUPPORT / ROLE_FINANCE. Additive and forward-only.

CREATE TABLE auth.user_totp
(
    id             uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    user_id        uuid        NOT NULL UNIQUE REFERENCES auth.users (id) ON DELETE CASCADE,
    -- base64(iv || AES-GCM ciphertext) of the 20-byte secret; key = AUTH_DATA_KEY.
    secret_enc     varchar(200) NOT NULL,
    enabled        boolean     NOT NULL DEFAULT false,
    -- Highest TOTP time-step already accepted: a code can be used once (replay protection).
    last_used_step bigint      NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL DEFAULT now(),
    enabled_at     timestamptz
);

CREATE TABLE auth.recovery_codes
(
    id         uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    user_id    uuid        NOT NULL REFERENCES auth.users (id) ON DELETE CASCADE,
    code_hash  char(64)    NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX recovery_codes_user_idx ON auth.recovery_codes (user_id, code_hash);

-- A pending second factor: 5 minutes, single use, 5 attempts. Raw token never stored.
CREATE TABLE auth.mfa_challenges
(
    id          uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    token_hash  char(64)    NOT NULL UNIQUE,
    user_id     uuid        NOT NULL REFERENCES auth.users (id) ON DELETE CASCADE,
    auth_method varchar(20) NOT NULL,
    attempts    smallint    NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL,
    consumed_at timestamptz
);
CREATE INDEX mfa_challenges_expiry_idx ON auth.mfa_challenges (expires_at);

CREATE TABLE auth.known_devices
(
    id          uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    user_id     uuid        NOT NULL REFERENCES auth.users (id) ON DELETE CASCADE,
    fingerprint char(64)    NOT NULL,
    first_seen  timestamptz NOT NULL DEFAULT now(),
    last_seen   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, fingerprint)
);

INSERT INTO auth.roles (id, name, description)
VALUES (5, 'ROLE_SUPPORT', 'Customer-support agent: tickets and read access to orders'),
       (6, 'ROLE_FINANCE', 'Finance: payouts, refunds and coupons')
ON CONFLICT (id) DO NOTHING;
