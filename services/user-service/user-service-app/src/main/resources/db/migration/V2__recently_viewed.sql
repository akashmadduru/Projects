-- Phase 7: signed-in recently viewed products (last 50 per user).
CREATE TABLE users.recently_viewed
(
    user_id    uuid        NOT NULL,
    product_id uuid        NOT NULL,
    viewed_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, product_id)
);
CREATE INDEX ix_recently_viewed_user_time ON users.recently_viewed (user_id, viewed_at DESC);
