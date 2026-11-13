-- Phase 10: display-only FX rates (admin-editable). One row per currency, all rows share one base.
CREATE TABLE fx_rates (
    currency   VARCHAR(3)    PRIMARY KEY,
    base       VARCHAR(3)    NOT NULL,
    rate       NUMERIC(18,6) NOT NULL CHECK (rate > 0),
    updated_at TIMESTAMPTZ   NOT NULL DEFAULT now()
);

INSERT INTO fx_rates (currency, base, rate) VALUES
    ('USD', 'USD', 1),
    ('INR', 'USD', 83.2),
    ('EUR', 'USD', 0.92);
