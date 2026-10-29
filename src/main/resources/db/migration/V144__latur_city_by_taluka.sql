-- =============================================================================
-- V144 — Latur district: the city of a Latur pincode is its taluka (Latur, Ausa, Udgir …), not Osmanabad.
--
-- On dev, pincodes added earlier by the postal lookup carry the India Post *division* ("Osmanabad") as
-- their city, so a Latur pincode showed city Osmanabad under district Latur. This moves the post offices
-- (areas) of the 26 Latur-district pincodes to a city named after their taluka (created under
-- district Latur when missing). Nothing is deleted; an area is skipped if its new city already has one
-- of the same name. A no-op where district Latur does not exist. Generated, do not edit.
-- =============================================================================

SET @p = UNHEX('00000000000000000000000000000001');
SET @d = (SELECT d.id FROM master_districts d JOIN master_states s ON s.id = d.state_id
          WHERE d.company_id = @p AND d.name = 'Latur' AND d.deleted = FALSE AND s.name = 'Maharashtra' ORDER BY d.created_at LIMIT 1);

INSERT INTO master_cities (id, company_id, code, name, status, display_order, district_id, is_metro, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @p, 'MH_AHMADPUR_0DD4B7', 'Ahmadpur', 'ACTIVE', 0, @d, FALSE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM DUAL WHERE @d IS NOT NULL AND NOT EXISTS (SELECT 1 FROM master_cities WHERE company_id = @p AND district_id = @d AND name = 'Ahmadpur')
  AND NOT EXISTS (SELECT 1 FROM master_cities WHERE company_id = @p AND code = 'MH_AHMADPUR_0DD4B7');
INSERT INTO master_cities (id, company_id, code, name, status, display_order, district_id, is_metro, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @p, 'MH_AUSA_52E734', 'Ausa', 'ACTIVE', 0, @d, FALSE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM DUAL WHERE @d IS NOT NULL AND NOT EXISTS (SELECT 1 FROM master_cities WHERE company_id = @p AND district_id = @d AND name = 'Ausa')
  AND NOT EXISTS (SELECT 1 FROM master_cities WHERE company_id = @p AND code = 'MH_AUSA_52E734');
INSERT INTO master_cities (id, company_id, code, name, status, display_order, district_id, is_metro, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @p, 'MH_CHAKUR_72FAC3', 'Chakur', 'ACTIVE', 0, @d, FALSE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM DUAL WHERE @d IS NOT NULL AND NOT EXISTS (SELECT 1 FROM master_cities WHERE company_id = @p AND district_id = @d AND name = 'Chakur')
  AND NOT EXISTS (SELECT 1 FROM master_cities WHERE company_id = @p AND code = 'MH_CHAKUR_72FAC3');
INSERT INTO master_cities (id, company_id, code, name, status, display_order, district_id, is_metro, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @p, 'MH_DEONI_9DDAB9', 'Deoni', 'ACTIVE', 0, @d, FALSE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM DUAL WHERE @d IS NOT NULL AND NOT EXISTS (SELECT 1 FROM master_cities WHERE company_id = @p AND district_id = @d AND name = 'Deoni')
  AND NOT EXISTS (SELECT 1 FROM master_cities WHERE company_id = @p AND code = 'MH_DEONI_9DDAB9');
INSERT INTO master_cities (id, company_id, code, name, status, display_order, district_id, is_metro, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @p, 'MH_JALKOT_100FC3', 'Jalkot', 'ACTIVE', 0, @d, FALSE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM DUAL WHERE @d IS NOT NULL AND NOT EXISTS (SELECT 1 FROM master_cities WHERE company_id = @p AND district_id = @d AND name = 'Jalkot')
  AND NOT EXISTS (SELECT 1 FROM master_cities WHERE company_id = @p AND code = 'MH_JALKOT_100FC3');
INSERT INTO master_cities (id, company_id, code, name, status, display_order, district_id, is_metro, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @p, 'MH_LATUR_E12B56', 'Latur', 'ACTIVE', 0, @d, FALSE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM DUAL WHERE @d IS NOT NULL AND NOT EXISTS (SELECT 1 FROM master_cities WHERE company_id = @p AND district_id = @d AND name = 'Latur')
  AND NOT EXISTS (SELECT 1 FROM master_cities WHERE company_id = @p AND code = 'MH_LATUR_E12B56');
INSERT INTO master_cities (id, company_id, code, name, status, display_order, district_id, is_metro, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @p, 'MH_NILANGA_5C76A6', 'Nilanga', 'ACTIVE', 0, @d, FALSE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM DUAL WHERE @d IS NOT NULL AND NOT EXISTS (SELECT 1 FROM master_cities WHERE company_id = @p AND district_id = @d AND name = 'Nilanga')
  AND NOT EXISTS (SELECT 1 FROM master_cities WHERE company_id = @p AND code = 'MH_NILANGA_5C76A6');
INSERT INTO master_cities (id, company_id, code, name, status, display_order, district_id, is_metro, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @p, 'MH_PARANDA_82D175', 'Paranda', 'ACTIVE', 0, @d, FALSE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM DUAL WHERE @d IS NOT NULL AND NOT EXISTS (SELECT 1 FROM master_cities WHERE company_id = @p AND district_id = @d AND name = 'Paranda')
  AND NOT EXISTS (SELECT 1 FROM master_cities WHERE company_id = @p AND code = 'MH_PARANDA_82D175');
INSERT INTO master_cities (id, company_id, code, name, status, display_order, district_id, is_metro, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @p, 'MH_RENAPUR_4149FA', 'Renapur', 'ACTIVE', 0, @d, FALSE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM DUAL WHERE @d IS NOT NULL AND NOT EXISTS (SELECT 1 FROM master_cities WHERE company_id = @p AND district_id = @d AND name = 'Renapur')
  AND NOT EXISTS (SELECT 1 FROM master_cities WHERE company_id = @p AND code = 'MH_RENAPUR_4149FA');
INSERT INTO master_cities (id, company_id, code, name, status, display_order, district_id, is_metro, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @p, 'MH_SHIRUR_ANANTPAL_C4D651', 'Shirur-Anantpal', 'ACTIVE', 0, @d, FALSE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM DUAL WHERE @d IS NOT NULL AND NOT EXISTS (SELECT 1 FROM master_cities WHERE company_id = @p AND district_id = @d AND name = 'Shirur-Anantpal')
  AND NOT EXISTS (SELECT 1 FROM master_cities WHERE company_id = @p AND code = 'MH_SHIRUR_ANANTPAL_C4D651');
INSERT INTO master_cities (id, company_id, code, name, status, display_order, district_id, is_metro, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @p, 'MH_UDGIR_AB740C', 'Udgir', 'ACTIVE', 0, @d, FALSE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM DUAL WHERE @d IS NOT NULL AND NOT EXISTS (SELECT 1 FROM master_cities WHERE company_id = @p AND district_id = @d AND name = 'Udgir')
  AND NOT EXISTS (SELECT 1 FROM master_cities WHERE company_id = @p AND code = 'MH_UDGIR_AB740C');

-- the pincode's primary area
UPDATE master_areas a
JOIN master_pincodes p ON p.area_id = a.id AND p.company_id = @p
JOIN (VALUES
  ROW('413502', 'Paranda'),
  ROW('413510', 'Latur'),
  ROW('413511', 'Ausa'),
  ROW('413512', 'Latur'),
  ROW('413513', 'Chakur'),
  ROW('413514', 'Ahmadpur'),
  ROW('413515', 'Ahmadpur'),
  ROW('413516', 'Ausa'),
  ROW('413517', 'Udgir'),
  ROW('413518', 'Udgir'),
  ROW('413519', 'Deoni'),
  ROW('413520', 'Ausa'),
  ROW('413521', 'Nilanga'),
  ROW('413522', 'Nilanga'),
  ROW('413523', 'Ahmadpur'),
  ROW('413524', 'Chakur'),
  ROW('413527', 'Renapur'),
  ROW('413529', 'Chakur'),
  ROW('413530', 'Nilanga'),
  ROW('413531', 'Latur'),
  ROW('413532', 'Jalkot'),
  ROW('413544', 'Shirur-Anantpal'),
  ROW('413581', 'Chakur'),
  ROW('413582', 'Latur'),
  ROW('413607', 'Nilanga'),
  ROW('431522', 'Renapur')
) m ON m.column_0 = p.code
JOIN master_cities tc ON tc.company_id = @p AND tc.district_id = @d AND tc.name = m.column_1 AND tc.deleted = FALSE
JOIN master_cities cur ON cur.id = a.city_id
SET a.city_id = tc.id, a.updated_at = UTC_TIMESTAMP(6)
WHERE @d IS NOT NULL AND a.company_id = @p AND cur.name <> m.column_1
  AND NOT EXISTS (SELECT 1 FROM (SELECT DISTINCT id, city_id, name FROM master_areas WHERE company_id = @p) x WHERE x.city_id = tc.id AND x.name = a.name AND x.id <> a.id);

-- its other post offices
UPDATE master_areas a
JOIN master_pincode_areas pa ON pa.area_id = a.id AND pa.company_id = @p
JOIN master_pincodes p ON p.id = pa.pincode_id
JOIN (VALUES
  ROW('413502', 'Paranda'),
  ROW('413510', 'Latur'),
  ROW('413511', 'Ausa'),
  ROW('413512', 'Latur'),
  ROW('413513', 'Chakur'),
  ROW('413514', 'Ahmadpur'),
  ROW('413515', 'Ahmadpur'),
  ROW('413516', 'Ausa'),
  ROW('413517', 'Udgir'),
  ROW('413518', 'Udgir'),
  ROW('413519', 'Deoni'),
  ROW('413520', 'Ausa'),
  ROW('413521', 'Nilanga'),
  ROW('413522', 'Nilanga'),
  ROW('413523', 'Ahmadpur'),
  ROW('413524', 'Chakur'),
  ROW('413527', 'Renapur'),
  ROW('413529', 'Chakur'),
  ROW('413530', 'Nilanga'),
  ROW('413531', 'Latur'),
  ROW('413532', 'Jalkot'),
  ROW('413544', 'Shirur-Anantpal'),
  ROW('413581', 'Chakur'),
  ROW('413582', 'Latur'),
  ROW('413607', 'Nilanga'),
  ROW('431522', 'Renapur')
) m ON m.column_0 = p.code
JOIN master_cities tc ON tc.company_id = @p AND tc.district_id = @d AND tc.name = m.column_1 AND tc.deleted = FALSE
JOIN master_cities cur ON cur.id = a.city_id
SET a.city_id = tc.id, a.updated_at = UTC_TIMESTAMP(6)
WHERE @d IS NOT NULL AND a.company_id = @p AND cur.name <> m.column_1
  AND NOT EXISTS (SELECT 1 FROM (SELECT DISTINCT id, city_id, name FROM master_areas WHERE company_id = @p) x WHERE x.city_id = tc.id AND x.name = a.name AND x.id <> a.id);
