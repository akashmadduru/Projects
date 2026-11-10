-- =============================================================================
-- inventory-service: amazon enrichment (Phase 4 slice - schema).
--
-- Additive enrichment columns on inventory.stock_levels for inventory
-- status tracking, reserved quantities, and last-update timestamps. All
-- new columns are NULLABLE or DEFAULT-backed per the additive-only doctrine.
--
-- NEW INDEX on status for efficient filtering.
-- =============================================================================

ALTER TABLE inventory.stock_levels
    -- Reserved quantity (items allocated but not yet shipped). Defaults to 0
    -- so availability calculations (quantity - reserved) work correctly for
    -- existing rows without backfill.
    ADD COLUMN reserved_quantity integer NOT NULL DEFAULT 0,

    -- Status of inventory: IN_STOCK, LOW_STOCK, OUT_OF_STOCK. Enum stored
    -- as varchar for maximum compatibility. Nullable initially; populated by
    -- service logic and event listeners.
    ADD COLUMN status varchar(32),

    -- Timestamp of last inventory update. Nullable initially; updated whenever
    -- quantity, reserved_quantity, or status changes.
    ADD COLUMN last_updated_at timestamptz;

-- =============================================================================
-- Comments for new columns
-- =============================================================================

COMMENT ON COLUMN inventory.stock_levels.reserved_quantity IS 'Items allocated but not yet shipped. Used to calculate available quantity.';
COMMENT ON COLUMN inventory.stock_levels.status IS 'Inventory status: IN_STOCK, LOW_STOCK, OUT_OF_STOCK.';
COMMENT ON COLUMN inventory.stock_levels.last_updated_at IS 'Timestamp of the last inventory update.';

-- =============================================================================
-- Constraints
-- =============================================================================

-- Ensure reserved_quantity never exceeds total quantity (checked at application level, but documented here)
ALTER TABLE inventory.stock_levels
    ADD CONSTRAINT stock_levels_reserved_check CHECK (reserved_quantity >= 0 AND reserved_quantity <= quantity);

-- Ensure valid status values
ALTER TABLE inventory.stock_levels
    ADD CONSTRAINT stock_levels_status_check CHECK (status IS NULL OR status IN ('IN_STOCK', 'LOW_STOCK', 'OUT_OF_STOCK'));

-- =============================================================================
-- Indexes
-- =============================================================================

-- Status filtering for inventory queries
CREATE INDEX stock_levels_status_idx ON inventory.stock_levels (status);

-- Query by warehouse for multi-warehouse setups
CREATE INDEX stock_levels_warehouse_code_idx ON inventory.stock_levels (warehouse_code);

-- Last updated for inventory audit trails
CREATE INDEX stock_levels_last_updated_at_idx ON inventory.stock_levels (last_updated_at);
