-- =============================================================================
-- V191 — Cash handovers (User Performance Report)
--
-- Branch staff collect cash at the counter (Paid bookings paid in CASH) and on delivery
-- (To-Pay and COD). To hand it over, the user who holds the cash submits a handover with a
-- proof image (deposit slip, UPI screenshot, photo of the counted cash). A branch manager, the
-- branch's ACCOUNTS desk or a company admin looks at the proof and approves or rejects it.
-- Only an APPROVED handover reduces the user's outstanding cash on the User Performance Report.
--
--   PENDING  --approve-->  APPROVED  --void (mistake found later)-->  VOIDED
--      \------reject---->  REJECTED
--
-- A handover is money changing hands inside the branch, not a wallet movement: the branch
-- wallet (wallets / wallet_transactions) is untouched. Amount, proof and the rest are never
-- edited; a wrong one is rejected (or voided) and submitted again.
--
-- cash_handover_settings.tracking_from: the day outstanding cash starts counting. Seeded with
-- today for every existing company, so cash collected before this feature existed (and never
-- handed over through it) does not show up as outstanding. A company without a row (created
-- after this migration) counts from the day it was created.
-- =============================================================================

CREATE TABLE cash_handovers (
    id         BINARY(16) NOT NULL,
    company_id BINARY(16) NOT NULL,

    branch_id BINARY(16) NOT NULL,
    -- The user who hands the cash over (and submitted the handover).
    user_id   BINARY(16) NOT NULL,

    amount          DECIMAL(19, 4) NOT NULL,
    -- CASH | BANK_DEPOSIT | UPI
    mode            VARCHAR(20)    NOT NULL,
    reference       VARCHAR(100)   NULL,
    remarks         VARCHAR(500)   NULL,
    -- The business day (IST) the cash was handed over / deposited.
    handover_date   DATE           NOT NULL,
    proof_image_url VARCHAR(500)   NOT NULL,
    submitted_at    TIMESTAMP(6)   NOT NULL,

    -- PENDING | APPROVED | REJECTED | VOIDED
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',

    -- Who approved or rejected it, when, and their note (required on a rejection).
    decided_by       BINARY(16)   NULL,
    decided_at       TIMESTAMP(6) NULL,
    decision_remarks VARCHAR(500) NULL,

    -- An approved handover found to be wrong later.
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
    KEY idx_cash_handover_branch_status (company_id, branch_id, status),
    KEY idx_cash_handover_user_date (company_id, user_id, handover_date),

    CONSTRAINT fk_cash_handover_branch FOREIGN KEY (branch_id)
        REFERENCES branches (id) ON DELETE RESTRICT ON UPDATE RESTRICT,

    CONSTRAINT ck_cash_handover_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_cash_handover_mode CHECK (mode IN ('CASH', 'BANK_DEPOSIT', 'UPI')),
    CONSTRAINT ck_cash_handover_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'VOIDED')),
    -- Decided (approved, rejected, voided) carries who and when; pending carries neither.
    CONSTRAINT ck_cash_handover_decision CHECK (
        (status = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL)
        OR (status <> 'PENDING' AND decided_by IS NOT NULL AND decided_at IS NOT NULL)
    ),
    -- Only a voided handover carries void details, and it always carries all of them.
    CONSTRAINT ck_cash_handover_void CHECK (
        (status <> 'VOIDED' AND voided_by IS NULL AND voided_at IS NULL AND void_reason IS NULL)
        OR (status = 'VOIDED' AND voided_by IS NOT NULL AND voided_at IS NOT NULL AND void_reason IS NOT NULL)
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Cash a branch user hands over with proof, approved by the branch manager / accounts desk';

CREATE TABLE cash_handover_settings (
    company_id    BINARY(16) NOT NULL,
    tracking_from DATE       NOT NULL,
    PRIMARY KEY (company_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Per company: the day outstanding cash per user starts counting';

INSERT INTO cash_handover_settings (company_id, tracking_from)
SELECT company_id, CURRENT_DATE FROM companies;
