-- TD-P8-13: referral claim abuse checks. IPs are stored only as keyed hashes (HMAC-SHA256, 64 hex).
CREATE TABLE users.referral_ip_activity
(
    user_id      uuid        NOT NULL,
    ip_hash      char(64)    NOT NULL,
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, ip_hash)
);
CREATE INDEX ix_referral_ip_activity_seen ON users.referral_ip_activity (last_seen_at);

ALTER TABLE users.referral_claims
    ADD COLUMN claim_ip_hash char(64);
CREATE INDEX ix_referral_claims_ip_age ON users.referral_claims (claimed_at) WHERE claim_ip_hash IS NOT NULL;
