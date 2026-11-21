-- =============================================================================
-- V167 — Volumetric divisor per service type.
--
-- master_service_types.volumetric_divisor: the cm3-per-kg divisor of L x W x H for
--   bookings on this service (Air is commonly 5000, Surface 4000). NULL keeps the
--   deployment default (pricing.volumetric-divisor, 5000).
-- =============================================================================

ALTER TABLE master_service_types
    ADD COLUMN volumetric_divisor INT NULL AFTER priority;
