-- TD-P8-23: a wallet debit that failed ambiguously (timeout after payment-service committed) is
-- reversed through the same durable retry table. REVERSE_DEBIT rows ask payment-service to give
-- back the debit with debit_key only if it exists; they share idem_key with the normal credit-back
-- of the same order, so the two can never both pay out.
ALTER TABLE orders.pending_wallet_credits
    ADD COLUMN kind      varchar(20)  NOT NULL DEFAULT 'CREDIT'
        CONSTRAINT ck_pending_wallet_credits_kind CHECK (kind IN ('CREDIT', 'REVERSE_DEBIT')),
    ADD COLUMN debit_key varchar(150);
