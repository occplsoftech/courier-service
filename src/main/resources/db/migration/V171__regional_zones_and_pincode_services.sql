-- =============================================================================
-- V171 — Regional zones, and serviceability per service on every pincode
--
-- master_regional_zones: a new global master, like the pincodes it groups. A regional zone is the
-- company's pricing region (PUNE, X - PUNE, MAHARASHTRA, X - MAHARASHTRA, VIDHARBHA, REST OF INDIA,
-- NORTH EAST in OCCPL's pincode master). It is not the direction zone (NORTH, SOUTH, EAST, WEST,
-- CENTRAL): one regional zone spans several directions and one direction holds several regions,
-- so each pincode stores both, the direction in the existing `zone` column and the region here.
--
-- master_pincodes gains, for each service network (document, surface, air):
--   *_serviceable  whether that network delivers there at all
--   *_oda          whether it delivers there out of delivery area (ODA surcharge)
--   *_tat_hours    the promised delivery time in hours
-- All nullable: NULL means "no per-service data", and booking falls back to the pincode's own
-- serviceable / oda_applicable flags, exactly as before. V172 fills them from OCCPL's sheet.
--
-- master_service_types gains `network` (SURFACE or AIR, NULL = SURFACE): which of the pincode's
-- surface or air columns a non-document booking on that service is checked against. Document
-- bookings always use the document columns.
--
-- tariff_zone_locations gains regional_zone_id and a wider scope column for the new
-- REGIONAL_ZONE scope, so a tariff zone can take a whole regional zone the way it takes a state.
-- =============================================================================

CREATE TABLE master_regional_zones (
    id            BINARY(16)   NOT NULL,
    company_id    BINARY(16)   NOT NULL,

    code          VARCHAR(50)  NOT NULL,
    name          VARCHAR(150) NOT NULL,
    description   VARCHAR(500) NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    display_order INT          NOT NULL DEFAULT 0,

    created_at TIMESTAMP(6) NOT NULL, created_by BINARY(16) NULL,
    updated_at TIMESTAMP(6) NOT NULL, updated_by BINARY(16) NULL,
    deleted    BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP(6) NULL, deleted_by BINARY(16) NULL,
    version    BIGINT NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    UNIQUE KEY uk_master_regional_zones_code (company_id, code),
    KEY idx_master_regional_zones_status (company_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

ALTER TABLE master_pincodes
    ADD COLUMN regional_zone_id    BINARY(16) NULL AFTER zone,
    ADD COLUMN doc_serviceable     BOOLEAN    NULL AFTER oda_applicable,
    ADD COLUMN doc_oda             BOOLEAN    NULL AFTER doc_serviceable,
    ADD COLUMN doc_tat_hours       INT        NULL AFTER doc_oda,
    ADD COLUMN surface_serviceable BOOLEAN    NULL AFTER doc_tat_hours,
    ADD COLUMN surface_oda         BOOLEAN    NULL AFTER surface_serviceable,
    ADD COLUMN surface_tat_hours   INT        NULL AFTER surface_oda,
    ADD COLUMN air_serviceable     BOOLEAN    NULL AFTER surface_tat_hours,
    ADD COLUMN air_oda             BOOLEAN    NULL AFTER air_serviceable,
    ADD COLUMN air_tat_hours       INT        NULL AFTER air_oda,
    ADD KEY idx_master_pincodes_regional_zone (company_id, regional_zone_id);

ALTER TABLE master_service_types
    ADD COLUMN network VARCHAR(10) NULL;

-- The air service every company was seeded with flies; everything else stays surface.
UPDATE master_service_types SET network = 'AIR' WHERE code = 'AIR' AND deleted = FALSE;

ALTER TABLE tariff_zone_locations
    MODIFY COLUMN scope VARCHAR(20) NOT NULL,
    ADD COLUMN regional_zone_id BINARY(16) NULL,
    ADD KEY idx_tariff_zone_locations_regional_zone (company_id, regional_zone_id);
