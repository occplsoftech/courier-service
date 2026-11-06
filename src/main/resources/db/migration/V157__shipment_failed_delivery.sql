-- Phase 10.2: a delivery attempt that failed. The shipment goes back to the delivery branch (IN_SCAN) with the reason, and stays
-- an open case (undelivered_open) until it is sent out again, delivered or returned.
ALTER TABLE shipments
    ADD COLUMN failed_attempts    INT          NOT NULL DEFAULT 0,
    ADD COLUMN undelivered_reason VARCHAR(40)  NULL,
    ADD COLUMN undelivered_note   VARCHAR(255) NULL,
    ADD COLUMN undelivered_at     DATETIME(6)  NULL,
    ADD COLUMN undelivered_open   BOOLEAN      NOT NULL DEFAULT FALSE;

CREATE INDEX idx_shipments_undelivered_open ON shipments (company_id, undelivered_open);
