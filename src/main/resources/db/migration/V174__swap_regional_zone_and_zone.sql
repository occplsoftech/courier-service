-- =============================================================================
-- V174 — Regional zones and pincode zones the right way round (user, 2026-11-25)
--
-- In OCCPL's pincode master sheet the "zone" column (NORTH, SOUTH, EAST, WEST, CENTRAL) is the regional zone,
-- and the "Region" column (PUNE, X - PUNE, MAHARASHTRA, X - MAHARASHTRA, VIDHARBHA, REST OF INDIA, NORTH EAST) is
-- the pincode's zone. V172 loaded them the other way round; this swaps them:
--   * regional zones NORTH, SOUTH, EAST, WEST, CENTRAL are created;
--   * every pincode V172 linked gets regional zone = its former zone value, and zone = its former regional zone's
--     name as written in the sheet;
--   * the seven V172 regional zones are deleted (soft), and any Zone Tariff place that pointed at one of them
--     (V173 added none; one added in the app would) is removed with them.
-- Zone Tariff zones PUNE, X_PUNE … NORTH_EAST and their cities and pincodes are not touched.
-- =============================================================================

SET @g = UNHEX('00000000000000000000000000000001');

-- New regional zones, from the sheet's zone column.
INSERT INTO master_regional_zones (id, company_id, code, name, description, status, display_order, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @g, n.code, n.code, 'From the OCCPL pincode master (V174)', 'ACTIVE', n.ord,
       UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT 'NORTH' code, 0 ord UNION ALL SELECT 'SOUTH', 1 UNION ALL SELECT 'EAST', 2
      UNION ALL SELECT 'WEST', 3 UNION ALL SELECT 'CENTRAL', 4) n
WHERE NOT EXISTS (SELECT 1 FROM master_regional_zones z WHERE z.company_id = @g AND z.code = n.code AND z.deleted = FALSE);

-- The seven V172 regional zones, with their names as written in the sheet's Region column.
DROP TABLE IF EXISTS tmp_old_region;
CREATE TABLE tmp_old_region (code VARCHAR(50) NOT NULL PRIMARY KEY, sheet_name VARCHAR(20) NOT NULL, id BINARY(16) NULL)
    ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
INSERT INTO tmp_old_region (code, sheet_name) VALUES
    ('PUNE', 'PUNE'), ('X_PUNE', 'X - PUNE'), ('MAHARASHTRA', 'MAHARASHTRA'), ('X_MAHARASHTRA', 'X - MAHARASHTRA'),
    ('VIDHARBHA', 'VIDHARBHA'), ('REST_OF_INDIA', 'REST OF INDIA'), ('NORTH_EAST', 'NORTH EAST');
UPDATE tmp_old_region o
JOIN master_regional_zones z ON z.company_id = @g AND z.code = o.code AND z.deleted = FALSE
SET o.id = z.id;

-- Each linked pincode: its new regional zone (by its current zone value) and its new zone (the old region's name).
DROP TABLE IF EXISTS tmp_pin_swap;
CREATE TABLE tmp_pin_swap (pincode_id BINARY(16) NOT NULL PRIMARY KEY, new_zone VARCHAR(20) NOT NULL, new_regional_zone_id BINARY(16) NULL)
    ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
INSERT INTO tmp_pin_swap (pincode_id, new_zone, new_regional_zone_id)
SELECT p.id, o.sheet_name, nz.id
FROM master_pincodes p
JOIN tmp_old_region o ON o.id = p.regional_zone_id
LEFT JOIN master_regional_zones nz ON nz.company_id = @g AND nz.code = p.zone AND nz.deleted = FALSE
    AND nz.code IN ('NORTH', 'SOUTH', 'EAST', 'WEST', 'CENTRAL')
WHERE p.company_id = @g AND p.deleted = FALSE;

UPDATE master_pincodes p
JOIN tmp_pin_swap s ON s.pincode_id = p.id
SET p.zone = s.new_zone, p.regional_zone_id = s.new_regional_zone_id,
    p.updated_at = UTC_TIMESTAMP(6), p.version = p.version + 1;

-- Zone Tariff places pointing at an old regional zone go with it.
UPDATE tariff_zone_locations l
JOIN tmp_old_region o ON o.id = l.regional_zone_id
SET l.deleted = TRUE, l.deleted_at = UTC_TIMESTAMP(6), l.updated_at = UTC_TIMESTAMP(6)
WHERE l.scope = 'REGIONAL_ZONE' AND l.deleted = FALSE;

UPDATE master_regional_zones z
JOIN tmp_old_region o ON o.id = z.id
SET z.deleted = TRUE, z.deleted_at = UTC_TIMESTAMP(6), z.updated_at = UTC_TIMESTAMP(6), z.version = z.version + 1;

DROP TABLE tmp_pin_swap;
DROP TABLE tmp_old_region;
