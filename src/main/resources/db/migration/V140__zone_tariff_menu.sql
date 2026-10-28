-- =============================================================================
-- V140 — Zone tariff menu: the "Zone Tariff" group (Zones, Rate Cards, Calculator)
--
-- The screens arrived in courier-web; these rows list them in the menu tree the
-- user-permission screens read (V84). All three leaves use the TARIFF permission
-- module from V138. Skipped if the group already exists.
-- =============================================================================

SET @zone_tariff = UNHEX(REPLACE(UUID(), '-', ''));

INSERT INTO menu_items (id, parent_id, code, title, icon, route, permission_module,
                        display_order, is_active, is_system, created_at, updated_at, deleted, version)
SELECT @zone_tariff, NULL, 'zone-tariff', 'Zone Tariff', 'travel_explore', NULL, NULL,
       26, TRUE, TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
WHERE NOT EXISTS (SELECT 1 FROM menu_items x WHERE x.code = 'zone-tariff');

INSERT INTO menu_items (id, parent_id, code, title, icon, route, permission_module,
                        display_order, is_active, is_system, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), p.id, d.code, d.title, d.icon, d.route, 'TARIFF',
       d.display_order, TRUE, TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM menu_items p
JOIN (SELECT 'tariff-zones' AS code, 'Zones' AS title, 'map' AS icon, '/zone-tariff/zones' AS route, 1 AS display_order
      UNION ALL SELECT 'tariff-rate-cards', 'Rate Cards', 'request_quote', '/zone-tariff/rate-cards', 2
      UNION ALL SELECT 'tariff-calculator', 'Calculator', 'calculate', '/zone-tariff/calculator', 3) d
WHERE p.code = 'zone-tariff' AND p.parent_id IS NULL AND p.deleted = FALSE
  AND NOT EXISTS (SELECT 1 FROM menu_items x WHERE x.code = d.code);
