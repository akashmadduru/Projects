-- Phase 10: privacy. Data export jobs (zip kept 24 h) and account deletion requests (7-day grace).

CREATE TABLE users.export_jobs
(
    id           uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    user_id      uuid        NOT NULL,
    email        varchar(320),
    status       varchar(10) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RUNNING', 'READY', 'FAILED', 'EXPIRED')),
    content      bytea,
    size_bytes   bigint,
    error        varchar(300),
    created_at   timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    expires_at   timestamptz
);
CREATE INDEX ix_export_jobs_user ON users.export_jobs (user_id, created_at DESC);

CREATE TABLE users.deletion_requests
(
    user_id       uuid PRIMARY KEY,
    email         varchar(320),
    status        varchar(12) NOT NULL CHECK (status IN ('SCHEDULED', 'CANCELLED', 'COMPLETED')),
    requested_at  timestamptz NOT NULL DEFAULT now(),
    scheduled_for timestamptz NOT NULL,
    completed_at  timestamptz
);
CREATE INDEX ix_deletion_due ON users.deletion_requests (scheduled_for) WHERE status = 'SCHEDULED';

-- Fresh email confirmation code for accounts without a password (Google / OTP-only).
CREATE TABLE users.delete_codes
(
    user_id    uuid PRIMARY KEY,
    code_hash  char(64)    NOT NULL,
    attempts   smallint    NOT NULL DEFAULT 0,
    expires_at timestamptz NOT NULL
);
