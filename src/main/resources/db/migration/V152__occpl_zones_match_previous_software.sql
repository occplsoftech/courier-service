-- =============================================================================
-- V152 — OCCPL's PUNE, EXPUNE and METRO zones as in the company's previous software
-- ("Edit Domestic Zone" screens, pasted by the user on 2026-10-30; choices below confirmed by the user).
--
-- METRO: New Delhi, Hyderabad, Bangalore, Chennai. Mumbai (V142/V143 city, V150 district) and Kolkata
--   (V150 district) come out: Mumbai prices as MAHARASHTRA, Kolkata as EAST. Delhi stays whole.
-- PUNE: Pune, Pimpri, Chinchwad. The "Pune City" taluka already holds all three; Pimple Gurav (411061)
--   and Wakad / Hinjewadi (411057), outside that taluka, are added as pincodes.
-- EXPUNE: Maval and the towns Chakan, Talegaon, Alandi, Dehu Road, Pirangut, Loni, Talegaon Dhamdhere,
--   Ranjangaon, Fursungi, not the whole district: the Pune district place (V142/V143) comes out, Maval
--   taluka goes in as a city, each town as its pincode(s), plus 411025 (Khadakwasla villages). The rest
--   of Pune district (Baramati, Junnar, Indapur…) prices as MAHARASHTRA, whose rates equal EXPUNE's.
--
-- Only companies OCCPL and OCCPL_COURIER. Removing is a soft delete, as Zone Tariff -> Zones does; a
-- place is added only when no live TO/BOTH zone of the company holds it already. Running it twice
-- changes nothing.
-- =============================================================================

SET @g = UNHEX('00000000000000000000000000000001');

-- METRO: Mumbai and Kolkata out.
UPDATE tariff_zone_locations l
JOIN tariff_zones z ON z.id = l.zone_id AND z.code = 'METRO'
JOIN companies co ON co.company_id = z.company_id AND co.company_code IN ('OCCPL', 'OCCPL_COURIER') AND co.deleted = FALSE
LEFT JOIN master_cities c ON l.scope = 'CITY' AND c.id = l.city_id
LEFT JOIN master_districts d ON d.id = COALESCE(l.district_id, c.district_id)
LEFT JOIN master_states s ON s.id = d.state_id
SET l.deleted = TRUE, l.deleted_at = UTC_TIMESTAMP(6), l.updated_at = UTC_TIMESTAMP(6)
WHERE l.deleted = FALSE
  AND ((l.scope = 'CITY' AND c.name = 'Mumbai' AND s.name = 'Maharashtra')
    OR (l.scope = 'DISTRICT' AND d.name IN ('Mumbai', 'Mumbai Suburban') AND s.name = 'Maharashtra')
    OR (l.scope = 'DISTRICT' AND d.name = 'Kolkata' AND s.name = 'West Bengal'));

-- EXPUNE: the whole Pune district out.
UPDATE tariff_zone_locations l
JOIN tariff_zones z ON z.id = l.zone_id AND z.code = 'EXPUNE'
JOIN companies co ON co.company_id = z.company_id AND co.company_code IN ('OCCPL', 'OCCPL_COURIER') AND co.deleted = FALSE
JOIN master_districts d ON d.id = l.district_id AND d.name = 'Pune'
JOIN master_states s ON s.id = d.state_id AND s.name = 'Maharashtra'
SET l.deleted = TRUE, l.deleted_at = UTC_TIMESTAMP(6), l.updated_at = UTC_TIMESTAMP(6)
WHERE l.deleted = FALSE AND l.scope = 'DISTRICT';

-- EXPUNE: Maval taluka (the masters spell it Mawal or Maval).
INSERT INTO tariff_zone_locations (id, company_id, zone_id, scope, state_id, district_id, city_id, pincode, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), z.company_id, z.id, 'CITY', s.id, d.id, c.id, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM tariff_zones z
JOIN companies co ON co.company_id = z.company_id AND co.company_code IN ('OCCPL', 'OCCPL_COURIER') AND co.deleted = FALSE
JOIN master_cities c ON c.company_id = @g AND c.deleted = FALSE AND c.name IN ('Mawal', 'Maval')
JOIN master_districts d ON d.id = c.district_id AND d.name = 'Pune'
JOIN master_states s ON s.id = d.state_id AND s.name = 'Maharashtra'
WHERE z.code = 'EXPUNE' AND z.deleted = FALSE AND z.zone_usage IN ('TO', 'BOTH')
  AND NOT EXISTS (SELECT 1 FROM tariff_zone_locations l JOIN tariff_zones oz ON oz.id = l.zone_id AND oz.deleted = FALSE AND oz.zone_usage IN ('TO', 'BOTH')
                  WHERE l.company_id = z.company_id AND l.deleted = FALSE AND l.scope = 'CITY' AND l.city_id = c.id);

-- Town pincodes: EXPUNE's towns and 411025; PUNE's 411057 and 411061.
INSERT INTO tariff_zone_locations (id, company_id, zone_id, scope, state_id, district_id, city_id, pincode, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), z.company_id, z.id, 'PINCODE', d.state_id, c.district_id, c.id, v.pin, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT * FROM (VALUES
        ROW('EXPUNE', '410501'),  -- Chakan
        ROW('EXPUNE', '412105'),  -- Alandi Devachi
        ROW('EXPUNE', '412101'),  -- Dehu Road
        ROW('EXPUNE', '412115'),  -- Pirangut
        ROW('EXPUNE', '412201'),  -- Loni Kalbhor
        ROW('EXPUNE', '412208'),  -- Talegaon Dhamdhere
        ROW('EXPUNE', '412209'),  -- Ranjangaon Ganpati
        ROW('EXPUNE', '412220'),  -- MIDC Ranjangaon Ganpati
        ROW('EXPUNE', '412308'),  -- Fursungi
        ROW('EXPUNE', '411025'),  -- Khadakwasla villages (Donje, Khanapur, Osade)
        ROW('PUNE', '411057'),    -- Wakad / Hinjewadi
        ROW('PUNE', '411061')     -- Pimple Gurav
     ) AS t(zone, pin)) v
JOIN tariff_zones z ON z.code = v.zone AND z.deleted = FALSE AND z.zone_usage IN ('TO', 'BOTH')
JOIN companies co ON co.company_id = z.company_id AND co.company_code IN ('OCCPL', 'OCCPL_COURIER') AND co.deleted = FALSE
JOIN master_pincodes p ON p.company_id = @g AND p.code = v.pin AND p.deleted = FALSE
JOIN master_areas a ON a.id = p.area_id
JOIN master_cities c ON c.id = a.city_id
JOIN master_districts d ON d.id = c.district_id
WHERE NOT EXISTS (SELECT 1 FROM tariff_zone_locations l JOIN tariff_zones oz ON oz.id = l.zone_id AND oz.deleted = FALSE AND oz.zone_usage IN ('TO', 'BOTH')
                  WHERE l.company_id = z.company_id AND l.deleted = FALSE AND l.scope = 'PINCODE' AND l.pincode = v.pin);
