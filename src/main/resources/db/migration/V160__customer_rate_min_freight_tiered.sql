-- =============================================================================
-- V160 — Customer rate card extras (portal roadmap 11.6).
--
-- min_freight: the least freight a parcel is charged on this card (0 = none). Freight =
--   max(slab freight, min_freight), before fuel %.
-- slab_mode: WHOLE (default, every existing card) = the slab the chargeable weight falls in
--   prices the whole weight; TIERED = each slab prices only the kilos inside it
--   (0-5 kg @ 40, 5-10 kg @ 30: 7 kg = 5 x 40 + 2 x 30). A fixed slab adds its flat price
--   once the weight enters it.
-- =============================================================================

ALTER TABLE customer_zone_rates
    ADD COLUMN min_freight DECIMAL(12, 2) NOT NULL DEFAULT 0 AFTER min_chargeable_weight,
    ADD COLUMN slab_mode VARCHAR(10) NOT NULL DEFAULT 'WHOLE' AFTER min_freight;
