-- =============================================================================
-- product-service: amazon enrichment (Phase 4 slice - schema).
--
-- Additive enrichment columns on product.products for Amazon product
-- attributes: descriptions, pricing variants, media, ratings, seller info,
-- and specifications. Per the phase 2-only doctrine, all new columns are
-- NULLABLE or DEFAULT-backed so existing rows never need backfill.
--
-- NEW INDEXES on commonly-filtered columns (is_featured, average_rating,
-- sale_price, sku, created_at) to support the filtering/search paths Phase 5
-- will expose.
-- =============================================================================

ALTER TABLE product.products
    -- Long-form product description; nullable enrichment field.
    ADD COLUMN description text,

    -- List price before discount. For products with a discount, this is the
    -- original MSRP. Nullable - not all products have a list price.
    ADD COLUMN list_price numeric(19, 2),

    -- Sale/current price. Can differ from price_amount in certain contexts,
    -- or be the same. Nullable so not every enrichment requires both prices.
    ADD COLUMN sale_price numeric(19, 2),

    -- Discount percentage (0-100) calculated as ((list_price - sale_price) /
    -- list_price * 100). Stored for query efficiency - avoids recalculating
    -- on every read. Nullable.
    ADD COLUMN discount_percent numeric(5, 2),

    -- Primary product image thumbnail URL. Nullable.
    ADD COLUMN thumbnail_url varchar(2048),

    -- Array of video URLs (stored as JSONB for flexibility). Nullable.
    ADD COLUMN video_urls jsonb,

    -- 360-degree view URL. Nullable.
    ADD COLUMN view_360_url varchar(2048),

    -- Weight in grams. Nullable.
    ADD COLUMN weight_grams integer,

    -- Dimensions in cm.
    ADD COLUMN dimensions_length numeric(10, 2),
    ADD COLUMN dimensions_width numeric(10, 2),
    ADD COLUMN dimensions_height numeric(10, 2),

    -- Product color. Nullable.
    ADD COLUMN color varchar(100),

    -- Primary material. Nullable.
    ADD COLUMN material varchar(100),

    -- Denormalized average rating (0-5). Updated by the rating-service
    -- listener when ratings are added/updated. Nullable.
    ADD COLUMN average_rating numeric(3, 2),

    -- Count of ratings. Updated by the rating-service listener. Defaults
    -- to 0 so SUM() aggregates work correctly.
    ADD COLUMN rating_count integer NOT NULL DEFAULT 0,

    -- Count of reviews (review_text is not null). Defaults to 0.
    ADD COLUMN review_count integer NOT NULL DEFAULT 0,

    -- Rating distribution as JSONB, e.g. {1: 10, 2: 20, 3: 50, 4: 100, 5:
    -- 200}. Nullable.
    ADD COLUMN rating_distribution jsonb,

    -- Flag for storefront featured/promoted products. Used for homepage and
    -- curated sections. Defaults to false.
    ADD COLUMN is_featured boolean NOT NULL DEFAULT false,

    -- Seller's own rating (separate from product ratings). Nullable.
    ADD COLUMN seller_rating numeric(3, 2),

    -- Flag indicating the seller is verified. Defaults to false.
    ADD COLUMN is_seller_verified boolean NOT NULL DEFAULT false;

-- =============================================================================
-- Comments for new columns
-- =============================================================================

COMMENT ON COLUMN product.products.description IS 'Long-form product description for detail views.';
COMMENT ON COLUMN product.products.list_price IS 'MSRP or original price before discount.';
COMMENT ON COLUMN product.products.sale_price IS 'Current selling price, may differ from price_amount.';
COMMENT ON COLUMN product.products.discount_percent IS 'Calculated discount percentage (0-100).';
COMMENT ON COLUMN product.products.thumbnail_url IS 'Primary product image URL for list views.';
COMMENT ON COLUMN product.products.video_urls IS 'Array of video URLs (JSONB).';
COMMENT ON COLUMN product.products.view_360_url IS 'URL for 360-degree product view.';
COMMENT ON COLUMN product.products.weight_grams IS 'Product weight in grams.';
COMMENT ON COLUMN product.products.dimensions_length IS 'Product length in cm.';
COMMENT ON COLUMN product.products.dimensions_width IS 'Product width in cm.';
COMMENT ON COLUMN product.products.dimensions_height IS 'Product height in cm.';
COMMENT ON COLUMN product.products.color IS 'Primary product color.';
COMMENT ON COLUMN product.products.material IS 'Primary material composition.';
COMMENT ON COLUMN product.products.average_rating IS 'Denormalized average rating (0-5), updated by rating-service listener.';
COMMENT ON COLUMN product.products.rating_count IS 'Total count of ratings, updated by rating-service listener.';
COMMENT ON COLUMN product.products.review_count IS 'Count of text reviews, updated by rating-service listener.';
COMMENT ON COLUMN product.products.rating_distribution IS 'Rating count distribution by star value (JSONB).';
COMMENT ON COLUMN product.products.is_featured IS 'Flag for featured/promoted products on storefront.';
COMMENT ON COLUMN product.products.seller_rating IS 'Seller''s own rating separate from product ratings.';
COMMENT ON COLUMN product.products.is_seller_verified IS 'Flag indicating seller verification status.';

-- =============================================================================
-- Indexes on commonly-filtered/sorted columns
-- =============================================================================

-- Featured products listing
CREATE INDEX products_is_featured_idx ON product.products (is_featured) WHERE is_featured = true;

-- Rating-based filtering and sorting
CREATE INDEX products_average_rating_idx ON product.products (average_rating);

-- Price-range filtering
CREATE INDEX products_sale_price_idx ON product.products (sale_price);

-- Temporal queries and ordering
CREATE INDEX products_created_at_idx ON product.products (created_at);

-- NOTE: A UNIQUE constraint automatically creates an index on the constrained
-- column(s), so an explicit INDEX on sku is redundant. The sku column has
-- UNIQUE (sku) constraint from V1, which provides the necessary index.
