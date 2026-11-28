-- =============================================================================
-- V188 — Daily booking summary e-mail to business customers, one row per (company, customer, day).
--
-- Every night at 23:30 IST each business customer with an e-mail address (and e-mail notifications on) who had
-- bookings in the last 24 hours (23:30 the day before to 23:30) gets one e-mail listing them: AWB, booked at,
-- consignee, destination, pieces, service, status. No rate, no weight. This table is the ledger: the unique key stops
-- a retry tick (23:40, 23:50), a restart or a second node from sending the same day's summary twice, and
-- attempt_count caps retries of a FAILED send.
-- =============================================================================

CREATE TABLE booking_summary_email_log (
    id          BINARY(16) NOT NULL,
    company_id  BINARY(16) NOT NULL,
    customer_id BINARY(16) NOT NULL,

    -- The day the 24-hour window ends on (IST), not the day it was sent.
    report_date   DATE         NOT NULL,
    recipient     VARCHAR(255) NOT NULL,
    booking_count INT          NOT NULL DEFAULT 0,

    -- PENDING | SENT | FAILED
    status          VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    error_message   VARCHAR(1000) NULL,
    attempt_count   INT           NOT NULL DEFAULT 0,
    last_attempt_at TIMESTAMP(6)  NULL,
    sent_at         TIMESTAMP(6)  NULL,

    created_at TIMESTAMP(6) NOT NULL, created_by BINARY(16) NULL,
    updated_at TIMESTAMP(6) NOT NULL, updated_by BINARY(16) NULL,
    deleted    BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP(6) NULL, deleted_by BINARY(16) NULL,
    version    BIGINT NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    UNIQUE KEY uk_booking_summary_email_log (company_id, customer_id, report_date),
    KEY ix_booking_summary_email_log_day (company_id, report_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Send ledger for the daily booking summary e-mail to business customers';
