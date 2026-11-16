-- =============================================================================
-- product-service: Cartwise AI-1 - semantic product search (pgvector).
-- ADDITIVE ONLY.
--
-- THE EXTENSION IS A PRECONDITION, NOT SOMETHING THIS ROLE CAN CREATE. `vector` is not a
-- "trusted" extension (unlike pg_trgm, V6): the product_service role is not a superuser, so
-- CREATE EXTENSION fails for it with "permission denied". It is therefore installed by the
-- database bootstrap, which runs as a superuser:
--     local, new volume      deploy/local/postgres/init/01-schemas.sql
--     local, existing volume deploy/local/scripts/create-customer-dbs.sql
--     AWS (RDS)              deploy/aws/k8s/db-bootstrap.yaml
-- The statement below is a no-op when it is already there (and succeeds for a non-superuser,
-- because IF NOT EXISTS short-circuits before the privilege check); it only does real work where
-- the migrating role IS privileged (Testcontainers' default user).
--
-- WHY SCHEMA `public`, NOT `product` LIKE pg_trgm. The bootstrap runs BEFORE Flyway has created
-- the `product` schema, and Flyway refuses to start in a pre-existing schema that is non-empty
-- but has no history table. `public` exists from the start and Flyway does not manage it.
-- EVERY use of the type, the operator class and the operators is schema-qualified
-- (public.vector, public.vector_cosine_ops, OPERATOR(public.<=>)): Flyway migrates with
-- search_path=product while the runtime default is `public`, so an unqualified name would work in
-- one and fail in the other.
-- =============================================================================
DO
$$
BEGIN
    CREATE EXTENSION IF NOT EXISTS vector SCHEMA public;
EXCEPTION
    WHEN insufficient_privilege THEN
        RAISE EXCEPTION 'pgvector is not installed in this database and this role cannot create it'
            USING HINT = 'As a superuser run: CREATE EXTENSION IF NOT EXISTS vector SCHEMA public; '
                         '(see deploy/local/scripts/create-customer-dbs.sql)';
END
$$;

-- -----------------------------------------------------------------------------
-- product_embeddings: one vector per product, in its OWN table (not a column on products): the
-- wide vector stays out of every catalog row read, and (re)embedding never touches
-- products.version or products.updated_at.
--
-- vector(1024) = Amazon Titan Text Embeddings V2 at 1024 dimensions. The width is part of the
-- column type: a different model/dimension is a NEW table plus a cutover, never an ALTER. `model`
-- and `dims` are stored so a model change is detectable, and the query always filters on the
-- current model.
--
-- content_hash = sha256(embedding text + "|" + model). The reconciler recomputes it and skips the
-- provider when unchanged. checked_at = when the product was last verified against its row; the
-- reconciler's cheap prefilter is `products.updated_at > checked_at`.
-- -----------------------------------------------------------------------------
CREATE TABLE product.product_embeddings
(
    product_id   uuid PRIMARY KEY REFERENCES product.products (id) ON DELETE CASCADE,
    embedding    public.vector(1024) NOT NULL,
    model        text                NOT NULL,
    dims         integer             NOT NULL,
    content_hash char(64)            NOT NULL,
    embedded_at  timestamptz         NOT NULL DEFAULT now(),
    checked_at   timestamptz         NOT NULL DEFAULT now()
);

COMMENT ON TABLE product.product_embeddings IS 'Semantic-search vectors, maintained by the EmbeddingReconciler. Advisory: search degrades to keyword without them.';

-- Cosine distance (the vectors are L2-normalised, so it ranks like dot product). m/ef_construction
-- are pgvector's defaults, stated so a change is a deliberate migration.
CREATE INDEX idx_product_embeddings_hnsw
    ON product.product_embeddings USING hnsw (embedding public.vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- Reconciler prefilter / coverage counts.
CREATE INDEX idx_product_embeddings_checked_at ON product.product_embeddings (checked_at);

-- -----------------------------------------------------------------------------
-- embedding_failures: per-product retry state, so one product the provider rejects (or a provider
-- outage) backs off exponentially instead of being retried every minute forever.
-- -----------------------------------------------------------------------------
CREATE TABLE product.embedding_failures
(
    product_id      uuid PRIMARY KEY REFERENCES product.products (id) ON DELETE CASCADE,
    content_hash    char(64)    NOT NULL,
    attempts        integer     NOT NULL DEFAULT 1,
    last_error      text,
    next_attempt_at timestamptz NOT NULL
);

COMMENT ON TABLE product.embedding_failures IS 'Products whose embedding failed; skipped by the reconciler until next_attempt_at.';
