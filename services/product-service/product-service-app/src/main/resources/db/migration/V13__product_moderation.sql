-- TD-P9-03: products listed by marketplace sellers are reviewed before they go live.
-- Existing products (platform and seller) are APPROVED, so nothing already on sale disappears.
ALTER TABLE product.products
    ADD COLUMN moderation_status varchar(20) NOT NULL DEFAULT 'APPROVED'
        CONSTRAINT ck_products_moderation_status
            CHECK (moderation_status IN ('APPROVED', 'PENDING_REVIEW', 'REJECTED')),
    ADD COLUMN moderation_note   varchar(500),
    ADD COLUMN reviewed_by       uuid,
    ADD COLUMN reviewed_at       timestamptz;

CREATE INDEX idx_products_moderation_queue ON product.products (created_at)
    WHERE moderation_status = 'PENDING_REVIEW';
