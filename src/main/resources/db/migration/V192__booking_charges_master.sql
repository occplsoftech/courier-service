-- =============================================================================
-- V192 — Charges Master: the booking charges a clerk can add
--
-- Freight, ODA, fuel surcharge and FOV are calculated (rate card, pincode). These are the others,
-- set per company and, where a business customer pays differently, per customer:
--   HANDLING, PICKUP, DELIVERY, AWB, GREEN_TAX, REVERSE_PICKUP, COD, OTHER
-- calc_method: FIXED (per shipment), PER_KG (per chargeable kg, rounded up), PER_PIECE,
--              PERCENT_OF_FREIGHT, PERCENT_OF_COD (COD only: % of the COD amount)
-- min_amount:  the least it comes to (COD: % or the minimum, whichever is higher).
-- customer_id NULL = the company's own value; set = that business customer's override.
-- The clerk ticks which apply on the booking; each ticked charge is kept in shipment_booking_charges.
--
-- Seed: every company gets its eight rows, all inactive at 0, except COD = 2% with a minimum of 100,
-- active — exactly what bookings charged for COD before this migration.
-- Nothing is ever physically removed: a customer's override is reset by soft delete.
-- =============================================================================

CREATE TABLE booking_charge_rates (
    id          BINARY(16)     NOT NULL,
    company_id  BINARY(16)     NOT NULL,
    customer_id BINARY(16)     NULL,
    charge_code VARCHAR(20)    NOT NULL,
    calc_method VARCHAR(20)    NOT NULL,
    charge_value DECIMAL(12, 3) NOT NULL,
    min_amount  DECIMAL(12, 2) NULL,
    active      BOOLEAN        NOT NULL DEFAULT FALSE,

    created_at TIMESTAMP(6) NOT NULL, created_by BINARY(16) NULL,
    updated_at TIMESTAMP(6) NOT NULL, updated_by BINARY(16) NULL,
    deleted    BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP(6) NULL, deleted_by BINARY(16) NULL,
    version    BIGINT NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    KEY idx_booking_charge_rates_company (company_id, customer_id, charge_code),

    CONSTRAINT ck_booking_charge_code CHECK (charge_code IN
        ('HANDLING', 'PICKUP', 'DELIVERY', 'AWB', 'GREEN_TAX', 'REVERSE_PICKUP', 'COD', 'OTHER')),
    CONSTRAINT ck_booking_charge_calc CHECK (calc_method IN
        ('FIXED', 'PER_KG', 'PER_PIECE', 'PERCENT_OF_FREIGHT', 'PERCENT_OF_COD')),
    CONSTRAINT ck_booking_charge_cod CHECK ((charge_code = 'COD') = (calc_method = 'PERCENT_OF_COD')),
    CONSTRAINT ck_booking_charge_value CHECK (charge_value >= 0),
    CONSTRAINT ck_booking_charge_min CHECK (min_amount IS NULL OR min_amount >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Charges Master: booking charges per company, and business customers'' overrides';

CREATE TABLE shipment_booking_charges (
    id          BINARY(16)     NOT NULL,
    company_id  BINARY(16)     NOT NULL,
    shipment_id BINARY(16)     NOT NULL,
    charge_code VARCHAR(20)    NOT NULL,
    charge_name VARCHAR(60)    NOT NULL,
    amount      DECIMAL(19, 4) NOT NULL,

    created_at TIMESTAMP(6) NOT NULL, created_by BINARY(16) NULL,
    updated_at TIMESTAMP(6) NOT NULL, updated_by BINARY(16) NULL,
    deleted    BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP(6) NULL, deleted_by BINARY(16) NULL,
    version    BIGINT NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    KEY idx_shipment_booking_charges_shipment (company_id, shipment_id),
    CONSTRAINT fk_shipment_booking_charge_shipment FOREIGN KEY (shipment_id)
        REFERENCES shipments (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_shipment_booking_charge_amount CHECK (amount >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'The Charges Master charges a clerk added to one booking, with their amounts';

INSERT INTO booking_charge_rates (id, company_id, customer_id, charge_code, calc_method, charge_value, min_amount, active,
                                  created_at, updated_at)
SELECT UNHEX(REPLACE(UUID(), '-', '')), c.company_id, NULL, k.code,
       IF(k.code = 'COD', 'PERCENT_OF_COD', 'FIXED'),
       IF(k.code = 'COD', 2, 0),
       IF(k.code = 'COD', 100, NULL),
       k.code = 'COD',
       CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
FROM companies c
CROSS JOIN (SELECT 'HANDLING' AS code UNION ALL SELECT 'PICKUP' UNION ALL SELECT 'DELIVERY' UNION ALL SELECT 'AWB'
            UNION ALL SELECT 'GREEN_TAX' UNION ALL SELECT 'REVERSE_PICKUP' UNION ALL SELECT 'COD' UNION ALL SELECT 'OTHER') k;
