-- =============================================================================
-- V139 — Zone tariff, Phase 2: rate cards, their weight slabs and their charges
--
-- A rate card prices shipments that start in one FROM zone (V138) between two dates. Only one
-- ACTIVE card per from zone may cover a date (enforced in the service). GST, the volumetric
-- divisor (NULL = actual weight only) and the rounding of the total are set on the card.
--
-- A slab is one weight band of a lane (to zone × service type × package type). FIXED: the rate
-- is the freight. PER_EXTRA_KG: the fixed slab before it, plus `rate` for each `step_kg` (1 kg
-- unless set) or part of one above that fixed slab. A lane's slabs are replaced together and
-- checked together (no overlap, no gap above 1 g, lightest slab FIXED).
--
-- A charge rule adds fuel, hamali, docket, COD, FOV, ODA… to_zone, service type and package type
-- are optional filters; per charge code the most specific matching rule is used.
--
-- Zone, service type and package type ids are validated in the service (no physical FK to the
-- master tables, like the rest of this module); the card id is a real same-module FK.
-- Permissions: the TARIFF module from V138 already covers rate cards.
-- =============================================================================

CREATE TABLE tariff_rate_cards (
    id                 BINARY(16)    NOT NULL,
    company_id         BINARY(16)    NOT NULL,
    code               VARCHAR(30)   NOT NULL,
    name               VARCHAR(100)  NOT NULL,
    from_zone_id       BINARY(16)    NOT NULL,
    effective_from     DATE          NOT NULL,
    effective_to       DATE          NULL,
    gst_percent        DECIMAL(5, 2) NOT NULL DEFAULT 18.00,
    volumetric_divisor INT           NULL,
    total_rounding     VARCHAR(15)   NOT NULL DEFAULT 'NEAREST_RUPEE',
    description        VARCHAR(255)  NULL,
    status             VARCHAR(10)   NOT NULL DEFAULT 'INACTIVE',

    created_at TIMESTAMP(6) NOT NULL, created_by BINARY(16) NULL,
    updated_at TIMESTAMP(6) NOT NULL, updated_by BINARY(16) NULL,
    deleted    BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP(6) NULL, deleted_by BINARY(16) NULL,
    version    BIGINT NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    KEY idx_trc_company_code (company_id, code),
    KEY idx_trc_company_from (company_id, from_zone_id, status),
    CONSTRAINT fk_trc_from_zone FOREIGN KEY (from_zone_id) REFERENCES tariff_zones (id) ON DELETE RESTRICT,
    CONSTRAINT ck_trc_rounding CHECK (total_rounding IN ('NONE', 'NEAREST_RUPEE', 'UP_RUPEE')),
    CONSTRAINT ck_trc_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_trc_dates CHECK (effective_to IS NULL OR effective_to >= effective_from)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Zone tariff rate cards: prices from one from zone, valid between two dates';

CREATE TABLE tariff_rate_slabs (
    id              BINARY(16)     NOT NULL,
    company_id      BINARY(16)     NOT NULL,
    rate_card_id    BINARY(16)     NOT NULL,
    to_zone_id      BINARY(16)     NOT NULL,
    service_type_id BINARY(16)     NOT NULL,
    package_type_id BINARY(16)     NOT NULL,
    weight_from_kg  DECIMAL(10, 3) NOT NULL,
    weight_to_kg    DECIMAL(10, 3) NOT NULL,
    rate_type       VARCHAR(15)    NOT NULL,
    rate            DECIMAL(12, 2) NOT NULL,
    step_kg         DECIMAL(6, 3)  NULL,

    created_at TIMESTAMP(6) NOT NULL, created_by BINARY(16) NULL,
    updated_at TIMESTAMP(6) NOT NULL, updated_by BINARY(16) NULL,
    deleted    BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP(6) NULL, deleted_by BINARY(16) NULL,
    version    BIGINT NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    KEY idx_trs_lane (company_id, rate_card_id, to_zone_id, service_type_id, package_type_id),
    KEY idx_trs_to_zone (company_id, to_zone_id),
    CONSTRAINT fk_trs_card FOREIGN KEY (rate_card_id) REFERENCES tariff_rate_cards (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trs_to_zone FOREIGN KEY (to_zone_id) REFERENCES tariff_zones (id) ON DELETE RESTRICT,
    CONSTRAINT ck_trs_type CHECK (rate_type IN ('FIXED', 'PER_EXTRA_KG')),
    CONSTRAINT ck_trs_weights CHECK (weight_from_kg >= 0 AND weight_to_kg > weight_from_kg),
    CONSTRAINT ck_trs_rate CHECK (rate >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Weight slabs of a rate card lane (to zone x service type x package type)';

CREATE TABLE tariff_charge_rules (
    id              BINARY(16)     NOT NULL,
    company_id      BINARY(16)     NOT NULL,
    rate_card_id    BINARY(16)     NOT NULL,
    charge_code     VARCHAR(30)    NOT NULL,
    charge_name     VARCHAR(100)   NOT NULL,
    to_zone_id      BINARY(16)     NULL,
    service_type_id BINARY(16)     NULL,
    package_type_id BINARY(16)     NULL,
    applies_when    VARCHAR(15)    NOT NULL DEFAULT 'ALWAYS',
    calc_method     VARCHAR(20)    NOT NULL,
    charge_value    DECIMAL(12, 3) NOT NULL,
    min_amount      DECIMAL(12, 2) NULL,
    max_amount      DECIMAL(12, 2) NULL,
    taxable         BOOLEAN        NOT NULL DEFAULT TRUE,
    display_order   INT            NOT NULL DEFAULT 0,
    status          VARCHAR(10)    NOT NULL DEFAULT 'ACTIVE',

    created_at TIMESTAMP(6) NOT NULL, created_by BINARY(16) NULL,
    updated_at TIMESTAMP(6) NOT NULL, updated_by BINARY(16) NULL,
    deleted    BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP(6) NULL, deleted_by BINARY(16) NULL,
    version    BIGINT NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    KEY idx_tcr_card (company_id, rate_card_id),
    KEY idx_tcr_to_zone (company_id, to_zone_id),
    CONSTRAINT fk_tcr_card FOREIGN KEY (rate_card_id) REFERENCES tariff_rate_cards (id) ON DELETE RESTRICT,
    CONSTRAINT fk_tcr_to_zone FOREIGN KEY (to_zone_id) REFERENCES tariff_zones (id) ON DELETE RESTRICT,
    CONSTRAINT ck_tcr_when CHECK (applies_when IN ('ALWAYS', 'COD_ONLY', 'ODA_ONLY')),
    CONSTRAINT ck_tcr_method CHECK (calc_method IN ('FLAT', 'PERCENT_OF_FREIGHT', 'PER_KG', 'PER_PIECE', 'PERCENT_OF_VALUE')),
    CONSTRAINT ck_tcr_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Charges a rate card adds to freight: fuel, hamali, docket, COD, FOV, ODA...';
