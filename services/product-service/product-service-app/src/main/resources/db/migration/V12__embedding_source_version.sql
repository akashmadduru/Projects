-- Which version of the product each embedding row was last verified against (TD-AI-01).
--
-- The reconciler used to compare products.updated_at with checked_at, the SNAPSHOT time of the
-- run. Any product edited within the snapshot's skew margin (or stamped in the future) therefore
-- still looked stale right after being processed and was picked again by every batch of the same
-- run, up to max-batches-per-run, re-sending changed text to the provider each time.
--
-- Now the row records the products.updated_at it actually read. "Edited since verified" is
-- products.updated_at > source_updated_at, which an edit landing between read and write still
-- satisfies. checked_at stays the wall-clock time of the check, used only by the full sweep.
--
-- Additive: existing rows start from checked_at, which keeps their current stale/fresh state.

ALTER TABLE product.product_embeddings
    ADD COLUMN source_updated_at timestamptz NOT NULL DEFAULT timestamptz 'epoch';

UPDATE product.product_embeddings SET source_updated_at = checked_at;
