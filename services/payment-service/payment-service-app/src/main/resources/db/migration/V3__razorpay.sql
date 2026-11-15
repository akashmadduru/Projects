-- Razorpay alongside Stripe. Forward-only and additive: the Stripe columns stay, so Stripe rows and
-- pods still running the previous version keep working during a rolling deploy.
--   mode                 now also RAZORPAY
--   provider_order_id    the Razorpay order a RAZORPAY payment is made against
--   provider_payment_id  the Razorpay payment that settled it (needed for refunds)
--   provider_refund_id   the Razorpay refund id
-- method stays CARD / COD: CARD means "paid online" (cards, UPI, netbanking, wallets).
ALTER TABLE payment.payments DROP CONSTRAINT IF EXISTS payments_mode_check;
ALTER TABLE payment.payments
    ADD CONSTRAINT ck_payments_mode CHECK (mode IN ('STRIPE', 'RAZORPAY', 'SIMULATED'));

ALTER TABLE payment.payments
    ADD COLUMN provider_order_id   varchar(100),
    ADD COLUMN provider_payment_id varchar(100);
CREATE INDEX ix_payments_provider_order ON payment.payments (provider_order_id)
    WHERE provider_order_id IS NOT NULL;

ALTER TABLE payment.refunds
    ADD COLUMN provider_refund_id varchar(100);
