-- =============================================================================
-- V193 — The operator's final amount at booking (Domestic (CASH) Shipment, 2026-11-28)
--
-- When the operator types the final amount instead of the system's (within Settings → Finance's Net Amount limits,
-- with a reason), shipment_charges.net_amount holds the typed amount, freight and GST are adjusted to it, and these
-- keep what changed: the system's own amount, the reason and who changed it. All NULL when it was not changed.
-- =============================================================================

ALTER TABLE shipment_charges
    ADD COLUMN system_net_amount       DECIMAL(19, 4) NULL,
    ADD COLUMN final_amount_reason     VARCHAR(255)   NULL,
    ADD COLUMN final_amount_changed_by BINARY(16)     NULL;
