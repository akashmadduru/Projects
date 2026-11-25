-- TD-P8-06: gift card codes are no longer stored in plain text.
--
-- The code is generated when the card is paid for and emailed straight away; only its SHA-256 hash
-- (for redemption lookup) and last 4 characters (for display) are kept. Codes are 16 random
-- characters from a 32-letter alphabet (80 bits), so an unsalted hash cannot be brute-forced.
--
-- Additive and forward-only: the old `code` column is kept, nullable and emptied, so the previous
-- image still starts if a release is rolled back (it would just show no code). Drop it later.

ALTER TABLE payment.gift_cards
    ADD COLUMN code_hash      char(64),
    ADD COLUMN code_last4     char(4),
    ADD COLUMN code_issued_at timestamptz;

UPDATE payment.gift_cards
SET code_hash      = encode(sha256(convert_to(code, 'UTF8')), 'hex'),
    code_last4     = right(code, 4),
    code_issued_at = created_at
WHERE code IS NOT NULL
  AND status <> 'PENDING_PAYMENT';

ALTER TABLE payment.gift_cards ALTER COLUMN code DROP NOT NULL;
UPDATE payment.gift_cards SET code = NULL;

CREATE UNIQUE INDEX ux_gift_cards_code_hash ON payment.gift_cards (code_hash)
    WHERE code_hash IS NOT NULL;

-- Failed redeem attempts, for the per-user lockout. Rows older than a day are swept.
CREATE TABLE payment.gift_card_redeem_failures
(
    user_id   uuid        NOT NULL,
    failed_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_gift_card_redeem_failures ON payment.gift_card_redeem_failures (user_id, failed_at);
