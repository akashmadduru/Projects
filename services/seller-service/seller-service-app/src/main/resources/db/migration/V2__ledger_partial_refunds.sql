-- Partial refunds: one reversal pair per return (not per shipment).
ALTER TABLE ledger_entries ADD COLUMN return_id uuid;
ALTER TABLE ledger_entries DROP CONSTRAINT ux_ledger_shipment_type;
CREATE UNIQUE INDEX ux_ledger_shipment_type ON ledger_entries (shipment_id, type) WHERE return_id IS NULL;
CREATE UNIQUE INDEX ux_ledger_return_type ON ledger_entries (return_id, type) WHERE return_id IS NOT NULL;
