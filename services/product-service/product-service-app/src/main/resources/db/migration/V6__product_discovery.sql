-- =============================================================================
-- product-service: Phase 7 discovery & conversion (search, brand/attributes,
-- variants, slugs, product views). ADDITIVE ONLY.
-- =============================================================================

-- Installed into the product schema (the service role cannot CREATE in public on PG15+):
-- objects are referenced as product.<name> (similarity, gin_trgm_ops).
CREATE EXTENSION IF NOT EXISTS pg_trgm SCHEMA product;

ALTER TABLE product.products
    ADD COLUMN brand      varchar(120),
    ADD COLUMN attributes jsonb,
    ADD COLUMN slug       varchar(240);

-- Full-text vector: name (A), brand (B), description (C). Category name is not included
-- (a generated column cannot read another table) - see tech debt.
ALTER TABLE product.products
    ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('english'::regconfig, coalesce(name, '')), 'A') ||
        setweight(to_tsvector('english'::regconfig, coalesce(brand, '')), 'B') ||
        setweight(to_tsvector('english'::regconfig, coalesce(description, '')), 'C')
    ) STORED;

CREATE INDEX idx_products_search_vector ON product.products USING gin (search_vector);
CREATE INDEX idx_products_name_trgm ON product.products USING gin (lower(name) product.gin_trgm_ops);
CREATE INDEX idx_products_brand ON product.products (lower(brand));
CREATE INDEX idx_products_attributes ON product.products USING gin (attributes);

-- Slug generator shared by the backfill and the BEFORE INSERT trigger (seeds / raw inserts).
CREATE FUNCTION product.make_slug(p_name text, p_id uuid) RETURNS text AS $$
DECLARE
    base text;
    candidate text;
BEGIN
    base := trim(both '-' from regexp_replace(lower(coalesce(p_name, '')), '[^a-z0-9]+', '-', 'g'));
    IF base = '' THEN
        base := 'product';
    END IF;
    base := left(base, 200);
    candidate := base;
    IF EXISTS (SELECT 1 FROM product.products WHERE slug = candidate AND id <> p_id) THEN
        candidate := base || '-' || substr(replace(p_id::text, '-', ''), 1, 6);
    END IF;
    RETURN candidate;
END;
$$ LANGUAGE plpgsql;

UPDATE product.products SET slug = product.make_slug(name, id);

CREATE UNIQUE INDEX uq_products_slug ON product.products (slug);

CREATE FUNCTION product.products_set_slug() RETURNS trigger AS $$
BEGIN
    IF NEW.slug IS NULL OR NEW.slug = '' THEN
        NEW.slug := product.make_slug(NEW.name, NEW.id);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_products_set_slug BEFORE INSERT ON product.products
    FOR EACH ROW EXECUTE FUNCTION product.products_set_slug();

-- =============================================================================
-- product_variants
-- =============================================================================
CREATE TABLE product.product_variants
(
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id   uuid         NOT NULL REFERENCES product.products (id) ON DELETE CASCADE,
    sku          varchar(80)  NOT NULL UNIQUE,
    attributes   jsonb        NOT NULL DEFAULT '{}'::jsonb,
    price_amount numeric(19, 2),
    sale_price   numeric(19, 2),
    image_url    varchar(2048),
    active       boolean      NOT NULL DEFAULT true,
    sort_order   integer      NOT NULL DEFAULT 0,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_product_variants_product ON product.product_variants (product_id, sort_order);

-- =============================================================================
-- product_views (recommendations; 30-day retention enforced by a scheduled job)
-- =============================================================================
CREATE TABLE product.product_views
(
    id         bigserial PRIMARY KEY,
    session_id varchar(64) NOT NULL,
    product_id uuid        NOT NULL,
    viewed_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_product_views_session ON product.product_views (session_id, product_id);
CREATE INDEX idx_product_views_product ON product.product_views (product_id);
CREATE INDEX idx_product_views_viewed_at ON product.product_views (viewed_at);
