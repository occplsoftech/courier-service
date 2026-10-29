-- =============================================================================
-- V142 — OCCPL Courier's zone tariff: zones, the Pune cash-booking rate card and demo charges
--
-- Applies only to the company with company_code 'OCCPL_COURIER': nowhere else does it change anything,
-- and where that company is missing it does nothing. Skipped if the rate card PUNE_CASH_2025 already
-- exists, so it is safe to run twice. Generated: zones and their places, the lanes of
-- CASH_BOOKING_RATES_2.xlsx (74 lanes) and four demo charges.
--
-- Places are looked up by name in the global masters and any that are not there are skipped
-- (only Maharashtra's geography is loaded in full), so a zone can come out with fewer places than
-- listed; add the rest from Zone Tariff -> Zones. EXMAHARASHTRA has no places by design.
-- Rates: Fixed is the freight; Per Kg and "Addition 500GM" are per extra kg (step 1 kg). The
-- sheet's 16 Kerala rows without a State (rows 499-500, 989-1002 of the sheet) are in KERALA.
-- The charges (fuel 10%, docket 30, hamali 2/kg, COD 2%) are DEMO values: edit on the rate card.
-- =============================================================================

SET @co = (SELECT company_id FROM companies WHERE company_code = 'OCCPL_COURIER' AND deleted = FALSE LIMIT 1);
SET @g = UNHEX('00000000000000000000000000000001');
SET @go = @co IS NOT NULL AND NOT EXISTS (SELECT 1 FROM tariff_rate_cards WHERE company_id = @co AND code = 'PUNE_CASH_2025');

SET @z_PUNE = UNHEX(REPLACE(UUID(), '-', ''));
SET @z_EXPUNE = UNHEX(REPLACE(UUID(), '-', ''));
SET @z_MAHARASHTRA = UNHEX(REPLACE(UUID(), '-', ''));
SET @z_EXMAHARASHTRA = UNHEX(REPLACE(UUID(), '-', ''));
SET @z_VIDHARBHA = UNHEX(REPLACE(UUID(), '-', ''));
SET @z_METRO = UNHEX(REPLACE(UUID(), '-', ''));
SET @z_NORTH = UNHEX(REPLACE(UUID(), '-', ''));
SET @z_SOUTH = UNHEX(REPLACE(UUID(), '-', ''));
SET @z_KERALA = UNHEX(REPLACE(UUID(), '-', ''));
SET @z_EAST = UNHEX(REPLACE(UUID(), '-', ''));
SET @z_WEST = UNHEX(REPLACE(UUID(), '-', ''));
SET @z_CENTRAL = UNHEX(REPLACE(UUID(), '-', ''));
SET @z_NORTH_EAST = UNHEX(REPLACE(UUID(), '-', ''));

INSERT INTO master_service_types (id, company_id, code, name, status, display_order, delivery_days, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, 'AIR', 'By Air', 'ACTIVE', 0, 2, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM DUAL WHERE @go AND NOT EXISTS (SELECT 1 FROM master_service_types WHERE company_id = @co AND code = 'AIR');
SET @st_AIR = (SELECT id FROM master_service_types WHERE company_id = @co AND code = 'AIR' AND deleted = FALSE LIMIT 1);
INSERT INTO master_service_types (id, company_id, code, name, status, display_order, delivery_days, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, 'SURFACE', 'Surface', 'ACTIVE', 0, 5, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM DUAL WHERE @go AND NOT EXISTS (SELECT 1 FROM master_service_types WHERE company_id = @co AND code = 'SURFACE');
SET @st_SURFACE = (SELECT id FROM master_service_types WHERE company_id = @co AND code = 'SURFACE' AND deleted = FALSE LIMIT 1);
INSERT INTO master_service_types (id, company_id, code, name, status, display_order, delivery_days, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, 'PREMIUM', 'Premium', 'ACTIVE', 0, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM DUAL WHERE @go AND NOT EXISTS (SELECT 1 FROM master_service_types WHERE company_id = @co AND code = 'PREMIUM');
SET @st_PREMIUM = (SELECT id FROM master_service_types WHERE company_id = @co AND code = 'PREMIUM' AND deleted = FALSE LIMIT 1);
INSERT INTO master_service_types (id, company_id, code, name, status, display_order, delivery_days, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, 'CARGO', 'Cargo', 'ACTIVE', 0, 6, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM DUAL WHERE @go AND NOT EXISTS (SELECT 1 FROM master_service_types WHERE company_id = @co AND code = 'CARGO');
SET @st_CARGO = (SELECT id FROM master_service_types WHERE company_id = @co AND code = 'CARGO' AND deleted = FALSE LIMIT 1);

-- Documents: the company's document package type if it has one, else DOC. Everything else: NONDOC, NON_DOCUMENT or PARCEL, else NONDOC.
INSERT INTO master_package_types (id, company_id, code, name, status, display_order, is_document, fragile_by_default, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, 'DOC', 'Document', 'ACTIVE', 0, TRUE, FALSE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM DUAL WHERE @go AND NOT EXISTS (SELECT 1 FROM master_package_types WHERE company_id = @co AND is_document = TRUE AND deleted = FALSE);
SET @pk_DOC = (SELECT id FROM master_package_types WHERE company_id = @co AND is_document = TRUE AND deleted = FALSE ORDER BY (code IN ('DOC', 'DOCUMENT')) DESC, created_at LIMIT 1);
INSERT INTO master_package_types (id, company_id, code, name, status, display_order, is_document, fragile_by_default, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, 'NONDOC', 'Non Document', 'ACTIVE', 0, FALSE, FALSE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM DUAL WHERE @go AND NOT EXISTS (SELECT 1 FROM master_package_types WHERE company_id = @co AND code IN ('NONDOC', 'NON_DOCUMENT', 'PARCEL') AND deleted = FALSE);
SET @pk_NONDOC = (SELECT id FROM master_package_types WHERE company_id = @co AND code IN ('NONDOC', 'NON_DOCUMENT', 'PARCEL') AND deleted = FALSE ORDER BY FIELD(code, 'NONDOC', 'NON_DOCUMENT', 'PARCEL') LIMIT 1);

-- PUNE
INSERT INTO tariff_zones (id, company_id, code, name, zone_usage, description, display_order, status, created_at, updated_at, deleted, version)
SELECT @z_PUNE, @co, 'PUNE', 'Pune city', 'BOTH', NULL, 1, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0 FROM DUAL WHERE @go;
INSERT INTO tariff_zone_locations (id, company_id, zone_id, scope, state_id, district_id, city_id, pincode, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @z_PUNE, 'CITY', s.id, d.id, c.id, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM master_cities c JOIN master_districts d ON d.id = c.district_id JOIN master_states s ON s.id = d.state_id
WHERE @go AND c.company_id = @g AND c.deleted = FALSE AND d.deleted = FALSE AND s.deleted = FALSE AND UPPER(REPLACE(s.name, '&', 'AND')) IN ('MAHARASHTRA') AND d.name = 'Pune' AND (c.name LIKE 'Pune City%' OR c.name = 'Pune');
-- EXPUNE
INSERT INTO tariff_zones (id, company_id, code, name, zone_usage, description, display_order, status, created_at, updated_at, deleted, version)
SELECT @z_EXPUNE, @co, 'EXPUNE', 'Pune district outside the city', 'TO', NULL, 2, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0 FROM DUAL WHERE @go;
INSERT INTO tariff_zone_locations (id, company_id, zone_id, scope, state_id, district_id, city_id, pincode, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @z_EXPUNE, 'DISTRICT', s.id, d.id, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM master_districts d JOIN master_states s ON s.id = d.state_id
WHERE @go AND d.company_id = @g AND d.deleted = FALSE AND s.deleted = FALSE AND UPPER(REPLACE(s.name, '&', 'AND')) IN ('MAHARASHTRA') AND d.name IN ('Pune');
-- MAHARASHTRA
INSERT INTO tariff_zones (id, company_id, code, name, zone_usage, description, display_order, status, created_at, updated_at, deleted, version)
SELECT @z_MAHARASHTRA, @co, 'MAHARASHTRA', 'Rest of Maharashtra', 'TO', NULL, 3, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0 FROM DUAL WHERE @go;
INSERT INTO tariff_zone_locations (id, company_id, zone_id, scope, state_id, district_id, city_id, pincode, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @z_MAHARASHTRA, 'STATE', s.id, NULL, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM master_states s WHERE @go AND s.company_id = @g AND s.deleted = FALSE AND UPPER(REPLACE(s.name, '&', 'AND')) IN ('MAHARASHTRA');
-- EXMAHARASHTRA
INSERT INTO tariff_zones (id, company_id, code, name, zone_usage, description, display_order, status, created_at, updated_at, deleted, version)
SELECT @z_EXMAHARASHTRA, @co, 'EXMAHARASHTRA', 'Outside Maharashtra', 'TO', NULL, 4, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0 FROM DUAL WHERE @go;
-- VIDHARBHA
INSERT INTO tariff_zones (id, company_id, code, name, zone_usage, description, display_order, status, created_at, updated_at, deleted, version)
SELECT @z_VIDHARBHA, @co, 'VIDHARBHA', 'Vidarbha', 'TO', NULL, 5, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0 FROM DUAL WHERE @go;
INSERT INTO tariff_zone_locations (id, company_id, zone_id, scope, state_id, district_id, city_id, pincode, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @z_VIDHARBHA, 'DISTRICT', s.id, d.id, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM master_districts d JOIN master_states s ON s.id = d.state_id
WHERE @go AND d.company_id = @g AND d.deleted = FALSE AND s.deleted = FALSE AND UPPER(REPLACE(s.name, '&', 'AND')) IN ('MAHARASHTRA') AND d.name IN ('Nagpur', 'Wardha', 'Amravati', 'Akola', 'Yavatmal', 'Chandrapur', 'Gadchiroli', 'Bhandara', 'Gondia', 'Washim', 'Buldhana');
-- METRO
INSERT INTO tariff_zones (id, company_id, code, name, zone_usage, description, display_order, status, created_at, updated_at, deleted, version)
SELECT @z_METRO, @co, 'METRO', 'Metro cities', 'TO', NULL, 6, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0 FROM DUAL WHERE @go;
INSERT INTO tariff_zone_locations (id, company_id, zone_id, scope, state_id, district_id, city_id, pincode, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @z_METRO, 'CITY', s.id, d.id, c.id, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM master_cities c JOIN master_districts d ON d.id = c.district_id JOIN master_states s ON s.id = d.state_id
WHERE @go AND c.company_id = @g AND c.deleted = FALSE AND d.deleted = FALSE AND s.deleted = FALSE AND UPPER(REPLACE(s.name, '&', 'AND')) IN ('MAHARASHTRA') AND (c.name IN ('Mumbai'));
INSERT INTO tariff_zone_locations (id, company_id, zone_id, scope, state_id, district_id, city_id, pincode, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @z_METRO, 'CITY', s.id, d.id, c.id, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM master_cities c JOIN master_districts d ON d.id = c.district_id JOIN master_states s ON s.id = d.state_id
WHERE @go AND c.company_id = @g AND c.deleted = FALSE AND d.deleted = FALSE AND s.deleted = FALSE AND UPPER(REPLACE(s.name, '&', 'AND')) IN ('DELHI') AND (c.name IN ('Delhi', 'New Delhi'));
INSERT INTO tariff_zone_locations (id, company_id, zone_id, scope, state_id, district_id, city_id, pincode, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @z_METRO, 'CITY', s.id, d.id, c.id, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM master_cities c JOIN master_districts d ON d.id = c.district_id JOIN master_states s ON s.id = d.state_id
WHERE @go AND c.company_id = @g AND c.deleted = FALSE AND d.deleted = FALSE AND s.deleted = FALSE AND UPPER(REPLACE(s.name, '&', 'AND')) IN ('WEST BENGAL') AND (c.name IN ('Kolkata'));
INSERT INTO tariff_zone_locations (id, company_id, zone_id, scope, state_id, district_id, city_id, pincode, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @z_METRO, 'CITY', s.id, d.id, c.id, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM master_cities c JOIN master_districts d ON d.id = c.district_id JOIN master_states s ON s.id = d.state_id
WHERE @go AND c.company_id = @g AND c.deleted = FALSE AND d.deleted = FALSE AND s.deleted = FALSE AND UPPER(REPLACE(s.name, '&', 'AND')) IN ('TAMIL NADU') AND (c.name IN ('Chennai'));
INSERT INTO tariff_zone_locations (id, company_id, zone_id, scope, state_id, district_id, city_id, pincode, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @z_METRO, 'CITY', s.id, d.id, c.id, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM master_cities c JOIN master_districts d ON d.id = c.district_id JOIN master_states s ON s.id = d.state_id
WHERE @go AND c.company_id = @g AND c.deleted = FALSE AND d.deleted = FALSE AND s.deleted = FALSE AND UPPER(REPLACE(s.name, '&', 'AND')) IN ('KARNATAKA') AND (c.name IN ('Bengaluru', 'Bangalore'));
INSERT INTO tariff_zone_locations (id, company_id, zone_id, scope, state_id, district_id, city_id, pincode, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @z_METRO, 'CITY', s.id, d.id, c.id, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM master_cities c JOIN master_districts d ON d.id = c.district_id JOIN master_states s ON s.id = d.state_id
WHERE @go AND c.company_id = @g AND c.deleted = FALSE AND d.deleted = FALSE AND s.deleted = FALSE AND UPPER(REPLACE(s.name, '&', 'AND')) IN ('TELANGANA') AND (c.name IN ('Hyderabad'));
-- NORTH
INSERT INTO tariff_zones (id, company_id, code, name, zone_usage, description, display_order, status, created_at, updated_at, deleted, version)
SELECT @z_NORTH, @co, 'NORTH', 'North India', 'TO', NULL, 7, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0 FROM DUAL WHERE @go;
INSERT INTO tariff_zone_locations (id, company_id, zone_id, scope, state_id, district_id, city_id, pincode, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @z_NORTH, 'STATE', s.id, NULL, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM master_states s WHERE @go AND s.company_id = @g AND s.deleted = FALSE AND UPPER(REPLACE(s.name, '&', 'AND')) IN ('DELHI', 'HARYANA', 'PUNJAB', 'UTTAR PRADESH', 'UTTARAKHAND', 'HIMACHAL PRADESH', 'JAMMU AND KASHMIR', 'RAJASTHAN', 'CHANDIGARH');
-- SOUTH
INSERT INTO tariff_zones (id, company_id, code, name, zone_usage, description, display_order, status, created_at, updated_at, deleted, version)
SELECT @z_SOUTH, @co, 'SOUTH', 'South India', 'TO', NULL, 8, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0 FROM DUAL WHERE @go;
INSERT INTO tariff_zone_locations (id, company_id, zone_id, scope, state_id, district_id, city_id, pincode, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @z_SOUTH, 'STATE', s.id, NULL, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM master_states s WHERE @go AND s.company_id = @g AND s.deleted = FALSE AND UPPER(REPLACE(s.name, '&', 'AND')) IN ('KARNATAKA', 'TAMIL NADU', 'ANDHRA PRADESH', 'TELANGANA', 'PUDUCHERRY');
-- KERALA
INSERT INTO tariff_zones (id, company_id, code, name, zone_usage, description, display_order, status, created_at, updated_at, deleted, version)
SELECT @z_KERALA, @co, 'KERALA', 'Kerala', 'TO', NULL, 9, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0 FROM DUAL WHERE @go;
INSERT INTO tariff_zone_locations (id, company_id, zone_id, scope, state_id, district_id, city_id, pincode, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @z_KERALA, 'STATE', s.id, NULL, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM master_states s WHERE @go AND s.company_id = @g AND s.deleted = FALSE AND UPPER(REPLACE(s.name, '&', 'AND')) IN ('KERALA');
-- EAST
INSERT INTO tariff_zones (id, company_id, code, name, zone_usage, description, display_order, status, created_at, updated_at, deleted, version)
SELECT @z_EAST, @co, 'EAST', 'East India', 'TO', NULL, 10, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0 FROM DUAL WHERE @go;
INSERT INTO tariff_zone_locations (id, company_id, zone_id, scope, state_id, district_id, city_id, pincode, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @z_EAST, 'STATE', s.id, NULL, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM master_states s WHERE @go AND s.company_id = @g AND s.deleted = FALSE AND UPPER(REPLACE(s.name, '&', 'AND')) IN ('WEST BENGAL', 'ODISHA', 'BIHAR', 'JHARKHAND');
-- WEST
INSERT INTO tariff_zones (id, company_id, code, name, zone_usage, description, display_order, status, created_at, updated_at, deleted, version)
SELECT @z_WEST, @co, 'WEST', 'West India', 'TO', NULL, 11, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0 FROM DUAL WHERE @go;
INSERT INTO tariff_zone_locations (id, company_id, zone_id, scope, state_id, district_id, city_id, pincode, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @z_WEST, 'STATE', s.id, NULL, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM master_states s WHERE @go AND s.company_id = @g AND s.deleted = FALSE AND UPPER(REPLACE(s.name, '&', 'AND')) IN ('GUJARAT', 'GOA', 'DAMAN AND DIU');
-- CENTRAL
INSERT INTO tariff_zones (id, company_id, code, name, zone_usage, description, display_order, status, created_at, updated_at, deleted, version)
SELECT @z_CENTRAL, @co, 'CENTRAL', 'Central India', 'TO', NULL, 12, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0 FROM DUAL WHERE @go;
INSERT INTO tariff_zone_locations (id, company_id, zone_id, scope, state_id, district_id, city_id, pincode, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @z_CENTRAL, 'STATE', s.id, NULL, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM master_states s WHERE @go AND s.company_id = @g AND s.deleted = FALSE AND UPPER(REPLACE(s.name, '&', 'AND')) IN ('MADHYA PRADESH', 'CHHATTISGARH');
-- NORTH_EAST
INSERT INTO tariff_zones (id, company_id, code, name, zone_usage, description, display_order, status, created_at, updated_at, deleted, version)
SELECT @z_NORTH_EAST, @co, 'NORTH_EAST', 'North East India', 'TO', NULL, 13, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0 FROM DUAL WHERE @go;
INSERT INTO tariff_zone_locations (id, company_id, zone_id, scope, state_id, district_id, city_id, pincode, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @z_NORTH_EAST, 'STATE', s.id, NULL, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM master_states s WHERE @go AND s.company_id = @g AND s.deleted = FALSE AND UPPER(REPLACE(s.name, '&', 'AND')) IN ('ASSAM', 'MEGHALAYA', 'MANIPUR', 'MIZORAM', 'NAGALAND', 'TRIPURA', 'ARUNACHAL PRADESH', 'SIKKIM');

-- The rate card
SET @card = UNHEX(REPLACE(UUID(), '-', ''));
INSERT INTO tariff_rate_cards (id, company_id, code, name, from_zone_id, effective_from, effective_to, gst_percent, volumetric_divisor, total_rounding, description, status, created_at, updated_at, deleted, version)
SELECT @card, @co, 'PUNE_CASH_2025', 'Pune cash booking', @z_PUNE, '2025-04-01', NULL, 18.00, NULL, 'NEAREST_RUPEE', 'CASH_BOOKING_RATES_2.xlsx', 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0 FROM DUAL WHERE @go;

INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_PUNE, @st_SURFACE, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.25 t, 'FIXED' rt, 50 r, NULL step UNION ALL SELECT 0.251 f, 0.5 t, 'FIXED' rt, 50 r, NULL step UNION ALL SELECT 0.501 f, 1 t, 'FIXED' rt, 50 r, NULL step UNION ALL SELECT 1.001 f, 99 t, 'PER_EXTRA_KG' rt, 40 r, 1 step) v WHERE @go;  -- PUNE · SURFACE · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_EXPUNE, @st_SURFACE, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.25 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 0.251 f, 0.5 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 0.501 f, 1 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 1.001 f, 99 t, 'PER_EXTRA_KG' rt, 60 r, 1 step) v WHERE @go;  -- EXPUNE · SURFACE · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_MAHARASHTRA, @st_SURFACE, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.25 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 0.251 f, 0.5 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 0.501 f, 1 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 1.001 f, 99 t, 'PER_EXTRA_KG' rt, 60 r, 1 step) v WHERE @go;  -- MAHARASHTRA · SURFACE · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_EXMAHARASHTRA, @st_SURFACE, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.25 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 0.251 f, 0.5 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 0.501 f, 1 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 1.001 f, 99 t, 'PER_EXTRA_KG' rt, 60 r, 1 step) v WHERE @go;  -- EXMAHARASHTRA · SURFACE · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_VIDHARBHA, @st_SURFACE, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.25 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 0.251 f, 0.5 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 0.501 f, 1 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 1.001 f, 999 t, 'PER_EXTRA_KG' rt, 60 r, 1 step) v WHERE @go;  -- VIDHARBHA · SURFACE · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_VIDHARBHA, @st_AIR, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.25 t, 'FIXED' rt, 150 r, NULL step UNION ALL SELECT 0.251 f, 0.5 t, 'FIXED' rt, 150 r, NULL step UNION ALL SELECT 0.501 f, 1 t, 'FIXED' rt, 150 r, NULL step UNION ALL SELECT 1.001 f, 999 t, 'PER_EXTRA_KG' rt, 150 r, 1 step) v WHERE @go;  -- VIDHARBHA · AIR · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_METRO, @st_AIR, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.25 t, 'FIXED' rt, 200 r, NULL step UNION ALL SELECT 0.251 f, 0.5 t, 'FIXED' rt, 200 r, NULL step UNION ALL SELECT 0.501 f, 1 t, 'FIXED' rt, 200 r, NULL step UNION ALL SELECT 1.001 f, 99 t, 'PER_EXTRA_KG' rt, 200 r, 1 step) v WHERE @go;  -- METRO · AIR · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_WEST, @st_AIR, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.25 t, 'FIXED' rt, 200 r, NULL step UNION ALL SELECT 0.251 f, 0.5 t, 'FIXED' rt, 200 r, NULL step UNION ALL SELECT 0.501 f, 1 t, 'FIXED' rt, 200 r, NULL step UNION ALL SELECT 1.001 f, 99 t, 'PER_EXTRA_KG' rt, 200 r, 1 step) v WHERE @go;  -- WEST · AIR · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_SOUTH, @st_AIR, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.25 t, 'FIXED' rt, 200 r, NULL step UNION ALL SELECT 0.251 f, 0.5 t, 'FIXED' rt, 200 r, NULL step UNION ALL SELECT 0.501 f, 1 t, 'FIXED' rt, 200 r, NULL step UNION ALL SELECT 1.001 f, 99 t, 'PER_EXTRA_KG' rt, 200 r, 1 step) v WHERE @go;  -- SOUTH · AIR · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_CENTRAL, @st_AIR, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.25 t, 'FIXED' rt, 200 r, NULL step UNION ALL SELECT 0.251 f, 0.5 t, 'FIXED' rt, 200 r, NULL step UNION ALL SELECT 0.501 f, 1 t, 'FIXED' rt, 200 r, NULL step UNION ALL SELECT 1.001 f, 99 t, 'PER_EXTRA_KG' rt, 200 r, 1 step) v WHERE @go;  -- CENTRAL · AIR · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_NORTH, @st_AIR, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.25 t, 'FIXED' rt, 200 r, NULL step UNION ALL SELECT 0.251 f, 0.5 t, 'FIXED' rt, 200 r, NULL step UNION ALL SELECT 0.501 f, 1 t, 'FIXED' rt, 200 r, NULL step UNION ALL SELECT 1.001 f, 99 t, 'PER_EXTRA_KG' rt, 200 r, 1 step) v WHERE @go;  -- NORTH · AIR · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_EAST, @st_AIR, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.25 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.251 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 1 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 1.001 f, 99 t, 'PER_EXTRA_KG' rt, 220 r, 1 step) v WHERE @go;  -- EAST · AIR · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_NORTH_EAST, @st_AIR, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.25 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.251 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 1 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 1.001 f, 99 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go;  -- NORTH_EAST · AIR · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_PUNE, @st_SURFACE, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 50 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 40 r, 1 step) v WHERE @go;  -- PUNE · SURFACE · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_EXPUNE, @st_SURFACE, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 60 r, 1 step) v WHERE @go;  -- EXPUNE · SURFACE · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_MAHARASHTRA, @st_SURFACE, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 60 r, 1 step) v WHERE @go;  -- MAHARASHTRA · SURFACE · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_EXMAHARASHTRA, @st_SURFACE, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 60 r, 1 step) v WHERE @go;  -- EXMAHARASHTRA · SURFACE · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_VIDHARBHA, @st_SURFACE, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 60 r, 1 step) v WHERE @go;  -- VIDHARBHA · SURFACE · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_VIDHARBHA, @st_AIR, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 150 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 150 r, 1 step) v WHERE @go;  -- VIDHARBHA · AIR · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_METRO, @st_SURFACE, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 90 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 90 r, 1 step) v WHERE @go;  -- METRO · SURFACE · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_WEST, @st_SURFACE, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 90 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 90 r, 1 step) v WHERE @go;  -- WEST · SURFACE · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_SOUTH, @st_SURFACE, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 90 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 90 r, 1 step) v WHERE @go;  -- SOUTH · SURFACE · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_CENTRAL, @st_SURFACE, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 90 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 90 r, 1 step) v WHERE @go;  -- CENTRAL · SURFACE · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_NORTH, @st_SURFACE, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 90 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 90 r, 1 step) v WHERE @go;  -- NORTH · SURFACE · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_EAST, @st_SURFACE, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 110 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 110 r, 1 step) v WHERE @go;  -- EAST · SURFACE · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_NORTH_EAST, @st_SURFACE, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 110 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 110 r, 1 step) v WHERE @go;  -- NORTH_EAST · SURFACE · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_METRO, @st_AIR, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 200 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 200 r, 1 step) v WHERE @go;  -- METRO · AIR · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_WEST, @st_AIR, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 200 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 200 r, 1 step) v WHERE @go;  -- WEST · AIR · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_SOUTH, @st_AIR, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 200 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 200 r, 1 step) v WHERE @go;  -- SOUTH · AIR · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_CENTRAL, @st_AIR, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 200 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 200 r, 1 step) v WHERE @go;  -- CENTRAL · AIR · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_NORTH, @st_AIR, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 200 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 200 r, 1 step) v WHERE @go;  -- NORTH · AIR · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_EAST, @st_AIR, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go;  -- EAST · AIR · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_NORTH_EAST, @st_AIR, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go;  -- NORTH_EAST · AIR · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_PUNE, @st_CARGO, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 20 t, 'FIXED' rt, 200 r, NULL step UNION ALL SELECT 20.001 f, 9999 t, 'PER_EXTRA_KG' rt, 10 r, 1 step) v WHERE @go;  -- PUNE · CARGO · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_EXPUNE, @st_CARGO, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 20 t, 'FIXED' rt, 500 r, NULL step UNION ALL SELECT 20.001 f, 9999 t, 'PER_EXTRA_KG' rt, 25 r, 1 step) v WHERE @go;  -- EXPUNE · CARGO · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_MAHARASHTRA, @st_CARGO, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 20 t, 'FIXED' rt, 500 r, NULL step UNION ALL SELECT 20.001 f, 9999 t, 'PER_EXTRA_KG' rt, 25 r, 1 step) v WHERE @go;  -- MAHARASHTRA · CARGO · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_VIDHARBHA, @st_CARGO, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 20 t, 'FIXED' rt, 700 r, NULL step UNION ALL SELECT 20.001 f, 9999 t, 'PER_EXTRA_KG' rt, 35 r, 1 step) v WHERE @go;  -- VIDHARBHA · CARGO · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_METRO, @st_CARGO, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 20 t, 'FIXED' rt, 700 r, NULL step UNION ALL SELECT 20.001 f, 9999 t, 'PER_EXTRA_KG' rt, 35 r, 1 step) v WHERE @go;  -- METRO · CARGO · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_WEST, @st_CARGO, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 20 t, 'FIXED' rt, 700 r, NULL step UNION ALL SELECT 20.001 f, 9999 t, 'PER_EXTRA_KG' rt, 35 r, 1 step) v WHERE @go;  -- WEST · CARGO · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_SOUTH, @st_CARGO, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 20 t, 'FIXED' rt, 700 r, NULL step UNION ALL SELECT 20.001 f, 9999 t, 'PER_EXTRA_KG' rt, 35 r, 1 step) v WHERE @go;  -- SOUTH · CARGO · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_CENTRAL, @st_CARGO, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 20 t, 'FIXED' rt, 700 r, NULL step UNION ALL SELECT 20.001 f, 9999 t, 'PER_EXTRA_KG' rt, 35 r, 1 step) v WHERE @go;  -- CENTRAL · CARGO · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_NORTH, @st_CARGO, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 20 t, 'FIXED' rt, 700 r, NULL step UNION ALL SELECT 20.001 f, 9999 t, 'PER_EXTRA_KG' rt, 35 r, 1 step) v WHERE @go;  -- NORTH · CARGO · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_EAST, @st_CARGO, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 20 t, 'FIXED' rt, 1000 r, NULL step UNION ALL SELECT 20.001 f, 9999 t, 'PER_EXTRA_KG' rt, 50 r, 1 step) v WHERE @go;  -- EAST · CARGO · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_NORTH_EAST, @st_CARGO, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 20 t, 'FIXED' rt, 1000 r, NULL step UNION ALL SELECT 20.001 f, 9999 t, 'PER_EXTRA_KG' rt, 50 r, 1 step) v WHERE @go;  -- NORTH_EAST · CARGO · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_PUNE, @st_PREMIUM, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go;  -- PUNE · PREMIUM · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_EXPUNE, @st_PREMIUM, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go;  -- EXPUNE · PREMIUM · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_MAHARASHTRA, @st_PREMIUM, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go;  -- MAHARASHTRA · PREMIUM · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_EXMAHARASHTRA, @st_PREMIUM, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go;  -- EXMAHARASHTRA · PREMIUM · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_VIDHARBHA, @st_PREMIUM, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go;  -- VIDHARBHA · PREMIUM · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_METRO, @st_PREMIUM, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go;  -- METRO · PREMIUM · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_WEST, @st_PREMIUM, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go;  -- WEST · PREMIUM · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_SOUTH, @st_PREMIUM, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go;  -- SOUTH · PREMIUM · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_CENTRAL, @st_PREMIUM, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go;  -- CENTRAL · PREMIUM · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_NORTH, @st_PREMIUM, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go;  -- NORTH · PREMIUM · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_EAST, @st_PREMIUM, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 400 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 400 r, 1 step) v WHERE @go;  -- EAST · PREMIUM · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_NORTH_EAST, @st_PREMIUM, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 400 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 400 r, 1 step) v WHERE @go;  -- NORTH_EAST · PREMIUM · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_PUNE, @st_PREMIUM, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go;  -- PUNE · PREMIUM · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_EXPUNE, @st_PREMIUM, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go;  -- EXPUNE · PREMIUM · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_MAHARASHTRA, @st_PREMIUM, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go;  -- MAHARASHTRA · PREMIUM · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_EXMAHARASHTRA, @st_PREMIUM, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go;  -- EXMAHARASHTRA · PREMIUM · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_VIDHARBHA, @st_PREMIUM, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go;  -- VIDHARBHA · PREMIUM · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_METRO, @st_PREMIUM, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go;  -- METRO · PREMIUM · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_WEST, @st_PREMIUM, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go;  -- WEST · PREMIUM · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_SOUTH, @st_PREMIUM, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go;  -- SOUTH · PREMIUM · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_CENTRAL, @st_PREMIUM, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go;  -- CENTRAL · PREMIUM · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_NORTH, @st_PREMIUM, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go;  -- NORTH · PREMIUM · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_EAST, @st_PREMIUM, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 400 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 400 r, 1 step) v WHERE @go;  -- EAST · PREMIUM · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_NORTH_EAST, @st_PREMIUM, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 400 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 400 r, 1 step) v WHERE @go;  -- NORTH_EAST · PREMIUM · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_KERALA, @st_AIR, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.25 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.251 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 1 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 1.001 f, 99 t, 'PER_EXTRA_KG' rt, 220 r, 1 step) v WHERE @go;  -- KERALA · AIR · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_KERALA, @st_SURFACE, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 110 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 110 r, 1 step) v WHERE @go;  -- KERALA · SURFACE · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_KERALA, @st_AIR, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go;  -- KERALA · AIR · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_KERALA, @st_CARGO, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 20 t, 'FIXED' rt, 1000 r, NULL step UNION ALL SELECT 20.001 f, 9999 t, 'PER_EXTRA_KG' rt, 50 r, 1 step) v WHERE @go;  -- KERALA · CARGO · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_KERALA, @st_PREMIUM, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 400 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 400 r, 1 step) v WHERE @go;  -- KERALA · PREMIUM · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_KERALA, @st_PREMIUM, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 400 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 400 r, 1 step) v WHERE @go;  -- KERALA · PREMIUM · NONDOC

-- Demo charges (edit or delete on the rate card page)
INSERT INTO tariff_charge_rules (id, company_id, rate_card_id, charge_code, charge_name, to_zone_id, service_type_id, package_type_id, applies_when, calc_method, charge_value, min_amount, max_amount, taxable, display_order, status, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, c.code, c.name, NULL, NULL, c.pkg, c.applies, c.method, c.val, c.mn, NULL, c.taxable, c.ord, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 'FUEL' code, 'Fuel surcharge' name, NULL pkg, 'ALWAYS' applies, 'PERCENT_OF_FREIGHT' method, 10 val, NULL mn, TRUE taxable, 1 ord
      UNION ALL SELECT 'DOCKET', 'Docket charge', NULL, 'ALWAYS', 'FLAT', 30, NULL, FALSE, 2
      UNION ALL SELECT 'HAMALI', 'Hamali (loading)', @pk_NONDOC, 'ALWAYS', 'PER_KG', 2, 20, TRUE, 3
      UNION ALL SELECT 'COD', 'COD charge', NULL, 'COD_ONLY', 'PERCENT_OF_VALUE', 2, 50, TRUE, 4) c
WHERE @go;
