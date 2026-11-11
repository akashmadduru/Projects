-- =============================================================================
-- product-service: category enrichment (Phase 4 slice - schema).
--
-- ADDITIVE ONLY, per V1-V4's convention: no existing column is widened,
-- renamed, or dropped. Lands the columns Category.java and CategoryService
-- already map but no earlier migration created - V3 shaped product.categories
-- with only id/name/parent_category_id/created_at/updated_at.
--
-- Every new column is NULLABLE or DEFAULT-backed, so existing rows need no
-- manual backfill beyond the depth_level derivation below.
-- =============================================================================

ALTER TABLE product.categories
    -- Long-form category description; nullable enrichment field.
    ADD COLUMN description text,

    -- 0 for a root category, 1 for a subcategory. Computed by CategoryService
    -- from the parent's depth at create time and capped there (MAX_DEPTH = 1) -
    -- still enforced in the application layer, not by a DB constraint, per V3.
    ADD COLUMN depth_level integer NOT NULL DEFAULT 0,

    -- Soft on/off switch for storefront visibility. Defaults to true so every
    -- pre-existing category stays visible.
    ADD COLUMN is_active boolean NOT NULL DEFAULT true,

    -- Optimistic-concurrency token, mirroring product.products.version (V2).
    ADD COLUMN version bigint NOT NULL DEFAULT 0;

-- V3 allowed exactly one level of subcategory, so any pre-existing row with a
-- parent is a depth-1 row. Without this, existing subcategories would read as
-- roots and accept children of their own, breaking the one-level rule.
UPDATE product.categories
SET depth_level = 1
WHERE parent_category_id IS NOT NULL;

COMMENT ON COLUMN product.categories.description IS 'Long-form category description.';
COMMENT ON COLUMN product.categories.depth_level IS '0 = root, 1 = subcategory. Set and capped by CategoryService, not a DB constraint.';
COMMENT ON COLUMN product.categories.is_active IS 'Storefront visibility flag.';
COMMENT ON COLUMN product.categories.version IS 'Optimistic-concurrency token. JPA @Version, not a hand-rolled counter.';
