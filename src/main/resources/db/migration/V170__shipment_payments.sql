-- =============================================================================
-- V170 — How the customer paid a Paid booking, and the company's UPI ID.
--
-- shipment_payments: how the customer paid a Paid (collect-at-booking) staff booking: RAZORPAY,
--   CASH or UPI_QR, one row per shipment. A RAZORPAY booking is made with the payment PENDING; a
--   single-use QR for the final amount is opened on screen and the row turns PAID when Razorpay
--   reports the payment (webhook qr_code.credited, or the screen's status check). It does not debit
--   the branch wallet: the money lands in the company's Razorpay account. CASH / UPI_QR rows are
--   written PAID at booking and the wallet is debited as before.
-- company_settings_config.upi_id / upi_payee_name: the company's own UPI ID; the booking screen
--   draws a UPI QR with the exact amount from it ("QR" method, confirmed by staff).
-- =============================================================================

-- IF NOT EXISTS: the first deploy of this file created the table and then failed on the next
-- statement (wrong table name), and MySQL cannot roll DDL back; Flyway's repair re-runs the file.
CREATE TABLE IF NOT EXISTS shipment_payments (
    id                 BINARY(16)    NOT NULL,
    company_id         BINARY(16)    NOT NULL,
    shipment_id        BINARY(16)    NOT NULL,
    shipment_number    VARCHAR(40)   NOT NULL,
    booking_branch_id  BINARY(16)    NOT NULL,
    method             VARCHAR(20)   NOT NULL COMMENT 'RAZORPAY, CASH or UPI_QR',
    status             VARCHAR(20)   NOT NULL COMMENT 'PENDING or PAID',
    amount             DECIMAL(19,4) NOT NULL,
    reference          VARCHAR(100)  NULL COMMENT 'UPI reference typed by staff, or the Razorpay payment id',
    gateway_qr_id      VARCHAR(64)   NULL,
    qr_image_url       VARCHAR(500)  NULL,
    qr_close_by        DATETIME(6)   NULL,
    gateway_payment_id VARCHAR(64)   NULL,
    paid_amount        DECIMAL(19,4) NULL,
    paid_at            DATETIME(6)   NULL,

    created_at TIMESTAMP(6) NOT NULL, created_by BINARY(16) NULL,
    updated_at TIMESTAMP(6) NOT NULL, updated_by BINARY(16) NULL,
    deleted    BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP(6) NULL, deleted_by BINARY(16) NULL,
    version    BIGINT NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    UNIQUE KEY uk_shipment_payments_shipment (shipment_id),
    KEY idx_shipment_payments_status (company_id, status, created_at),
    KEY idx_shipment_payments_qr (gateway_qr_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'How the customer paid a Paid booking at the counter';

ALTER TABLE company_settings_config
    ADD COLUMN upi_id VARCHAR(100) NULL AFTER forward_min_profit_percent,
    ADD COLUMN upi_payee_name VARCHAR(100) NULL AFTER upi_id;
