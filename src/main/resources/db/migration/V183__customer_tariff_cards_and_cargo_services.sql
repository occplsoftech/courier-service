-- =============================================================================
-- V183 — A business customer's own zone-tariff rate card; Per Box slabs; the five service types
--
-- 1. tariff_rate_cards.customer_id: NULL = the company's card (as before); set = that business customer's own card,
--    uploaded from their row (old-software rate sheet). At booking for that customer, a lane on their card is used
--    before the company's card for the same from zone.
-- 2. tariff_rate_slabs.rate_type gains PER_BOX: freight = rate x number of boxes (pieces), whatever the weight.
-- 3. Service types, for every company that has service types: CARGO becomes SURFACE CARGO (same row, so its rates
--    and bookings follow); AIR CARGO is added (flies, network AIR); SURFACE CARGO is added where there was no CARGO.
-- 4. Package type LAPTOP (not a document) for the same companies: the sheets' "LAPTOP-SURFACE" Per Box rates.
-- =============================================================================

ALTER TABLE tariff_rate_cards
    ADD COLUMN customer_id BINARY(16) NULL AFTER from_zone_id,
    ADD KEY idx_trc_company_customer (company_id, customer_id, from_zone_id, status);

ALTER TABLE tariff_rate_slabs DROP CHECK ck_trs_type;
ALTER TABLE tariff_rate_slabs
    ADD CONSTRAINT ck_trs_type CHECK (rate_type IN ('FIXED', 'PER_EXTRA_KG', 'PER_BOX'));

-- Companies with service types of their own.
CREATE TEMPORARY TABLE v183_companies AS
SELECT DISTINCT company_id FROM master_service_types WHERE deleted = FALSE;

-- CARGO -> SURFACE CARGO, unless the company already has a SURFACE_CARGO code or a "Surface Cargo" name.
CREATE TEMPORARY TABLE v183_taken AS
SELECT DISTINCT company_id FROM master_service_types
WHERE deleted = FALSE AND (code = 'SURFACE_CARGO' OR name = 'Surface Cargo');

UPDATE master_service_types st
    LEFT JOIN v183_taken t ON t.company_id = st.company_id
SET st.code = 'SURFACE_CARGO', st.name = 'Surface Cargo', st.updated_at = UTC_TIMESTAMP(6), st.version = st.version + 1
WHERE st.code = 'CARGO' AND st.deleted = FALSE AND t.company_id IS NULL;

-- SURFACE CARGO where the company had no CARGO.
INSERT INTO master_service_types (id, company_id, code, name, status, display_order, delivery_days, network,
                                  created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), c.company_id, 'SURFACE_CARGO', 'Surface Cargo', 'ACTIVE', 0, 6, 'SURFACE',
       UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM v183_companies c
WHERE NOT EXISTS (SELECT 1 FROM master_service_types s
                  WHERE s.company_id = c.company_id AND (s.code = 'SURFACE_CARGO' OR s.name = 'Surface Cargo'));

-- AIR CARGO, everywhere.
INSERT INTO master_service_types (id, company_id, code, name, status, display_order, delivery_days, network,
                                  created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), c.company_id, 'AIR_CARGO', 'Air Cargo', 'ACTIVE', 0, 3, 'AIR',
       UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM v183_companies c
WHERE NOT EXISTS (SELECT 1 FROM master_service_types s
                  WHERE s.company_id = c.company_id AND (s.code = 'AIR_CARGO' OR s.name = 'Air Cargo'));

-- LAPTOP package type.
INSERT INTO master_package_types (id, company_id, code, name, status, display_order, is_document, fragile_by_default,
                                  created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), c.company_id, 'LAPTOP', 'Laptop', 'ACTIVE', 0, FALSE, TRUE,
       UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM v183_companies c
WHERE NOT EXISTS (SELECT 1 FROM master_package_types p
                  WHERE p.company_id = c.company_id AND (p.code = 'LAPTOP' OR p.name = 'Laptop'));

DROP TEMPORARY TABLE v183_taken;
DROP TEMPORARY TABLE v183_companies;
