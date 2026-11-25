-- =============================================================================
-- V175 — Each tariff zone stores how many places it holds (user, 2026-11-25)
--
-- The Zones list shows the count only, read straight from the zone row; a zone's places load page by page when
-- the zone is opened. The service keeps the count in step as places are added and removed.
-- =============================================================================

ALTER TABLE tariff_zones ADD COLUMN location_count INT NOT NULL DEFAULT 0 AFTER display_order;

UPDATE tariff_zones z
SET z.location_count = (SELECT COUNT(*) FROM tariff_zone_locations l WHERE l.zone_id = z.id AND l.deleted = FALSE);
