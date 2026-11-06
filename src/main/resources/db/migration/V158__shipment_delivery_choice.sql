-- Phase 10.2 (part b): what the customer chose after a failed delivery attempt.
ALTER TABLE shipments
    ADD COLUMN undelivered_choice         VARCHAR(20)  NULL,
    ADD COLUMN undelivered_choice_date    DATE         NULL,
    ADD COLUMN undelivered_choice_at      DATETIME(6)  NULL;
