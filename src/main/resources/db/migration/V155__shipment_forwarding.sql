-- =============================================================================
-- V155 — Forwarding a shipment to a courier partner from the hub.
--
-- A shipment collected by OCCPL and brought to a hub can be handed to a connected courier (Delhivery, Shiprocket...). These columns
-- record who it went to and the courier's own AWB, label and cost. The customer keeps the OCCPL AWB; the status becomes FORWARDED.
--   forward_state: SUBMITTING while the courier is being asked, UNKNOWN when we cannot tell whether it accepted (needs checking, never
--   retried automatically), BOOKED once the courier has it. NULL = never forwarded.
-- =============================================================================

ALTER TABLE shipments
    ADD COLUMN forward_provider  VARCHAR(40)    NULL,
    ADD COLUMN forward_service   VARCHAR(150)   NULL,
    ADD COLUMN forward_state     VARCHAR(12)    NULL,
    ADD COLUMN forward_awb       VARCHAR(80)    NULL,
    ADD COLUMN forward_order_ref VARCHAR(120)   NULL,
    ADD COLUMN forward_label_url VARCHAR(500)   NULL,
    ADD COLUMN forward_cost      DECIMAL(12, 2) NULL,
    ADD COLUMN forward_note      VARCHAR(255)   NULL,
    ADD COLUMN forwarded_at      TIMESTAMP(6)   NULL,
    ADD COLUMN forwarded_by      BINARY(16)     NULL,
    ADD KEY idx_shipments_forward (company_id, forward_provider, forward_state);
