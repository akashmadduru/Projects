-- =============================================================================
-- rating-service baseline schema (Phase 4 slice).
--
-- Database `rating_db` is created by infra/postgres/init/01-schemas.sql and
-- owned by the `rating_service` role. Everything below lives in the `rating`
-- SCHEMA.
--
-- Flyway is configured with `schemas: rating` / `default-schema: rating`, so
-- it creates the schema and its own history table here.
--
-- FORWARD-ONLY, same posture as product-service and inventory-service: no
-- down migrations, all new columns are additive with NULLable defaults.
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE SCHEMA IF NOT EXISTS rating;

-- =============================================================================
-- ratings
--
-- One rating/review of a product by a user. Optimistic locking via @Version.
-- =============================================================================
CREATE TABLE rating.ratings
(
    id                    uuid PRIMARY KEY     DEFAULT gen_random_uuid(),

    -- FK to product-service. Denormalized; no mapped JPA relationship.
    product_id            uuid         NOT NULL,

    -- FK to auth-service users. The user who wrote this rating. NOT exposed
    -- in list endpoints (only returned on detail/edit), per the plan's RBAC.
    user_id               uuid         NOT NULL,

    -- Denormalized display name of the rating author. Nullable if the user
    -- account was deleted but the rating remained.
    user_display_name     varchar(200),

    -- Rating value on a 1-5 scale. Domain validation ensures this.
    rating_value          integer      NOT NULL
        CONSTRAINT ratings_rating_value_check CHECK (rating_value >= 1 AND rating_value <= 5),

    -- Review title. Nullable if only a rating with no review text.
    review_title          varchar(200),

    -- Full review text. Nullable; a rating can exist without a review.
    review_text           text,

    -- Flag indicating this is a verified purchase. Defaults to false; set by
    -- order-service integration in a future phase.
    is_verified_purchase  boolean      NOT NULL DEFAULT false,

    -- Aggregate count of users who found this review helpful. Defaults to 0.
    helpful_count         integer      NOT NULL DEFAULT 0,

    -- Aggregate count of users who marked it unhelpful. Defaults to 0.
    unhelpful_count       integer      NOT NULL DEFAULT 0,

    -- Optimistic-concurrency token. Checked explicitly by the service before
    -- any write is applied.
    version               bigint       NOT NULL DEFAULT 0,

    created_at            timestamptz  NOT NULL DEFAULT now(),
    updated_at            timestamptz  NOT NULL DEFAULT now()
);

COMMENT ON TABLE rating.ratings IS 'Product ratings and reviews.';
COMMENT ON COLUMN rating.ratings.product_id IS 'FK to product-service. Not a mapped JPA relationship.';
COMMENT ON COLUMN rating.ratings.user_id IS 'FK to auth-service users. Not exposed in list endpoints.';
COMMENT ON COLUMN rating.ratings.user_display_name IS 'Denormalized author display name.';
COMMENT ON COLUMN rating.ratings.rating_value IS 'Rating on a 1-5 scale.';
COMMENT ON COLUMN rating.ratings.is_verified_purchase IS 'Flag indicating verified purchase status.';
COMMENT ON COLUMN rating.ratings.helpful_count IS 'Count of helpful votes.';
COMMENT ON COLUMN rating.ratings.unhelpful_count IS 'Count of unhelpful votes.';
COMMENT ON COLUMN rating.ratings.version IS 'Optimistic-concurrency token. JPA @Version.';

-- =============================================================================
-- rating_votes
--
-- User votes on whether a rating was helpful or unhelpful. UNIQUE constraint
-- on (rating_id, user_id) makes vote addition idempotent: a user can change
-- their vote (helpful -> unhelpful, vice versa) but only one vote per rating
-- per user can exist.
-- =============================================================================
CREATE TABLE rating.rating_votes
(
    id          uuid PRIMARY KEY     DEFAULT gen_random_uuid(),

    -- FK to rating.ratings. Deletes cascade so votes are cleaned up when a
    -- rating is deleted.
    rating_id   uuid         NOT NULL REFERENCES rating.ratings (id) ON DELETE CASCADE,

    -- FK to auth-service users. The user casting this vote.
    user_id     uuid         NOT NULL,

    -- Vote type: HELPFUL or UNHELPFUL. Enum stored as varchar.
    vote_type   varchar(32)  NOT NULL
        CONSTRAINT rating_votes_vote_type_check CHECK (vote_type IN ('HELPFUL', 'UNHELPFUL')),

    created_at  timestamptz  NOT NULL DEFAULT now(),

    -- UNIQUE constraint: one vote per user per rating.
    CONSTRAINT rating_votes_unique_user_rating UNIQUE (rating_id, user_id)
);

COMMENT ON TABLE rating.rating_votes IS 'Helpful/unhelpful votes on ratings.';
COMMENT ON COLUMN rating.rating_votes.rating_id IS 'FK to rating.ratings, cascade delete.';
COMMENT ON COLUMN rating.rating_votes.user_id IS 'FK to auth-service users.';
COMMENT ON COLUMN rating.rating_votes.vote_type IS 'HELPFUL or UNHELPFUL.';

-- =============================================================================
-- Indexes
-- =============================================================================

-- Queries: "get all ratings for product X"
CREATE INDEX ratings_product_id_idx ON rating.ratings (product_id);

-- Queries: "get all ratings by user X" (for profile pages, future phase)
CREATE INDEX ratings_user_id_idx ON rating.ratings (user_id);

-- Queries: "get new ratings" or "ratings modified recently"
CREATE INDEX ratings_created_at_idx ON rating.ratings (created_at);

-- Queries: "ratings with minimum star value"
CREATE INDEX ratings_rating_value_idx ON rating.ratings (rating_value);

-- Votes lookups: "did user X vote on rating Y?"
CREATE INDEX rating_votes_rating_id_idx ON rating.rating_votes (rating_id);

-- Votes lookups: "all votes by user X"
CREATE INDEX rating_votes_user_id_idx ON rating.rating_votes (user_id);
