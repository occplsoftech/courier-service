-- =============================================================================
-- V174 — Regional zone names spelled exactly as the Region column of OCCPL's pincode master sheet
-- (user, 2026-11-25). Codes, and so every pincode's and tariff zone's link, are unchanged. Tariff zone
-- names are left as they are. A zone renamed in the app since V172 is left alone.
-- =============================================================================

SET @g = UNHEX('00000000000000000000000000000001');

UPDATE master_regional_zones z
JOIN (SELECT 'PUNE' code, 'Pune' old_name, 'PUNE' new_name
      UNION ALL SELECT 'X_PUNE', 'X - Pune', 'X - PUNE'
      UNION ALL SELECT 'MAHARASHTRA', 'Maharashtra', 'MAHARASHTRA'
      UNION ALL SELECT 'X_MAHARASHTRA', 'X - Maharashtra', 'X - MAHARASHTRA'
      UNION ALL SELECT 'VIDHARBHA', 'Vidharbha', 'VIDHARBHA'
      UNION ALL SELECT 'REST_OF_INDIA', 'Rest of India', 'REST OF INDIA'
      UNION ALL SELECT 'NORTH_EAST', 'North East', 'NORTH EAST') n
  ON n.code = z.code AND BINARY z.name = BINARY n.old_name
SET z.name = n.new_name, z.updated_at = UTC_TIMESTAMP(6), z.version = z.version + 1
WHERE z.company_id = @g AND z.deleted = FALSE;
