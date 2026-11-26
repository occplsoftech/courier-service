-- =============================================================================
-- V178 — Co-Loaders, and the outscan details of the earlier OCCPL portal.
--
-- co_loaders: outside transporters / agents a branch hands parcels to (SELF VEHICLE, NIGHT BIRD,
--   S T LOGISTICS ...), one list per company (Masters → Co-Loaders).
-- manifests.co_loader_id / forwarding_number / bags_count: the Scan → Assign to Branch outscan's
--   Co-Loader, Forwarding Number and Bags Count. A trip may now leave with a co-loader and no
--   vehicle / driver (both columns were already nullable).
-- delivery_assignment.co_loader_id: Assign to Delivery may hand a local delivery to a co-loader
--   instead of a staff delivery boy, so delivery_user_id becomes nullable (exactly one of the two).
-- =============================================================================

CREATE TABLE co_loaders (
    id             BINARY(16)   NOT NULL,
    company_id     BINARY(16)   NOT NULL,
    name           VARCHAR(150) NOT NULL,
    contact_person VARCHAR(150) NULL,
    phone          VARCHAR(20)  NULL,
    notes          VARCHAR(500) NULL,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,

    created_at TIMESTAMP(6) NOT NULL, created_by BINARY(16) NULL,
    updated_at TIMESTAMP(6) NOT NULL, updated_by BINARY(16) NULL,
    deleted    BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP(6) NULL, deleted_by BINARY(16) NULL,
    version    BIGINT NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    UNIQUE KEY uk_co_loaders_company_name (company_id, name),
    KEY idx_co_loaders_company_active (company_id, active)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Outside transporters / agents parcels are handed to at outscan';

ALTER TABLE manifests
    ADD COLUMN co_loader_id      BINARY(16)  NULL AFTER driver_user_id,
    ADD COLUMN forwarding_number VARCHAR(60) NULL AFTER co_loader_id,
    ADD COLUMN bags_count        INT         NULL AFTER forwarding_number;

ALTER TABLE delivery_assignment
    MODIFY COLUMN delivery_user_id BINARY(16) NULL,
    ADD COLUMN co_loader_id BINARY(16) NULL AFTER delivery_user_id;
