-- One Razorpay order per payment and the capture-mode watch (F-20261119-04, slice 2). Additive:
-- nullable columns, no default, no rewrite, no index. Old pods ignore them.
--   provision_claimed_at  lease taken by the caller creating the Razorpay order
--   authorized_at         first payment.authorized seen while still unpaid (a candidate marker only)
--   capture_alerted_at    set once when RAZORPAY NOT CAPTURED has been logged
ALTER TABLE payment.payments
    ADD COLUMN provision_claimed_at timestamptz,
    ADD COLUMN authorized_at        timestamptz,
    ADD COLUMN capture_alerted_at   timestamptz;
