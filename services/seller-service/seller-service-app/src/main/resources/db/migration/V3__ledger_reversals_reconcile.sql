-- Ledger integrity part A: FEE_REVERSAL entry type, shipment register, discrepancy report.
-- Plain DROP (no IF EXISTS): a wrong constraint name must fail the migration loudly.
ALTER TABLE ledger_entries DROP CONSTRAINT ledger_entries_type_check;
ALTER TABLE ledger_entries ADD CONSTRAINT ledger_entries_type_check
    CHECK (type IN ('SALE', 'COMMISSION', 'FEE', 'REFUND_REVERSAL', 'COMMISSION_REVERSAL', 'FEE_REVERSAL'));

-- What order-service announced per shipment (fed from every order.events message with shipments).
CREATE TABLE ledger_shipments
(
    shipment_id uuid PRIMARY KEY,
    order_id    uuid           NOT NULL,
    seller_id   uuid           NOT NULL,
    status      varchar(30)    NOT NULL,
    amount      numeric(14, 2) NOT NULL,
    currency    varchar(3)     NOT NULL,
    updated_at  timestamptz    NOT NULL DEFAULT now()
);

-- Existing deliveries are seeded from their SALE rows.
INSERT INTO ledger_shipments (shipment_id, order_id, seller_id, status, amount, currency, updated_at)
SELECT shipment_id, order_id, seller_id, 'DELIVERED', amount, currency, created_at
FROM ledger_entries
WHERE type = 'SALE' AND return_id IS NULL;

-- Report-only findings of the reconcile job; never posts to the ledger.
CREATE TABLE ledger_discrepancies
(
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    shipment_id   uuid        NOT NULL,
    order_id      uuid,
    seller_id     uuid,
    kind          varchar(30) NOT NULL
        CHECK (kind IN ('MISSING_SALE', 'AMOUNT_MISMATCH', 'OVER_REVERSED', 'STATE_MISMATCH', 'UNMATCHED_REVERSAL')),
    ref           uuid,
    expected      numeric(14, 2),
    actual        numeric(14, 2),
    detail        text,
    first_seen_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at  timestamptz NOT NULL DEFAULT now(),
    resolved_at   timestamptz,
    resolved_by   varchar(100),
    CONSTRAINT ux_ledger_discrepancy UNIQUE NULLS NOT DISTINCT (shipment_id, kind, ref)
);
CREATE INDEX ix_ledger_discrepancy_open ON ledger_discrepancies (first_seen_at) WHERE resolved_at IS NULL;
