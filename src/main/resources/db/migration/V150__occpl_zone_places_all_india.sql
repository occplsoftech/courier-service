-- =============================================================================
-- V150 — OCCPL's tariff zones: the places V142 / V143 could not add before the rest of India's
-- geography existed (V149).
--
-- V142 (company OCCPL_COURIER) and V143 (company OCCPL) put each zone's states and cities in by name,
-- and skipped every place missing from the global masters: then only Maharashtra was loaded, so
-- NORTH, SOUTH, KERALA, EAST, WEST, CENTRAL and NORTH_EAST came out empty and METRO held Mumbai only.
-- Pune bookings to those states found no zone. This adds:
--   * each of those zones' states (also under older names: Orissa, Pondicherry, Chattisgarh…);
--   * METRO's metro areas as whole districts: every district of Delhi, Mumbai, Kolkata, Chennai,
--     Bangalore and Hyderabad. The directory files a metro city's pincodes under many talukas
--     (Chennai's under "Egmore Nungambakkam", "Chennai City Corporation"…), so city places would miss
--     most of them; a district is more specific than the state, so METRO wins over SOUTH, EAST, NORTH.
--
-- Only companies OCCPL and OCCPL_COURIER, only zones with these codes that are live and still take
-- deliveries (TO / BOTH). A place is skipped when any live TO / BOTH zone of the company already holds
-- it at the same scope (the one-zone-per-place rule), so a zone someone has since edited keeps its
-- places, and running this twice adds nothing.
-- =============================================================================

SET @g = UNHEX('00000000000000000000000000000001');

-- NORTH: states
INSERT INTO tariff_zone_locations (id, company_id, zone_id, scope, state_id, district_id, city_id, pincode, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), z.company_id, z.id, 'STATE', s.id, NULL, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM tariff_zones z
JOIN companies co ON co.company_id = z.company_id AND co.company_code IN ('OCCPL', 'OCCPL_COURIER') AND co.deleted = FALSE
JOIN master_states s
WHERE z.code = 'NORTH' AND z.deleted = FALSE AND z.zone_usage IN ('TO', 'BOTH')
  AND s.company_id = @g AND s.deleted = FALSE AND UPPER(REPLACE(s.name, '&', 'AND')) IN ('DELHI', 'HARYANA', 'PUNJAB', 'UTTAR PRADESH', 'UTTARAKHAND', 'UTTARANCHAL', 'HIMACHAL PRADESH', 'JAMMU AND KASHMIR', 'LADAKH', 'RAJASTHAN', 'CHANDIGARH')
  AND NOT EXISTS (SELECT 1 FROM tariff_zone_locations l JOIN tariff_zones oz ON oz.id = l.zone_id AND oz.deleted = FALSE AND oz.zone_usage IN ('TO', 'BOTH')
                  WHERE l.company_id = z.company_id AND l.deleted = FALSE AND l.scope = 'STATE' AND l.state_id = s.id);

-- SOUTH: states
INSERT INTO tariff_zone_locations (id, company_id, zone_id, scope, state_id, district_id, city_id, pincode, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), z.company_id, z.id, 'STATE', s.id, NULL, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM tariff_zones z
JOIN companies co ON co.company_id = z.company_id AND co.company_code IN ('OCCPL', 'OCCPL_COURIER') AND co.deleted = FALSE
JOIN master_states s
WHERE z.code = 'SOUTH' AND z.deleted = FALSE AND z.zone_usage IN ('TO', 'BOTH')
  AND s.company_id = @g AND s.deleted = FALSE AND UPPER(REPLACE(s.name, '&', 'AND')) IN ('KARNATAKA', 'TAMIL NADU', 'ANDHRA PRADESH', 'TELANGANA', 'PUDUCHERRY', 'PONDICHERRY')
  AND NOT EXISTS (SELECT 1 FROM tariff_zone_locations l JOIN tariff_zones oz ON oz.id = l.zone_id AND oz.deleted = FALSE AND oz.zone_usage IN ('TO', 'BOTH')
                  WHERE l.company_id = z.company_id AND l.deleted = FALSE AND l.scope = 'STATE' AND l.state_id = s.id);

-- KERALA: states
INSERT INTO tariff_zone_locations (id, company_id, zone_id, scope, state_id, district_id, city_id, pincode, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), z.company_id, z.id, 'STATE', s.id, NULL, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM tariff_zones z
JOIN companies co ON co.company_id = z.company_id AND co.company_code IN ('OCCPL', 'OCCPL_COURIER') AND co.deleted = FALSE
JOIN master_states s
WHERE z.code = 'KERALA' AND z.deleted = FALSE AND z.zone_usage IN ('TO', 'BOTH')
  AND s.company_id = @g AND s.deleted = FALSE AND UPPER(REPLACE(s.name, '&', 'AND')) IN ('KERALA')
  AND NOT EXISTS (SELECT 1 FROM tariff_zone_locations l JOIN tariff_zones oz ON oz.id = l.zone_id AND oz.deleted = FALSE AND oz.zone_usage IN ('TO', 'BOTH')
                  WHERE l.company_id = z.company_id AND l.deleted = FALSE AND l.scope = 'STATE' AND l.state_id = s.id);

-- EAST: states
INSERT INTO tariff_zone_locations (id, company_id, zone_id, scope, state_id, district_id, city_id, pincode, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), z.company_id, z.id, 'STATE', s.id, NULL, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM tariff_zones z
JOIN companies co ON co.company_id = z.company_id AND co.company_code IN ('OCCPL', 'OCCPL_COURIER') AND co.deleted = FALSE
JOIN master_states s
WHERE z.code = 'EAST' AND z.deleted = FALSE AND z.zone_usage IN ('TO', 'BOTH')
  AND s.company_id = @g AND s.deleted = FALSE AND UPPER(REPLACE(s.name, '&', 'AND')) IN ('WEST BENGAL', 'ODISHA', 'ORISSA', 'BIHAR', 'JHARKHAND')
  AND NOT EXISTS (SELECT 1 FROM tariff_zone_locations l JOIN tariff_zones oz ON oz.id = l.zone_id AND oz.deleted = FALSE AND oz.zone_usage IN ('TO', 'BOTH')
                  WHERE l.company_id = z.company_id AND l.deleted = FALSE AND l.scope = 'STATE' AND l.state_id = s.id);

-- WEST: states
INSERT INTO tariff_zone_locations (id, company_id, zone_id, scope, state_id, district_id, city_id, pincode, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), z.company_id, z.id, 'STATE', s.id, NULL, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM tariff_zones z
JOIN companies co ON co.company_id = z.company_id AND co.company_code IN ('OCCPL', 'OCCPL_COURIER') AND co.deleted = FALSE
JOIN master_states s
WHERE z.code = 'WEST' AND z.deleted = FALSE AND z.zone_usage IN ('TO', 'BOTH')
  AND s.company_id = @g AND s.deleted = FALSE AND UPPER(REPLACE(s.name, '&', 'AND')) IN ('GUJARAT', 'GOA', 'DAMAN AND DIU')
  AND NOT EXISTS (SELECT 1 FROM tariff_zone_locations l JOIN tariff_zones oz ON oz.id = l.zone_id AND oz.deleted = FALSE AND oz.zone_usage IN ('TO', 'BOTH')
                  WHERE l.company_id = z.company_id AND l.deleted = FALSE AND l.scope = 'STATE' AND l.state_id = s.id);

-- CENTRAL: states
INSERT INTO tariff_zone_locations (id, company_id, zone_id, scope, state_id, district_id, city_id, pincode, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), z.company_id, z.id, 'STATE', s.id, NULL, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM tariff_zones z
JOIN companies co ON co.company_id = z.company_id AND co.company_code IN ('OCCPL', 'OCCPL_COURIER') AND co.deleted = FALSE
JOIN master_states s
WHERE z.code = 'CENTRAL' AND z.deleted = FALSE AND z.zone_usage IN ('TO', 'BOTH')
  AND s.company_id = @g AND s.deleted = FALSE AND UPPER(REPLACE(s.name, '&', 'AND')) IN ('MADHYA PRADESH', 'CHHATTISGARH', 'CHATTISGARH')
  AND NOT EXISTS (SELECT 1 FROM tariff_zone_locations l JOIN tariff_zones oz ON oz.id = l.zone_id AND oz.deleted = FALSE AND oz.zone_usage IN ('TO', 'BOTH')
                  WHERE l.company_id = z.company_id AND l.deleted = FALSE AND l.scope = 'STATE' AND l.state_id = s.id);

-- NORTH_EAST: states
INSERT INTO tariff_zone_locations (id, company_id, zone_id, scope, state_id, district_id, city_id, pincode, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), z.company_id, z.id, 'STATE', s.id, NULL, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM tariff_zones z
JOIN companies co ON co.company_id = z.company_id AND co.company_code IN ('OCCPL', 'OCCPL_COURIER') AND co.deleted = FALSE
JOIN master_states s
WHERE z.code = 'NORTH_EAST' AND z.deleted = FALSE AND z.zone_usage IN ('TO', 'BOTH')
  AND s.company_id = @g AND s.deleted = FALSE AND UPPER(REPLACE(s.name, '&', 'AND')) IN ('ASSAM', 'MEGHALAYA', 'MANIPUR', 'MIZORAM', 'NAGALAND', 'TRIPURA', 'ARUNACHAL PRADESH', 'SIKKIM')
  AND NOT EXISTS (SELECT 1 FROM tariff_zone_locations l JOIN tariff_zones oz ON oz.id = l.zone_id AND oz.deleted = FALSE AND oz.zone_usage IN ('TO', 'BOTH')
                  WHERE l.company_id = z.company_id AND l.deleted = FALSE AND l.scope = 'STATE' AND l.state_id = s.id);

-- METRO: Delhi — every district
INSERT INTO tariff_zone_locations (id, company_id, zone_id, scope, state_id, district_id, city_id, pincode, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), z.company_id, z.id, 'DISTRICT', s.id, d.id, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM tariff_zones z
JOIN companies co ON co.company_id = z.company_id AND co.company_code IN ('OCCPL', 'OCCPL_COURIER') AND co.deleted = FALSE
JOIN master_districts d JOIN master_states s ON s.id = d.state_id AND s.deleted = FALSE
WHERE z.code = 'METRO' AND z.deleted = FALSE AND z.zone_usage IN ('TO', 'BOTH')
  AND d.company_id = @g AND d.deleted = FALSE AND UPPER(REPLACE(s.name, '&', 'AND')) IN ('DELHI')
  AND NOT EXISTS (SELECT 1 FROM tariff_zone_locations l JOIN tariff_zones oz ON oz.id = l.zone_id AND oz.deleted = FALSE AND oz.zone_usage IN ('TO', 'BOTH')
                  WHERE l.company_id = z.company_id AND l.deleted = FALSE AND l.scope = 'DISTRICT' AND l.district_id = d.id);

-- METRO: Maharashtra — Mumbai, Mumbai Suburban
INSERT INTO tariff_zone_locations (id, company_id, zone_id, scope, state_id, district_id, city_id, pincode, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), z.company_id, z.id, 'DISTRICT', s.id, d.id, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM tariff_zones z
JOIN companies co ON co.company_id = z.company_id AND co.company_code IN ('OCCPL', 'OCCPL_COURIER') AND co.deleted = FALSE
JOIN master_districts d JOIN master_states s ON s.id = d.state_id AND s.deleted = FALSE
WHERE z.code = 'METRO' AND z.deleted = FALSE AND z.zone_usage IN ('TO', 'BOTH')
  AND d.company_id = @g AND d.deleted = FALSE AND UPPER(REPLACE(s.name, '&', 'AND')) IN ('MAHARASHTRA') AND d.name IN ('Mumbai', 'Mumbai Suburban')
  AND NOT EXISTS (SELECT 1 FROM tariff_zone_locations l JOIN tariff_zones oz ON oz.id = l.zone_id AND oz.deleted = FALSE AND oz.zone_usage IN ('TO', 'BOTH')
                  WHERE l.company_id = z.company_id AND l.deleted = FALSE AND l.scope = 'DISTRICT' AND l.district_id = d.id);

-- METRO: West Bengal — Kolkata
INSERT INTO tariff_zone_locations (id, company_id, zone_id, scope, state_id, district_id, city_id, pincode, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), z.company_id, z.id, 'DISTRICT', s.id, d.id, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM tariff_zones z
JOIN companies co ON co.company_id = z.company_id AND co.company_code IN ('OCCPL', 'OCCPL_COURIER') AND co.deleted = FALSE
JOIN master_districts d JOIN master_states s ON s.id = d.state_id AND s.deleted = FALSE
WHERE z.code = 'METRO' AND z.deleted = FALSE AND z.zone_usage IN ('TO', 'BOTH')
  AND d.company_id = @g AND d.deleted = FALSE AND UPPER(REPLACE(s.name, '&', 'AND')) IN ('WEST BENGAL') AND d.name IN ('Kolkata')
  AND NOT EXISTS (SELECT 1 FROM tariff_zone_locations l JOIN tariff_zones oz ON oz.id = l.zone_id AND oz.deleted = FALSE AND oz.zone_usage IN ('TO', 'BOTH')
                  WHERE l.company_id = z.company_id AND l.deleted = FALSE AND l.scope = 'DISTRICT' AND l.district_id = d.id);

-- METRO: Tamil Nadu — Chennai
INSERT INTO tariff_zone_locations (id, company_id, zone_id, scope, state_id, district_id, city_id, pincode, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), z.company_id, z.id, 'DISTRICT', s.id, d.id, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM tariff_zones z
JOIN companies co ON co.company_id = z.company_id AND co.company_code IN ('OCCPL', 'OCCPL_COURIER') AND co.deleted = FALSE
JOIN master_districts d JOIN master_states s ON s.id = d.state_id AND s.deleted = FALSE
WHERE z.code = 'METRO' AND z.deleted = FALSE AND z.zone_usage IN ('TO', 'BOTH')
  AND d.company_id = @g AND d.deleted = FALSE AND UPPER(REPLACE(s.name, '&', 'AND')) IN ('TAMIL NADU') AND d.name IN ('Chennai')
  AND NOT EXISTS (SELECT 1 FROM tariff_zone_locations l JOIN tariff_zones oz ON oz.id = l.zone_id AND oz.deleted = FALSE AND oz.zone_usage IN ('TO', 'BOTH')
                  WHERE l.company_id = z.company_id AND l.deleted = FALSE AND l.scope = 'DISTRICT' AND l.district_id = d.id);

-- METRO: Karnataka — Bangalore, Bengaluru, Bengaluru Urban, Bangalore Urban
INSERT INTO tariff_zone_locations (id, company_id, zone_id, scope, state_id, district_id, city_id, pincode, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), z.company_id, z.id, 'DISTRICT', s.id, d.id, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM tariff_zones z
JOIN companies co ON co.company_id = z.company_id AND co.company_code IN ('OCCPL', 'OCCPL_COURIER') AND co.deleted = FALSE
JOIN master_districts d JOIN master_states s ON s.id = d.state_id AND s.deleted = FALSE
WHERE z.code = 'METRO' AND z.deleted = FALSE AND z.zone_usage IN ('TO', 'BOTH')
  AND d.company_id = @g AND d.deleted = FALSE AND UPPER(REPLACE(s.name, '&', 'AND')) IN ('KARNATAKA') AND d.name IN ('Bangalore', 'Bengaluru', 'Bengaluru Urban', 'Bangalore Urban')
  AND NOT EXISTS (SELECT 1 FROM tariff_zone_locations l JOIN tariff_zones oz ON oz.id = l.zone_id AND oz.deleted = FALSE AND oz.zone_usage IN ('TO', 'BOTH')
                  WHERE l.company_id = z.company_id AND l.deleted = FALSE AND l.scope = 'DISTRICT' AND l.district_id = d.id);

-- METRO: Telangana — Hyderabad
INSERT INTO tariff_zone_locations (id, company_id, zone_id, scope, state_id, district_id, city_id, pincode, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), z.company_id, z.id, 'DISTRICT', s.id, d.id, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM tariff_zones z
JOIN companies co ON co.company_id = z.company_id AND co.company_code IN ('OCCPL', 'OCCPL_COURIER') AND co.deleted = FALSE
JOIN master_districts d JOIN master_states s ON s.id = d.state_id AND s.deleted = FALSE
WHERE z.code = 'METRO' AND z.deleted = FALSE AND z.zone_usage IN ('TO', 'BOTH')
  AND d.company_id = @g AND d.deleted = FALSE AND UPPER(REPLACE(s.name, '&', 'AND')) IN ('TELANGANA') AND d.name IN ('Hyderabad')
  AND NOT EXISTS (SELECT 1 FROM tariff_zone_locations l JOIN tariff_zones oz ON oz.id = l.zone_id AND oz.deleted = FALSE AND oz.zone_usage IN ('TO', 'BOTH')
                  WHERE l.company_id = z.company_id AND l.deleted = FALSE AND l.scope = 'DISTRICT' AND l.district_id = d.id);
