-- =============================================================================
-- V166 — Serviceability per pincode area, and the unserviceable-requests report.
--
-- master_pincode_areas.serviceable: an area of a pincode can be switched off on its own
--   (the pincode's own master_pincodes.serviceable still switches off all of it).
-- unserviceable_requests: one row per company, pincode and area (area_key '' when no area
--   was named, e.g. the customer portal) counting how often a booking asked for a
--   destination OCCPL does not serve, from staff booking and from the portal; cleared to
--   "turned on" when an admin switches the area on from the report.
-- =============================================================================

ALTER TABLE master_pincode_areas
    ADD COLUMN serviceable BOOLEAN NOT NULL DEFAULT TRUE AFTER is_primary;

CREATE TABLE unserviceable_requests (
    id                BINARY(16)   NOT NULL,
    company_id        BINARY(16)   NOT NULL,
    pincode_code      VARCHAR(6)   NOT NULL,
    area_key          VARCHAR(36)  NOT NULL DEFAULT '',
    pincode_id        BINARY(16)   NULL,
    area_id           BINARY(16)   NULL,
    area_name         VARCHAR(150) NULL,
    city_name         VARCHAR(150) NULL,
    reason            VARCHAR(30)  NOT NULL COMMENT 'PINCODE_OFF, AREA_OFF or NOT_ON_FILE (latest)',
    staff_count       INT          NOT NULL DEFAULT 0,
    portal_count      INT          NOT NULL DEFAULT 0,
    first_requested_at DATETIME(6) NOT NULL,
    last_requested_at  DATETIME(6) NOT NULL,
    last_branch_id    BINARY(16)   NULL,
    last_user_id      BINARY(16)   NULL,
    turned_on_at      DATETIME(6)  NULL,
    turned_on_by      BINARY(16)   NULL,

    created_at TIMESTAMP(6) NOT NULL, created_by BINARY(16) NULL,
    updated_at TIMESTAMP(6) NOT NULL, updated_by BINARY(16) NULL,
    deleted    BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP(6) NULL, deleted_by BINARY(16) NULL,
    version    BIGINT NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    UNIQUE KEY uk_unserviceable_requests (company_id, pincode_code, area_key),
    KEY idx_unserviceable_requests_last (company_id, last_requested_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'How often bookings asked for a destination OCCPL does not serve';
