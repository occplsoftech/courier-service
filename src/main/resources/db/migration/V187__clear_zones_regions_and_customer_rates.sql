-- =============================================================================
-- V187 — Clear zones, regions and customer rates (owner's request, 2026-11-27: "delete all customer rates, and all
-- zone, and all region"; nothing is loaded back — the owner sets them up again).
--
-- Companies OCCPL and OCCPL_COURIER:
--   * Zone Tariff: every rate card (the company's, e.g. PUNE_CASH_2025, and the business customers' CUST-…), its lanes
--     (rate slabs) and charge rules, every tariff zone and its places.
--   * Customer pricing: customer pricing zones, their places, customer zone rates and their slabs, customer structure
--     rates.
-- Global (platform) masters:
--   * Regional Zones master (NORTH, SOUTH, EAST, WEST, CENTRAL…).
--   * Every pincode's Zone and Regional zone are blanked (coverage, TAT, ODA and the rest stay).
--
-- Everything is a soft delete (deleted = TRUE), never a physical delete, after a full copy into backup_20261127c_*.
-- The regional zones' codes and names are freed (suffix "~V187" + a short id) so the same codes can be created again:
-- the master's unique key (company_id, code) does not look at `deleted`. Tariff zones and rate cards check their code
-- in the service, which ignores deleted rows, so their codes are free already.
--
-- With no rate card left, a booking is priced by District Level Freight (or refused when that has no lane) until new
-- zones and rates are set up.
-- =============================================================================

SET @g = UNHEX('00000000000000000000000000000001');
SET @now = UTC_TIMESTAMP(6);

DROP TABLE IF EXISTS tmp_v187_co;
CREATE TABLE tmp_v187_co (company_id BINARY(16) NOT NULL PRIMARY KEY) ENGINE = InnoDB;
INSERT INTO tmp_v187_co (company_id)
SELECT company_id FROM companies WHERE company_code IN ('OCCPL', 'OCCPL_COURIER') AND deleted = FALSE;

-- 1. Backup ------------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS backup_20261127c_tariff_zones LIKE tariff_zones;
CREATE TABLE IF NOT EXISTS backup_20261127c_tariff_zone_locations LIKE tariff_zone_locations;
CREATE TABLE IF NOT EXISTS backup_20261127c_tariff_rate_cards LIKE tariff_rate_cards;
CREATE TABLE IF NOT EXISTS backup_20261127c_tariff_rate_slabs LIKE tariff_rate_slabs;
CREATE TABLE IF NOT EXISTS backup_20261127c_tariff_charge_rules LIKE tariff_charge_rules;
CREATE TABLE IF NOT EXISTS backup_20261127c_customer_pricing_zones LIKE customer_pricing_zones;
CREATE TABLE IF NOT EXISTS backup_20261127c_customer_zone_locations LIKE customer_zone_locations;
CREATE TABLE IF NOT EXISTS backup_20261127c_customer_zone_rates LIKE customer_zone_rates;
CREATE TABLE IF NOT EXISTS backup_20261127c_customer_zone_rate_slabs LIKE customer_zone_rate_slabs;
CREATE TABLE IF NOT EXISTS backup_20261127c_customer_structure_rates LIKE customer_structure_rates;
CREATE TABLE IF NOT EXISTS backup_20261127c_master_regional_zones LIKE master_regional_zones;
DROP TABLE IF EXISTS backup_20261127c_pincode_zones;
CREATE TABLE backup_20261127c_pincode_zones (id BINARY(16) NOT NULL PRIMARY KEY, zone VARCHAR(20) NULL,
                                              regional_zone_id BINARY(16) NULL) ENGINE = InnoDB;
INSERT IGNORE INTO backup_20261127c_tariff_zones SELECT * FROM tariff_zones;
INSERT IGNORE INTO backup_20261127c_tariff_zone_locations SELECT * FROM tariff_zone_locations;
INSERT IGNORE INTO backup_20261127c_tariff_rate_cards SELECT * FROM tariff_rate_cards;
INSERT IGNORE INTO backup_20261127c_tariff_rate_slabs SELECT * FROM tariff_rate_slabs;
INSERT IGNORE INTO backup_20261127c_tariff_charge_rules SELECT * FROM tariff_charge_rules;
INSERT IGNORE INTO backup_20261127c_customer_pricing_zones SELECT * FROM customer_pricing_zones;
INSERT IGNORE INTO backup_20261127c_customer_zone_locations SELECT * FROM customer_zone_locations;
INSERT IGNORE INTO backup_20261127c_customer_zone_rates SELECT * FROM customer_zone_rates;
INSERT IGNORE INTO backup_20261127c_customer_zone_rate_slabs SELECT * FROM customer_zone_rate_slabs;
INSERT IGNORE INTO backup_20261127c_customer_structure_rates SELECT * FROM customer_structure_rates;
INSERT IGNORE INTO backup_20261127c_master_regional_zones SELECT * FROM master_regional_zones;
INSERT INTO backup_20261127c_pincode_zones (id, zone, regional_zone_id)
SELECT id, zone, regional_zone_id FROM master_pincodes WHERE zone IS NOT NULL OR regional_zone_id IS NOT NULL;

-- 2. Zone Tariff: lanes, charges, cards, places, zones -------------------------------------------------------
UPDATE tariff_rate_slabs s JOIN tmp_v187_co c ON c.company_id = s.company_id
SET s.deleted = TRUE, s.deleted_at = @now, s.updated_at = @now WHERE s.deleted = FALSE;
UPDATE tariff_charge_rules r JOIN tmp_v187_co c ON c.company_id = r.company_id
SET r.deleted = TRUE, r.deleted_at = @now, r.updated_at = @now WHERE r.deleted = FALSE;
UPDATE tariff_rate_cards k JOIN tmp_v187_co c ON c.company_id = k.company_id
SET k.deleted = TRUE, k.deleted_at = @now, k.updated_at = @now, k.status = 'INACTIVE', k.version = k.version + 1
WHERE k.deleted = FALSE;
UPDATE tariff_zone_locations l JOIN tmp_v187_co c ON c.company_id = l.company_id
SET l.deleted = TRUE, l.deleted_at = @now, l.updated_at = @now WHERE l.deleted = FALSE;
UPDATE tariff_zones z JOIN tmp_v187_co c ON c.company_id = z.company_id
SET z.deleted = TRUE, z.deleted_at = @now, z.updated_at = @now, z.status = 'INACTIVE', z.location_count = 0,
    z.version = z.version + 1
WHERE z.deleted = FALSE;

-- 3. Customer pricing ----------------------------------------------------------------------------------------
UPDATE customer_zone_rate_slabs s JOIN tmp_v187_co c ON c.company_id = s.company_id
SET s.deleted = TRUE, s.deleted_at = @now, s.updated_at = @now WHERE s.deleted = FALSE;
UPDATE customer_zone_rates r JOIN tmp_v187_co c ON c.company_id = r.company_id
SET r.deleted = TRUE, r.deleted_at = @now, r.updated_at = @now WHERE r.deleted = FALSE;
UPDATE customer_zone_locations l JOIN tmp_v187_co c ON c.company_id = l.company_id
SET l.deleted = TRUE, l.deleted_at = @now, l.updated_at = @now WHERE l.deleted = FALSE;
UPDATE customer_pricing_zones z JOIN tmp_v187_co c ON c.company_id = z.company_id
SET z.deleted = TRUE, z.deleted_at = @now, z.updated_at = @now WHERE z.deleted = FALSE;
UPDATE customer_structure_rates r JOIN tmp_v187_co c ON c.company_id = r.company_id
SET r.deleted = TRUE, r.deleted_at = @now, r.updated_at = @now WHERE r.deleted = FALSE;

-- 4. Pincodes: no zone, no regional zone ----------------------------------------------------------------------
UPDATE master_pincodes SET zone = NULL, regional_zone_id = NULL, updated_at = @now
WHERE zone IS NOT NULL OR regional_zone_id IS NOT NULL;

-- 5. Regional Zones master (codes and names freed for re-creation) -------------------------------------------
UPDATE master_regional_zones
SET code = CONCAT(LEFT(code, 30), '~V187-', LEFT(HEX(id), 8)), name = CONCAT(LEFT(name, 70), ' ~V187-', LEFT(HEX(id), 8)),
    status = 'INACTIVE', deleted = TRUE, deleted_at = @now, updated_at = @now, version = version + 1
WHERE deleted = FALSE;

DROP TABLE tmp_v187_co;
