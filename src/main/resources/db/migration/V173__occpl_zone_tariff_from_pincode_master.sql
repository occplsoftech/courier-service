-- =============================================================================
-- V173 — OCCPL's Zone Tariff rebuilt from the pincode master (asked by the user on 2026-11-25)
--
-- Companies OCCPL and OCCPL_COURIER only; anywhere else this changes nothing.
--
-- 1. Backup. Every row of the five Zone Tariff tables (all companies) is copied into
--    backup_20261125_tariff_zones, _tariff_zone_locations, _tariff_rate_cards, _tariff_rate_slabs and
--    _tariff_charge_rules, in the same database. To restore a company's tariff, delete its rows from the live
--    tables and INSERT … SELECT them back from these copies (zones, then locations, cards, slabs, charges).
-- 2. Delete. The two companies' Zone Tariff zones, their places, rate cards, slabs and charges are removed for
--    good (the copies above keep them). Business customer pricing zones and District Level Freight are untouched;
--    until new rate cards are entered, bookings that Zone Tariff priced fall back to District Level Freight.
-- 3. Create. One zone per regional zone of the pincode master (V172), used as both From and To:
--    PUNE, X_PUNE, MAHARASHTRA, X_MAHARASHTRA, VIDHARBHA, REST_OF_INDIA, NORTH_EAST.
-- 4. Places. A city goes in as one place when every pincode on file in it (by the pincode's primary area) belongs
--    to the same regional zone; otherwise each of its pincodes with a regional zone goes in on its own. So every
--    pincode of the sheet is covered, and a city split between two regions (Pune vs X - Pune) is split by pincode.
-- =============================================================================

SET @g = UNHEX('00000000000000000000000000000001');

-- 1. Backup ------------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS backup_20261125_tariff_zones LIKE tariff_zones;
CREATE TABLE IF NOT EXISTS backup_20261125_tariff_zone_locations LIKE tariff_zone_locations;
CREATE TABLE IF NOT EXISTS backup_20261125_tariff_rate_cards LIKE tariff_rate_cards;
CREATE TABLE IF NOT EXISTS backup_20261125_tariff_rate_slabs LIKE tariff_rate_slabs;
CREATE TABLE IF NOT EXISTS backup_20261125_tariff_charge_rules LIKE tariff_charge_rules;

INSERT IGNORE INTO backup_20261125_tariff_zones SELECT * FROM tariff_zones;
INSERT IGNORE INTO backup_20261125_tariff_zone_locations SELECT * FROM tariff_zone_locations;
INSERT IGNORE INTO backup_20261125_tariff_rate_cards SELECT * FROM tariff_rate_cards;
INSERT IGNORE INTO backup_20261125_tariff_rate_slabs SELECT * FROM tariff_rate_slabs;
INSERT IGNORE INTO backup_20261125_tariff_charge_rules SELECT * FROM tariff_charge_rules;

DROP TABLE IF EXISTS tmp_occpl_companies;
CREATE TABLE tmp_occpl_companies (company_id BINARY(16) NOT NULL PRIMARY KEY) ENGINE = InnoDB;
INSERT IGNORE INTO tmp_occpl_companies
SELECT company_id FROM companies WHERE company_code IN ('OCCPL', 'OCCPL_COURIER') AND deleted = FALSE;

-- 2. Delete (children first: the foreign keys are ON DELETE RESTRICT) -------------------------------------
DELETE r FROM tariff_charge_rules r JOIN tmp_occpl_companies c ON c.company_id = r.company_id;
DELETE s FROM tariff_rate_slabs s JOIN tmp_occpl_companies c ON c.company_id = s.company_id;
DELETE k FROM tariff_rate_cards k JOIN tmp_occpl_companies c ON c.company_id = k.company_id;
DELETE l FROM tariff_zone_locations l JOIN tmp_occpl_companies c ON c.company_id = l.company_id;
DELETE z FROM tariff_zones z JOIN tmp_occpl_companies c ON c.company_id = z.company_id;

-- 3. Zones: one per regional zone -------------------------------------------------------------------------
INSERT INTO tariff_zones (id, company_id, code, name, zone_usage, description, display_order, status,
                          created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), c.company_id, rz.code, rz.name, 'BOTH',
       'Regional zone of the pincode master (V173)', rz.display_order, 'ACTIVE',
       UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM tmp_occpl_companies c
JOIN master_regional_zones rz ON rz.company_id = @g AND rz.deleted = FALSE AND rz.status = 'ACTIVE';

-- 4. Places -----------------------------------------------------------------------------------------------
-- Every live pincode with its city, district, state and regional zone.
DROP TABLE IF EXISTS tmp_pin_place;
CREATE TABLE tmp_pin_place (
    pincode VARCHAR(10) NOT NULL PRIMARY KEY, city_id BINARY(16) NOT NULL, district_id BINARY(16) NULL,
    state_id BINARY(16) NULL, regional_zone_id BINARY(16) NULL, KEY (city_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
INSERT IGNORE INTO tmp_pin_place (pincode, city_id, district_id, state_id, regional_zone_id)
SELECT p.code, a.city_id, ci.district_id, d.state_id, p.regional_zone_id
FROM master_pincodes p
JOIN master_areas a ON a.id = p.area_id
JOIN master_cities ci ON ci.id = a.city_id
LEFT JOIN master_districts d ON d.id = ci.district_id
WHERE p.company_id = @g AND p.deleted = FALSE AND LENGTH(p.code) = 6;

-- Cities whose pincodes all share one regional zone.
DROP TABLE IF EXISTS tmp_whole_city;
CREATE TABLE tmp_whole_city (city_id BINARY(16) NOT NULL PRIMARY KEY, regional_zone_id BINARY(16) NOT NULL) ENGINE = InnoDB;
INSERT INTO tmp_whole_city (city_id, regional_zone_id)
SELECT city_id, MIN(regional_zone_id)
FROM tmp_pin_place
GROUP BY city_id
HAVING SUM(regional_zone_id IS NULL) = 0 AND COUNT(DISTINCT regional_zone_id) = 1;

INSERT INTO tariff_zone_locations (id, company_id, zone_id, scope, state_id, district_id, city_id, pincode,
                                   created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), z.company_id, z.id, 'CITY', d.state_id, ci.district_id, w.city_id, NULL,
       UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM tmp_whole_city w
JOIN master_cities ci ON ci.id = w.city_id
LEFT JOIN master_districts d ON d.id = ci.district_id
JOIN master_regional_zones rz ON rz.id = w.regional_zone_id
JOIN tariff_zones z ON z.code = rz.code AND z.deleted = FALSE
JOIN tmp_occpl_companies c ON c.company_id = z.company_id;

INSERT INTO tariff_zone_locations (id, company_id, zone_id, scope, state_id, district_id, city_id, pincode,
                                   created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), z.company_id, z.id, 'PINCODE', t.state_id, t.district_id, t.city_id, t.pincode,
       UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM tmp_pin_place t
JOIN master_regional_zones rz ON rz.id = t.regional_zone_id
JOIN tariff_zones z ON z.code = rz.code AND z.deleted = FALSE
JOIN tmp_occpl_companies c ON c.company_id = z.company_id
WHERE t.regional_zone_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM tmp_whole_city w WHERE w.city_id = t.city_id);

DROP TABLE tmp_whole_city;
DROP TABLE tmp_pin_place;
DROP TABLE tmp_occpl_companies;
