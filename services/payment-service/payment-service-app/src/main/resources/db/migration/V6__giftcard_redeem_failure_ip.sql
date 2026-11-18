-- TD-P8-21: per-IP limit on failed gift card redeems, beside the per-user one. Nullable: rows from
-- before this change, and callers with no address, only count toward the user limit.
ALTER TABLE payment.gift_card_redeem_failures ADD COLUMN ip varchar(45);
CREATE INDEX ix_gift_card_redeem_failures_ip ON payment.gift_card_redeem_failures (ip, failed_at)
    WHERE ip IS NOT NULL;
