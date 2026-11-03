-- =============================================================================
-- V154 — A short note on a quoted option, shown to the customer next to its price
-- (for example "Minimum chargeable weight 20 kg" on a Cargo option).
-- =============================================================================

ALTER TABLE rate_quote_options
    ADD COLUMN note VARCHAR(255) NULL AFTER provider_quote_ref;
