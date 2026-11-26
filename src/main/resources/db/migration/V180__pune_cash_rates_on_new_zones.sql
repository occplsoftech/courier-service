-- =============================================================================
-- V180 — The Pune cash-booking rate card again, on the zones rebuilt from the pincode master (V173)
--
-- Companies OCCPL and OCCPL_COURIER only; skipped where a PUNE_CASH_2025 card already exists, so it
-- is safe to run twice. From zone PUNE. Rates are the lanes of CASH_BOOKING_RATES_2.xlsx (as V143
-- loaded them), moved onto the new zones as the owner chose on 2026-11-26:
--   PUNE <- PUNE, X_PUNE <- EXPUNE, MAHARASHTRA <- MAHARASHTRA, X_MAHARASHTRA <- EXMAHARASHTRA (no Cargo,
--   as in the sheet), VIDHARBHA <- VIDHARBHA, NORTH_EAST <- NORTH_EAST, and REST_OF_INDIA <- the standard
--   rest-of-India rates (METRO / WEST / NORTH / SOUTH / CENTRAL: Surface 90 + 90/kg, Cargo 700 + 35/kg,
--   Premium 250, Air 200); the higher KERALA / EAST rates are not used.
-- Freight only (GST 18 %, rounded to the rupee); the old demo charges are not brought back.
-- A lane whose zone, service type or package type is missing is skipped.
-- =============================================================================


-- OCCPL -----------------------------------------------------------------------------
SET @co = (SELECT company_id FROM companies WHERE company_code = 'OCCPL' AND deleted = FALSE LIMIT 1);
SET @go = @co IS NOT NULL AND NOT EXISTS (SELECT 1 FROM tariff_rate_cards WHERE company_id = @co AND code = 'PUNE_CASH_2025' AND deleted = FALSE);
SET @z_PUNE = (SELECT id FROM tariff_zones WHERE company_id = @co AND code = 'PUNE' AND deleted = FALSE LIMIT 1);
SET @z_X_PUNE = (SELECT id FROM tariff_zones WHERE company_id = @co AND code = 'X_PUNE' AND deleted = FALSE LIMIT 1);
SET @z_MAHARASHTRA = (SELECT id FROM tariff_zones WHERE company_id = @co AND code = 'MAHARASHTRA' AND deleted = FALSE LIMIT 1);
SET @z_X_MAHARASHTRA = (SELECT id FROM tariff_zones WHERE company_id = @co AND code = 'X_MAHARASHTRA' AND deleted = FALSE LIMIT 1);
SET @z_VIDHARBHA = (SELECT id FROM tariff_zones WHERE company_id = @co AND code = 'VIDHARBHA' AND deleted = FALSE LIMIT 1);
SET @z_REST_OF_INDIA = (SELECT id FROM tariff_zones WHERE company_id = @co AND code = 'REST_OF_INDIA' AND deleted = FALSE LIMIT 1);
SET @z_NORTH_EAST = (SELECT id FROM tariff_zones WHERE company_id = @co AND code = 'NORTH_EAST' AND deleted = FALSE LIMIT 1);
SET @st_AIR = (SELECT id FROM master_service_types WHERE company_id = @co AND deleted = FALSE AND (code IN ('AIR', 'AIR') OR name IN ('By Air', 'AIR')) ORDER BY (code = 'AIR') DESC, created_at LIMIT 1);
SET @st_SURFACE = (SELECT id FROM master_service_types WHERE company_id = @co AND deleted = FALSE AND (code IN ('SURFACE', 'SRF') OR name IN ('Surface', 'SURFACE')) ORDER BY (code = 'SURFACE') DESC, created_at LIMIT 1);
SET @st_PREMIUM = (SELECT id FROM master_service_types WHERE company_id = @co AND deleted = FALSE AND (code IN ('PREMIUM', 'PRM') OR name IN ('Premium', 'PREMIUM')) ORDER BY (code = 'PREMIUM') DESC, created_at LIMIT 1);
SET @st_CARGO = (SELECT id FROM master_service_types WHERE company_id = @co AND deleted = FALSE AND (code IN ('CARGO', 'CRG') OR name IN ('Cargo', 'CARGO')) ORDER BY (code = 'CARGO') DESC, created_at LIMIT 1);
SET @pk_DOC = (SELECT id FROM master_package_types WHERE company_id = @co AND is_document = TRUE AND deleted = FALSE ORDER BY (code IN ('DOC', 'DOCUMENT')) DESC, created_at LIMIT 1);
SET @pk_NONDOC = (SELECT id FROM master_package_types WHERE company_id = @co AND is_document = FALSE AND deleted = FALSE AND (code IN ('NONDOC', 'NON-DOC', 'NON_DOCUMENT', 'PARCEL') OR name = 'Non Document') ORDER BY (code = 'NONDOC') DESC, created_at LIMIT 1);
SET @go = @go AND @z_PUNE IS NOT NULL;
SET @card = UNHEX(REPLACE(UUID(), '-', ''));
INSERT INTO tariff_rate_cards (id, company_id, code, name, from_zone_id, effective_from, effective_to, gst_percent, volumetric_divisor, total_rounding, description, status, created_at, updated_at, deleted, version)
SELECT @card, @co, 'PUNE_CASH_2025', 'Pune cash booking', @z_PUNE, '2025-04-01', NULL, 18.00, NULL, 'NEAREST_RUPEE', 'CASH_BOOKING_RATES_2.xlsx on the pincode-master zones (V180)', 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0 FROM DUAL WHERE @go;
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_PUNE, @st_SURFACE, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.25 t, 'FIXED' rt, 50 r, NULL step UNION ALL SELECT 0.251 f, 0.5 t, 'FIXED' rt, 50 r, NULL step UNION ALL SELECT 0.501 f, 1 t, 'FIXED' rt, 50 r, NULL step UNION ALL SELECT 1.001 f, 99 t, 'PER_EXTRA_KG' rt, 40 r, 1 step) v WHERE @go AND @z_PUNE IS NOT NULL AND @st_SURFACE IS NOT NULL AND @pk_DOC IS NOT NULL;  -- PUNE · SURFACE · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_PUNE, @st_SURFACE, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 50 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 40 r, 1 step) v WHERE @go AND @z_PUNE IS NOT NULL AND @st_SURFACE IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- PUNE · SURFACE · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_PUNE, @st_CARGO, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 20 t, 'FIXED' rt, 200 r, NULL step UNION ALL SELECT 20.001 f, 9999 t, 'PER_EXTRA_KG' rt, 10 r, 1 step) v WHERE @go AND @z_PUNE IS NOT NULL AND @st_CARGO IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- PUNE · CARGO · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_PUNE, @st_PREMIUM, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go AND @z_PUNE IS NOT NULL AND @st_PREMIUM IS NOT NULL AND @pk_DOC IS NOT NULL;  -- PUNE · PREMIUM · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_PUNE, @st_PREMIUM, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go AND @z_PUNE IS NOT NULL AND @st_PREMIUM IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- PUNE · PREMIUM · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_X_PUNE, @st_SURFACE, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.25 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 0.251 f, 0.5 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 0.501 f, 1 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 1.001 f, 99 t, 'PER_EXTRA_KG' rt, 60 r, 1 step) v WHERE @go AND @z_X_PUNE IS NOT NULL AND @st_SURFACE IS NOT NULL AND @pk_DOC IS NOT NULL;  -- X_PUNE · SURFACE · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_X_PUNE, @st_SURFACE, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 60 r, 1 step) v WHERE @go AND @z_X_PUNE IS NOT NULL AND @st_SURFACE IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- X_PUNE · SURFACE · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_X_PUNE, @st_CARGO, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 20 t, 'FIXED' rt, 500 r, NULL step UNION ALL SELECT 20.001 f, 9999 t, 'PER_EXTRA_KG' rt, 25 r, 1 step) v WHERE @go AND @z_X_PUNE IS NOT NULL AND @st_CARGO IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- X_PUNE · CARGO · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_X_PUNE, @st_PREMIUM, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go AND @z_X_PUNE IS NOT NULL AND @st_PREMIUM IS NOT NULL AND @pk_DOC IS NOT NULL;  -- X_PUNE · PREMIUM · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_X_PUNE, @st_PREMIUM, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go AND @z_X_PUNE IS NOT NULL AND @st_PREMIUM IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- X_PUNE · PREMIUM · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_MAHARASHTRA, @st_SURFACE, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.25 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 0.251 f, 0.5 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 0.501 f, 1 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 1.001 f, 99 t, 'PER_EXTRA_KG' rt, 60 r, 1 step) v WHERE @go AND @z_MAHARASHTRA IS NOT NULL AND @st_SURFACE IS NOT NULL AND @pk_DOC IS NOT NULL;  -- MAHARASHTRA · SURFACE · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_MAHARASHTRA, @st_SURFACE, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 60 r, 1 step) v WHERE @go AND @z_MAHARASHTRA IS NOT NULL AND @st_SURFACE IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- MAHARASHTRA · SURFACE · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_MAHARASHTRA, @st_CARGO, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 20 t, 'FIXED' rt, 500 r, NULL step UNION ALL SELECT 20.001 f, 9999 t, 'PER_EXTRA_KG' rt, 25 r, 1 step) v WHERE @go AND @z_MAHARASHTRA IS NOT NULL AND @st_CARGO IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- MAHARASHTRA · CARGO · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_MAHARASHTRA, @st_PREMIUM, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go AND @z_MAHARASHTRA IS NOT NULL AND @st_PREMIUM IS NOT NULL AND @pk_DOC IS NOT NULL;  -- MAHARASHTRA · PREMIUM · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_MAHARASHTRA, @st_PREMIUM, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go AND @z_MAHARASHTRA IS NOT NULL AND @st_PREMIUM IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- MAHARASHTRA · PREMIUM · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_X_MAHARASHTRA, @st_SURFACE, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.25 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 0.251 f, 0.5 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 0.501 f, 1 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 1.001 f, 99 t, 'PER_EXTRA_KG' rt, 60 r, 1 step) v WHERE @go AND @z_X_MAHARASHTRA IS NOT NULL AND @st_SURFACE IS NOT NULL AND @pk_DOC IS NOT NULL;  -- X_MAHARASHTRA · SURFACE · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_X_MAHARASHTRA, @st_SURFACE, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 60 r, 1 step) v WHERE @go AND @z_X_MAHARASHTRA IS NOT NULL AND @st_SURFACE IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- X_MAHARASHTRA · SURFACE · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_X_MAHARASHTRA, @st_PREMIUM, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go AND @z_X_MAHARASHTRA IS NOT NULL AND @st_PREMIUM IS NOT NULL AND @pk_DOC IS NOT NULL;  -- X_MAHARASHTRA · PREMIUM · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_X_MAHARASHTRA, @st_PREMIUM, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go AND @z_X_MAHARASHTRA IS NOT NULL AND @st_PREMIUM IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- X_MAHARASHTRA · PREMIUM · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_VIDHARBHA, @st_SURFACE, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.25 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 0.251 f, 0.5 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 0.501 f, 1 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 1.001 f, 999 t, 'PER_EXTRA_KG' rt, 60 r, 1 step) v WHERE @go AND @z_VIDHARBHA IS NOT NULL AND @st_SURFACE IS NOT NULL AND @pk_DOC IS NOT NULL;  -- VIDHARBHA · SURFACE · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_VIDHARBHA, @st_SURFACE, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 60 r, 1 step) v WHERE @go AND @z_VIDHARBHA IS NOT NULL AND @st_SURFACE IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- VIDHARBHA · SURFACE · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_VIDHARBHA, @st_CARGO, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 20 t, 'FIXED' rt, 700 r, NULL step UNION ALL SELECT 20.001 f, 9999 t, 'PER_EXTRA_KG' rt, 35 r, 1 step) v WHERE @go AND @z_VIDHARBHA IS NOT NULL AND @st_CARGO IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- VIDHARBHA · CARGO · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_VIDHARBHA, @st_PREMIUM, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go AND @z_VIDHARBHA IS NOT NULL AND @st_PREMIUM IS NOT NULL AND @pk_DOC IS NOT NULL;  -- VIDHARBHA · PREMIUM · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_VIDHARBHA, @st_PREMIUM, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go AND @z_VIDHARBHA IS NOT NULL AND @st_PREMIUM IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- VIDHARBHA · PREMIUM · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_VIDHARBHA, @st_AIR, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.25 t, 'FIXED' rt, 150 r, NULL step UNION ALL SELECT 0.251 f, 0.5 t, 'FIXED' rt, 150 r, NULL step UNION ALL SELECT 0.501 f, 1 t, 'FIXED' rt, 150 r, NULL step UNION ALL SELECT 1.001 f, 999 t, 'PER_EXTRA_KG' rt, 150 r, 1 step) v WHERE @go AND @z_VIDHARBHA IS NOT NULL AND @st_AIR IS NOT NULL AND @pk_DOC IS NOT NULL;  -- VIDHARBHA · AIR · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_VIDHARBHA, @st_AIR, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 150 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 150 r, 1 step) v WHERE @go AND @z_VIDHARBHA IS NOT NULL AND @st_AIR IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- VIDHARBHA · AIR · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_REST_OF_INDIA, @st_SURFACE, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 90 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 90 r, 1 step) v WHERE @go AND @z_REST_OF_INDIA IS NOT NULL AND @st_SURFACE IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- REST_OF_INDIA · SURFACE · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_REST_OF_INDIA, @st_CARGO, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 20 t, 'FIXED' rt, 700 r, NULL step UNION ALL SELECT 20.001 f, 9999 t, 'PER_EXTRA_KG' rt, 35 r, 1 step) v WHERE @go AND @z_REST_OF_INDIA IS NOT NULL AND @st_CARGO IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- REST_OF_INDIA · CARGO · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_REST_OF_INDIA, @st_PREMIUM, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go AND @z_REST_OF_INDIA IS NOT NULL AND @st_PREMIUM IS NOT NULL AND @pk_DOC IS NOT NULL;  -- REST_OF_INDIA · PREMIUM · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_REST_OF_INDIA, @st_PREMIUM, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go AND @z_REST_OF_INDIA IS NOT NULL AND @st_PREMIUM IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- REST_OF_INDIA · PREMIUM · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_REST_OF_INDIA, @st_AIR, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.25 t, 'FIXED' rt, 200 r, NULL step UNION ALL SELECT 0.251 f, 0.5 t, 'FIXED' rt, 200 r, NULL step UNION ALL SELECT 0.501 f, 1 t, 'FIXED' rt, 200 r, NULL step UNION ALL SELECT 1.001 f, 99 t, 'PER_EXTRA_KG' rt, 200 r, 1 step) v WHERE @go AND @z_REST_OF_INDIA IS NOT NULL AND @st_AIR IS NOT NULL AND @pk_DOC IS NOT NULL;  -- REST_OF_INDIA · AIR · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_REST_OF_INDIA, @st_AIR, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 200 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 200 r, 1 step) v WHERE @go AND @z_REST_OF_INDIA IS NOT NULL AND @st_AIR IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- REST_OF_INDIA · AIR · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_NORTH_EAST, @st_SURFACE, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 110 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 110 r, 1 step) v WHERE @go AND @z_NORTH_EAST IS NOT NULL AND @st_SURFACE IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- NORTH_EAST · SURFACE · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_NORTH_EAST, @st_CARGO, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 20 t, 'FIXED' rt, 1000 r, NULL step UNION ALL SELECT 20.001 f, 9999 t, 'PER_EXTRA_KG' rt, 50 r, 1 step) v WHERE @go AND @z_NORTH_EAST IS NOT NULL AND @st_CARGO IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- NORTH_EAST · CARGO · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_NORTH_EAST, @st_PREMIUM, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 400 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 400 r, 1 step) v WHERE @go AND @z_NORTH_EAST IS NOT NULL AND @st_PREMIUM IS NOT NULL AND @pk_DOC IS NOT NULL;  -- NORTH_EAST · PREMIUM · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_NORTH_EAST, @st_PREMIUM, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 400 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 400 r, 1 step) v WHERE @go AND @z_NORTH_EAST IS NOT NULL AND @st_PREMIUM IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- NORTH_EAST · PREMIUM · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_NORTH_EAST, @st_AIR, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.25 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.251 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 1 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 1.001 f, 99 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go AND @z_NORTH_EAST IS NOT NULL AND @st_AIR IS NOT NULL AND @pk_DOC IS NOT NULL;  -- NORTH_EAST · AIR · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_NORTH_EAST, @st_AIR, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go AND @z_NORTH_EAST IS NOT NULL AND @st_AIR IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- NORTH_EAST · AIR · NONDOC

-- OCCPL_COURIER -----------------------------------------------------------------------------
SET @co = (SELECT company_id FROM companies WHERE company_code = 'OCCPL_COURIER' AND deleted = FALSE LIMIT 1);
SET @go = @co IS NOT NULL AND NOT EXISTS (SELECT 1 FROM tariff_rate_cards WHERE company_id = @co AND code = 'PUNE_CASH_2025' AND deleted = FALSE);
SET @z_PUNE = (SELECT id FROM tariff_zones WHERE company_id = @co AND code = 'PUNE' AND deleted = FALSE LIMIT 1);
SET @z_X_PUNE = (SELECT id FROM tariff_zones WHERE company_id = @co AND code = 'X_PUNE' AND deleted = FALSE LIMIT 1);
SET @z_MAHARASHTRA = (SELECT id FROM tariff_zones WHERE company_id = @co AND code = 'MAHARASHTRA' AND deleted = FALSE LIMIT 1);
SET @z_X_MAHARASHTRA = (SELECT id FROM tariff_zones WHERE company_id = @co AND code = 'X_MAHARASHTRA' AND deleted = FALSE LIMIT 1);
SET @z_VIDHARBHA = (SELECT id FROM tariff_zones WHERE company_id = @co AND code = 'VIDHARBHA' AND deleted = FALSE LIMIT 1);
SET @z_REST_OF_INDIA = (SELECT id FROM tariff_zones WHERE company_id = @co AND code = 'REST_OF_INDIA' AND deleted = FALSE LIMIT 1);
SET @z_NORTH_EAST = (SELECT id FROM tariff_zones WHERE company_id = @co AND code = 'NORTH_EAST' AND deleted = FALSE LIMIT 1);
SET @st_AIR = (SELECT id FROM master_service_types WHERE company_id = @co AND deleted = FALSE AND (code IN ('AIR', 'AIR') OR name IN ('By Air', 'AIR')) ORDER BY (code = 'AIR') DESC, created_at LIMIT 1);
SET @st_SURFACE = (SELECT id FROM master_service_types WHERE company_id = @co AND deleted = FALSE AND (code IN ('SURFACE', 'SRF') OR name IN ('Surface', 'SURFACE')) ORDER BY (code = 'SURFACE') DESC, created_at LIMIT 1);
SET @st_PREMIUM = (SELECT id FROM master_service_types WHERE company_id = @co AND deleted = FALSE AND (code IN ('PREMIUM', 'PRM') OR name IN ('Premium', 'PREMIUM')) ORDER BY (code = 'PREMIUM') DESC, created_at LIMIT 1);
SET @st_CARGO = (SELECT id FROM master_service_types WHERE company_id = @co AND deleted = FALSE AND (code IN ('CARGO', 'CRG') OR name IN ('Cargo', 'CARGO')) ORDER BY (code = 'CARGO') DESC, created_at LIMIT 1);
SET @pk_DOC = (SELECT id FROM master_package_types WHERE company_id = @co AND is_document = TRUE AND deleted = FALSE ORDER BY (code IN ('DOC', 'DOCUMENT')) DESC, created_at LIMIT 1);
SET @pk_NONDOC = (SELECT id FROM master_package_types WHERE company_id = @co AND is_document = FALSE AND deleted = FALSE AND (code IN ('NONDOC', 'NON-DOC', 'NON_DOCUMENT', 'PARCEL') OR name = 'Non Document') ORDER BY (code = 'NONDOC') DESC, created_at LIMIT 1);
SET @go = @go AND @z_PUNE IS NOT NULL;
SET @card = UNHEX(REPLACE(UUID(), '-', ''));
INSERT INTO tariff_rate_cards (id, company_id, code, name, from_zone_id, effective_from, effective_to, gst_percent, volumetric_divisor, total_rounding, description, status, created_at, updated_at, deleted, version)
SELECT @card, @co, 'PUNE_CASH_2025', 'Pune cash booking', @z_PUNE, '2025-04-01', NULL, 18.00, NULL, 'NEAREST_RUPEE', 'CASH_BOOKING_RATES_2.xlsx on the pincode-master zones (V180)', 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0 FROM DUAL WHERE @go;
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_PUNE, @st_SURFACE, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.25 t, 'FIXED' rt, 50 r, NULL step UNION ALL SELECT 0.251 f, 0.5 t, 'FIXED' rt, 50 r, NULL step UNION ALL SELECT 0.501 f, 1 t, 'FIXED' rt, 50 r, NULL step UNION ALL SELECT 1.001 f, 99 t, 'PER_EXTRA_KG' rt, 40 r, 1 step) v WHERE @go AND @z_PUNE IS NOT NULL AND @st_SURFACE IS NOT NULL AND @pk_DOC IS NOT NULL;  -- PUNE · SURFACE · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_PUNE, @st_SURFACE, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 50 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 40 r, 1 step) v WHERE @go AND @z_PUNE IS NOT NULL AND @st_SURFACE IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- PUNE · SURFACE · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_PUNE, @st_CARGO, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 20 t, 'FIXED' rt, 200 r, NULL step UNION ALL SELECT 20.001 f, 9999 t, 'PER_EXTRA_KG' rt, 10 r, 1 step) v WHERE @go AND @z_PUNE IS NOT NULL AND @st_CARGO IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- PUNE · CARGO · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_PUNE, @st_PREMIUM, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go AND @z_PUNE IS NOT NULL AND @st_PREMIUM IS NOT NULL AND @pk_DOC IS NOT NULL;  -- PUNE · PREMIUM · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_PUNE, @st_PREMIUM, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go AND @z_PUNE IS NOT NULL AND @st_PREMIUM IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- PUNE · PREMIUM · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_X_PUNE, @st_SURFACE, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.25 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 0.251 f, 0.5 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 0.501 f, 1 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 1.001 f, 99 t, 'PER_EXTRA_KG' rt, 60 r, 1 step) v WHERE @go AND @z_X_PUNE IS NOT NULL AND @st_SURFACE IS NOT NULL AND @pk_DOC IS NOT NULL;  -- X_PUNE · SURFACE · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_X_PUNE, @st_SURFACE, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 60 r, 1 step) v WHERE @go AND @z_X_PUNE IS NOT NULL AND @st_SURFACE IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- X_PUNE · SURFACE · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_X_PUNE, @st_CARGO, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 20 t, 'FIXED' rt, 500 r, NULL step UNION ALL SELECT 20.001 f, 9999 t, 'PER_EXTRA_KG' rt, 25 r, 1 step) v WHERE @go AND @z_X_PUNE IS NOT NULL AND @st_CARGO IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- X_PUNE · CARGO · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_X_PUNE, @st_PREMIUM, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go AND @z_X_PUNE IS NOT NULL AND @st_PREMIUM IS NOT NULL AND @pk_DOC IS NOT NULL;  -- X_PUNE · PREMIUM · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_X_PUNE, @st_PREMIUM, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go AND @z_X_PUNE IS NOT NULL AND @st_PREMIUM IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- X_PUNE · PREMIUM · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_MAHARASHTRA, @st_SURFACE, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.25 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 0.251 f, 0.5 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 0.501 f, 1 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 1.001 f, 99 t, 'PER_EXTRA_KG' rt, 60 r, 1 step) v WHERE @go AND @z_MAHARASHTRA IS NOT NULL AND @st_SURFACE IS NOT NULL AND @pk_DOC IS NOT NULL;  -- MAHARASHTRA · SURFACE · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_MAHARASHTRA, @st_SURFACE, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 60 r, 1 step) v WHERE @go AND @z_MAHARASHTRA IS NOT NULL AND @st_SURFACE IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- MAHARASHTRA · SURFACE · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_MAHARASHTRA, @st_CARGO, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 20 t, 'FIXED' rt, 500 r, NULL step UNION ALL SELECT 20.001 f, 9999 t, 'PER_EXTRA_KG' rt, 25 r, 1 step) v WHERE @go AND @z_MAHARASHTRA IS NOT NULL AND @st_CARGO IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- MAHARASHTRA · CARGO · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_MAHARASHTRA, @st_PREMIUM, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go AND @z_MAHARASHTRA IS NOT NULL AND @st_PREMIUM IS NOT NULL AND @pk_DOC IS NOT NULL;  -- MAHARASHTRA · PREMIUM · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_MAHARASHTRA, @st_PREMIUM, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go AND @z_MAHARASHTRA IS NOT NULL AND @st_PREMIUM IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- MAHARASHTRA · PREMIUM · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_X_MAHARASHTRA, @st_SURFACE, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.25 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 0.251 f, 0.5 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 0.501 f, 1 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 1.001 f, 99 t, 'PER_EXTRA_KG' rt, 60 r, 1 step) v WHERE @go AND @z_X_MAHARASHTRA IS NOT NULL AND @st_SURFACE IS NOT NULL AND @pk_DOC IS NOT NULL;  -- X_MAHARASHTRA · SURFACE · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_X_MAHARASHTRA, @st_SURFACE, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 60 r, 1 step) v WHERE @go AND @z_X_MAHARASHTRA IS NOT NULL AND @st_SURFACE IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- X_MAHARASHTRA · SURFACE · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_X_MAHARASHTRA, @st_PREMIUM, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go AND @z_X_MAHARASHTRA IS NOT NULL AND @st_PREMIUM IS NOT NULL AND @pk_DOC IS NOT NULL;  -- X_MAHARASHTRA · PREMIUM · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_X_MAHARASHTRA, @st_PREMIUM, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go AND @z_X_MAHARASHTRA IS NOT NULL AND @st_PREMIUM IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- X_MAHARASHTRA · PREMIUM · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_VIDHARBHA, @st_SURFACE, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.25 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 0.251 f, 0.5 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 0.501 f, 1 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 1.001 f, 999 t, 'PER_EXTRA_KG' rt, 60 r, 1 step) v WHERE @go AND @z_VIDHARBHA IS NOT NULL AND @st_SURFACE IS NOT NULL AND @pk_DOC IS NOT NULL;  -- VIDHARBHA · SURFACE · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_VIDHARBHA, @st_SURFACE, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 70 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 60 r, 1 step) v WHERE @go AND @z_VIDHARBHA IS NOT NULL AND @st_SURFACE IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- VIDHARBHA · SURFACE · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_VIDHARBHA, @st_CARGO, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 20 t, 'FIXED' rt, 700 r, NULL step UNION ALL SELECT 20.001 f, 9999 t, 'PER_EXTRA_KG' rt, 35 r, 1 step) v WHERE @go AND @z_VIDHARBHA IS NOT NULL AND @st_CARGO IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- VIDHARBHA · CARGO · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_VIDHARBHA, @st_PREMIUM, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go AND @z_VIDHARBHA IS NOT NULL AND @st_PREMIUM IS NOT NULL AND @pk_DOC IS NOT NULL;  -- VIDHARBHA · PREMIUM · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_VIDHARBHA, @st_PREMIUM, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go AND @z_VIDHARBHA IS NOT NULL AND @st_PREMIUM IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- VIDHARBHA · PREMIUM · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_VIDHARBHA, @st_AIR, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.25 t, 'FIXED' rt, 150 r, NULL step UNION ALL SELECT 0.251 f, 0.5 t, 'FIXED' rt, 150 r, NULL step UNION ALL SELECT 0.501 f, 1 t, 'FIXED' rt, 150 r, NULL step UNION ALL SELECT 1.001 f, 999 t, 'PER_EXTRA_KG' rt, 150 r, 1 step) v WHERE @go AND @z_VIDHARBHA IS NOT NULL AND @st_AIR IS NOT NULL AND @pk_DOC IS NOT NULL;  -- VIDHARBHA · AIR · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_VIDHARBHA, @st_AIR, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 150 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 150 r, 1 step) v WHERE @go AND @z_VIDHARBHA IS NOT NULL AND @st_AIR IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- VIDHARBHA · AIR · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_REST_OF_INDIA, @st_SURFACE, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 90 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 90 r, 1 step) v WHERE @go AND @z_REST_OF_INDIA IS NOT NULL AND @st_SURFACE IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- REST_OF_INDIA · SURFACE · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_REST_OF_INDIA, @st_CARGO, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 20 t, 'FIXED' rt, 700 r, NULL step UNION ALL SELECT 20.001 f, 9999 t, 'PER_EXTRA_KG' rt, 35 r, 1 step) v WHERE @go AND @z_REST_OF_INDIA IS NOT NULL AND @st_CARGO IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- REST_OF_INDIA · CARGO · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_REST_OF_INDIA, @st_PREMIUM, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go AND @z_REST_OF_INDIA IS NOT NULL AND @st_PREMIUM IS NOT NULL AND @pk_DOC IS NOT NULL;  -- REST_OF_INDIA · PREMIUM · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_REST_OF_INDIA, @st_PREMIUM, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go AND @z_REST_OF_INDIA IS NOT NULL AND @st_PREMIUM IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- REST_OF_INDIA · PREMIUM · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_REST_OF_INDIA, @st_AIR, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.25 t, 'FIXED' rt, 200 r, NULL step UNION ALL SELECT 0.251 f, 0.5 t, 'FIXED' rt, 200 r, NULL step UNION ALL SELECT 0.501 f, 1 t, 'FIXED' rt, 200 r, NULL step UNION ALL SELECT 1.001 f, 99 t, 'PER_EXTRA_KG' rt, 200 r, 1 step) v WHERE @go AND @z_REST_OF_INDIA IS NOT NULL AND @st_AIR IS NOT NULL AND @pk_DOC IS NOT NULL;  -- REST_OF_INDIA · AIR · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_REST_OF_INDIA, @st_AIR, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 200 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 200 r, 1 step) v WHERE @go AND @z_REST_OF_INDIA IS NOT NULL AND @st_AIR IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- REST_OF_INDIA · AIR · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_NORTH_EAST, @st_SURFACE, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 110 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 110 r, 1 step) v WHERE @go AND @z_NORTH_EAST IS NOT NULL AND @st_SURFACE IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- NORTH_EAST · SURFACE · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_NORTH_EAST, @st_CARGO, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 20 t, 'FIXED' rt, 1000 r, NULL step UNION ALL SELECT 20.001 f, 9999 t, 'PER_EXTRA_KG' rt, 50 r, 1 step) v WHERE @go AND @z_NORTH_EAST IS NOT NULL AND @st_CARGO IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- NORTH_EAST · CARGO · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_NORTH_EAST, @st_PREMIUM, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 400 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 400 r, 1 step) v WHERE @go AND @z_NORTH_EAST IS NOT NULL AND @st_PREMIUM IS NOT NULL AND @pk_DOC IS NOT NULL;  -- NORTH_EAST · PREMIUM · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_NORTH_EAST, @st_PREMIUM, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.5 t, 'FIXED' rt, 400 r, NULL step UNION ALL SELECT 0.501 f, 9999 t, 'PER_EXTRA_KG' rt, 400 r, 1 step) v WHERE @go AND @z_NORTH_EAST IS NOT NULL AND @st_PREMIUM IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- NORTH_EAST · PREMIUM · NONDOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_NORTH_EAST, @st_AIR, @pk_DOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 0.25 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.251 f, 0.5 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 0.501 f, 1 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 1.001 f, 99 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go AND @z_NORTH_EAST IS NOT NULL AND @st_AIR IS NOT NULL AND @pk_DOC IS NOT NULL;  -- NORTH_EAST · AIR · DOC
INSERT INTO tariff_rate_slabs (id, company_id, rate_card_id, to_zone_id, service_type_id, package_type_id, weight_from_kg, weight_to_kg, rate_type, rate, step_kg, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @co, @card, @z_NORTH_EAST, @st_AIR, @pk_NONDOC, v.f, v.t, v.rt, v.r, v.step, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 0.001 f, 1 t, 'FIXED' rt, 250 r, NULL step UNION ALL SELECT 1.001 f, 9999 t, 'PER_EXTRA_KG' rt, 250 r, 1 step) v WHERE @go AND @z_NORTH_EAST IS NOT NULL AND @st_AIR IS NOT NULL AND @pk_NONDOC IS NOT NULL;  -- NORTH_EAST · AIR · NONDOC
