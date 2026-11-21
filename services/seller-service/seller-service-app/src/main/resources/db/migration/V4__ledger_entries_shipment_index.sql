-- The reversal sums read every ledger row of a shipment (rows with a return_id are outside the
-- partial unique index), and they run while the SALE row lock is held.
CREATE INDEX ix_ledger_entries_shipment ON ledger_entries (shipment_id);
