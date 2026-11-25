-- =============================================================================
-- V177 — A business customer's rate structure (user, 2026-11-25): the company's printed rate card
-- (DOMESTIC AIR COURIER per destination group; DOMESTIC CARGO per origin region x destination group, by surface or
-- by air; a minimum chargeable weight) imported as it is, one row per rate.
--
--   service_type_id     which service the rate prices (air courier, cargo by surface, cargo by air: picked at import)
--   origin_region       where the shipment starts: NORTH, SOUTH, EAST, WEST, NORTH_EAST; NULL = any origin (air courier)
--   destination_group   WITHIN_STATE, REST_OF_INDIA, NORTH_EAST (cargo); WEST, METROS, REST_OF_INDIA,
--                       EAST_NORTH_EAST (air courier)
--   rate_per_kg         per chargeable kg
--   min_chargeable_kg   the chargeable weight is at least this (cargo: 20)
--
-- Read at booking for a customer on business rates, before their pricing zones (see CustomerRateStructureService).
-- =============================================================================

CREATE TABLE customer_structure_rates (
    id                 BINARY(16)     NOT NULL,
    company_id         BINARY(16)     NOT NULL,
    customer_id        BINARY(16)     NOT NULL,
    service_type_id    BINARY(16)     NOT NULL,
    origin_region      VARCHAR(20)    NULL,
    destination_group  VARCHAR(20)    NOT NULL,
    rate_per_kg        DECIMAL(12, 2) NOT NULL,
    min_chargeable_kg  DECIMAL(10, 3) NOT NULL DEFAULT 0,

    created_at TIMESTAMP(6) NOT NULL, created_by BINARY(16) NULL,
    updated_at TIMESTAMP(6) NOT NULL, updated_by BINARY(16) NULL,
    deleted    BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP(6) NULL, deleted_by BINARY(16) NULL,
    version    BIGINT NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    KEY idx_csr_customer_service (company_id, customer_id, service_type_id),
    CONSTRAINT ck_csr_rate CHECK (rate_per_kg >= 0),
    CONSTRAINT ck_csr_min CHECK (min_chargeable_kg >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Business customer rate structure: per-kg rates by origin region and destination group';
