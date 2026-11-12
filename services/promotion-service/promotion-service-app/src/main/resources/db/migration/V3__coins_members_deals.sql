-- Phase 8: loyalty coins ledger + members-only early access on deals. Forward-only.
CREATE TABLE promotion.coin_ledger
(
    id           uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    user_id      uuid         NOT NULL,
    delta        integer      NOT NULL CHECK (delta <> 0),
    reason       varchar(20)  NOT NULL CHECK (reason IN
                 ('EARN_ORDER', 'REDEEM_ORDER', 'REVERSE_ORDER', 'REFERRAL', 'ADJUST', 'EXPIRE')),
    reference_id varchar(100) NOT NULL,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    expires_at   timestamptz,
    CONSTRAINT uq_coin_ledger UNIQUE (reason, reference_id, user_id)
);
CREATE INDEX ix_coin_ledger_user ON promotion.coin_ledger (user_id, created_at DESC);
CREATE INDEX ix_coin_ledger_ref ON promotion.coin_ledger (reference_id);

ALTER TABLE promotion.deals ADD COLUMN members_only_until timestamptz;
