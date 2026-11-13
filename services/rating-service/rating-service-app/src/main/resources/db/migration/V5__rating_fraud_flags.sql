-- Phase 10: fake-review heuristics. Flagged reviews are excluded from averages until an admin approves.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
ALTER TABLE rating.ratings
    ADD COLUMN flagged      boolean NOT NULL DEFAULT false,
    ADD COLUMN flag_reasons text,
    ADD COLUMN moderated    boolean NOT NULL DEFAULT false;
CREATE INDEX ix_ratings_flagged ON rating.ratings (created_at DESC) WHERE flagged;
CREATE INDEX ix_ratings_user_created ON rating.ratings (user_id, created_at DESC);
