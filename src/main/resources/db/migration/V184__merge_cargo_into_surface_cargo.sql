-- =============================================================================
-- V184 — One cargo service: the company's existing cargo service becomes SURFACE CARGO
--
-- V183 renamed a service coded CARGO, but a company whose cargo service has another code (OCCPL's is CRG, named
-- "CARGO") kept it and also received a new, empty SURFACE_CARGO. Here, per company:
--   * the existing cargo service (code CRG or CARGO, or named "Cargo") is renamed SURFACE_CARGO "Surface Cargo", so
--     its rates, charges and bookings follow;
--   * the empty SURFACE_CARGO V183 added is removed first.
-- A company is left as it is when that SURFACE_CARGO row is already used by any rate, charge or shipment, or when
-- another service already holds the name "Surface Cargo". Service types have no physical FKs; the tables below are
-- every table with a service_type_id.
-- =============================================================================

CREATE TEMPORARY TABLE v184_pairs AS
SELECT crg.company_id, crg.id AS cargo_id, dup.id AS dup_id
FROM master_service_types crg
JOIN master_service_types dup
  ON dup.company_id = crg.company_id AND dup.code = 'SURFACE_CARGO' AND dup.deleted = FALSE
WHERE crg.deleted = FALSE
  AND crg.code <> 'SURFACE_CARGO'
  AND (crg.code IN ('CRG', 'CARGO') OR UPPER(crg.name) = 'CARGO');

-- Keep only companies with exactly one cargo service to merge.
CREATE TEMPORARY TABLE v184_multi AS
SELECT company_id FROM v184_pairs GROUP BY company_id HAVING COUNT(*) > 1;
DELETE p FROM v184_pairs p JOIN v184_multi m ON m.company_id = p.company_id;

-- Leave a company alone when the new SURFACE_CARGO row is already in use.
DELETE FROM v184_pairs WHERE dup_id IN (SELECT service_type_id FROM tariff_rate_slabs);
DELETE FROM v184_pairs WHERE dup_id IN (SELECT service_type_id FROM tariff_charge_rules WHERE service_type_id IS NOT NULL);
DELETE FROM v184_pairs WHERE dup_id IN (SELECT service_type_id FROM shipments WHERE service_type_id IS NOT NULL);
DELETE FROM v184_pairs WHERE dup_id IN (SELECT service_type_id FROM customer_zone_rates WHERE service_type_id IS NOT NULL);
DELETE FROM v184_pairs WHERE dup_id IN (SELECT service_type_id FROM customer_structure_rates WHERE service_type_id IS NOT NULL);
DELETE FROM v184_pairs WHERE dup_id IN (SELECT service_type_id FROM rate_master WHERE service_type_id IS NOT NULL);
DELETE FROM v184_pairs WHERE dup_id IN (SELECT service_type_id FROM charges WHERE service_type_id IS NOT NULL);
DELETE FROM v184_pairs WHERE dup_id IN (SELECT service_type_id FROM charge_settings WHERE service_type_id IS NOT NULL);

-- ...or when a third row already holds the name.
CREATE TEMPORARY TABLE v184_taken AS
SELECT DISTINCT st.company_id FROM master_service_types st
JOIN v184_pairs p ON p.company_id = st.company_id
WHERE st.name = 'Surface Cargo' AND st.id <> p.dup_id AND st.id <> p.cargo_id;
DELETE p FROM v184_pairs p JOIN v184_taken t ON t.company_id = p.company_id;

-- The empty duplicate goes (hard delete: the code and name are unique among deleted rows too).
DELETE st FROM master_service_types st JOIN v184_pairs p ON st.id = p.dup_id;

UPDATE master_service_types st
    JOIN v184_pairs p ON st.id = p.cargo_id
SET st.code = 'SURFACE_CARGO', st.name = 'Surface Cargo', st.updated_at = UTC_TIMESTAMP(6), st.version = st.version + 1;

DROP TEMPORARY TABLE v184_taken;
DROP TEMPORARY TABLE v184_multi;
DROP TEMPORARY TABLE v184_pairs;
