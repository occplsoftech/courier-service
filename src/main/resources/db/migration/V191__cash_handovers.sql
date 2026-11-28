-- =============================================================================
-- V191 — Cash handovers (User Performance Report)
--
-- Branch staff collect cash at the counter (Paid bookings paid in CASH) and on delivery
-- (To-Pay and COD). A branch manager, the branch's ACCOUNTS desk or a company admin records
-- each time a user hands that cash over. The User Performance Report then shows, per user,
-- cash collected, handed over, and outstanding.
--
-- A handover is money changing hands inside the branch, not a wallet movement: the branch
-- wallet (wallets / wallet_transactions) is untouched. Rows are never edited; a mistake is
-- voided (with a reason) and recorded again.
--
-- cash_handover_settings.tracking_from: the day outstanding cash starts counting. Seeded with
-- today for every existing company, so cash collected before this feature existed (and never
-- recorded as handed over) does not show up as outstanding. A company without a row (created
-- after this migration) counts from the day it was created.
-- =============================================================================

CREATE TABLE cash_handovers (
    id         BINARY(16) NOT NULL,
    company_id BINARY(16) NOT NULL,

    branch_id BINARY(16) NOT NULL,
    -- The user who handed the cash over.
    user_id   BINARY(16) NOT NULL,

    amount        DECIMAL(19, 4) NOT NULL,
    -- CASH | BANK_DEPOSIT | UPI
    mode          VARCHAR(20)    NOT NULL,
    reference     VARCHAR(100)   NULL,
    remarks       VARCHAR(500)   NULL,
    -- The business day the cash was handed over (IST).
    handover_date DATE           NOT NULL,

    -- Who received it, and when it was recorded.
    received_by BINARY(16)   NOT NULL,
    received_at TIMESTAMP(6) NOT NULL,

    voided      BOOLEAN      NOT NULL DEFAULT FALSE,
    voided_by   BINARY(16)   NULL,
    voided_at   TIMESTAMP(6) NULL,
    void_reason VARCHAR(500) NULL,

    created_at TIMESTAMP(6) NOT NULL, created_by BINARY(16) NULL,
    updated_at TIMESTAMP(6) NOT NULL, updated_by BINARY(16) NULL,
    deleted    BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP(6) NULL, deleted_by BINARY(16) NULL,
    version    BIGINT NOT NULL DEFAULT 0,

    PRIMARY KEY (id),

    KEY idx_cash_handover_branch_date (company_id, branch_id, handover_date),
    KEY idx_cash_handover_user_date (company_id, user_id, handover_date),

    CONSTRAINT fk_cash_handover_branch FOREIGN KEY (branch_id)
        REFERENCES branches (id) ON DELETE RESTRICT ON UPDATE RESTRICT,

    CONSTRAINT ck_cash_handover_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_cash_handover_mode CHECK (mode IN ('CASH', 'BANK_DEPOSIT', 'UPI')),
    -- A voided handover always carries who, when and why; a live one carries none of them.
    CONSTRAINT ck_cash_handover_void CHECK (
        (voided = FALSE AND voided_by IS NULL AND voided_at IS NULL AND void_reason IS NULL)
        OR (voided = TRUE AND voided_by IS NOT NULL AND voided_at IS NOT NULL AND void_reason IS NOT NULL)
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Cash a branch user handed over to the branch manager / accounts desk';

CREATE TABLE cash_handover_settings (
    company_id    BINARY(16) NOT NULL,
    tracking_from DATE       NOT NULL,
    PRIMARY KEY (company_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Per company: the day outstanding cash per user starts counting';

INSERT INTO cash_handover_settings (company_id, tracking_from)
SELECT company_id, CURRENT_DATE FROM companies;
