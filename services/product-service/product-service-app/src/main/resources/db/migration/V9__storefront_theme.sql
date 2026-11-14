-- Admin-editable theme & branding shared by the admin console and the storefront.
-- Single-row table (id is pinned to 1); `config` is validated JSON written by ThemeController.
-- No row = "use the built-in Space-Age Atomic theme".
CREATE TABLE theme_settings (
    id         SMALLINT    PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    config     TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
