-- =============================================================================
-- One review per user per product (buyers-only reviews feature).
--
-- Removes any pre-existing duplicate (product_id, user_id) rows - keeping the
-- earliest - then enforces uniqueness. Votes on removed rows cascade away.
-- =============================================================================

DELETE FROM rating.ratings r
    USING rating.ratings d
WHERE r.product_id = d.product_id
  AND r.user_id = d.user_id
  AND (r.created_at, r.id) > (d.created_at, d.id);

ALTER TABLE rating.ratings
    ADD CONSTRAINT ux_ratings_product_user UNIQUE (product_id, user_id);

-- Public list sorts: newest (default), most helpful.
CREATE INDEX ratings_product_helpful_idx ON rating.ratings (product_id, helpful_count DESC);
