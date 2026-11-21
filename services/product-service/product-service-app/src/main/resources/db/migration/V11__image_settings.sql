-- Base URL ("parentUrl") that relative product image keys are resolved against, e.g.
-- parentUrl http://localhost:8090/product-images + key 0a1b.jpg -> http://localhost:8090/product-images/0a1b.jpg.
-- Single-row table (id pinned to 1), same shape as theme_settings. Seeded by product-service on startup
-- from STATIC_IMAGE_SERVER_PUBLIC_URL when empty; changed at runtime via PUT /api/admin/image-settings.
CREATE TABLE image_settings (
    id         SMALLINT     PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    parent_url VARCHAR(500) NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
