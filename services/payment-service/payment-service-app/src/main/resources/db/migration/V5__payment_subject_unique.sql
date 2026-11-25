-- TD-P8-14: a payment's subject is (purpose, reference_id): an order, a membership or a gift card.
-- order_id still carries the same value for every purpose (inserts write it to both columns), and its
-- own UNIQUE stays. This constraint makes the real invariant explicit, so it survives if the two
-- columns ever diverge. Existing rows satisfy it because reference_id = order_id on every row.
ALTER TABLE payment.payments
    ADD CONSTRAINT ux_payments_purpose_reference UNIQUE (purpose, reference_id);
