-- =============================================================================
-- V153 — A customer rate slab can be a FIXED price for the whole slab.
--
-- fixed_rate = FALSE (default, every existing slab): freight = chargeable weight x rate_per_kg.
-- fixed_rate = TRUE: the slab's rate is a flat amount: any weight in the slab costs exactly that
-- (slab 0-10 kg at 100 fixed: 1 kg is 100 and 10 kg is 100).
-- =============================================================================

ALTER TABLE customer_zone_rate_slabs
    ADD COLUMN fixed_rate BOOLEAN NOT NULL DEFAULT FALSE AFTER rate_per_kg;
