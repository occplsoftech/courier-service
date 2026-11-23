-- =============================================================================
-- V168 — COD charge on a shipment.
--
-- shipment_charges.cod_charge: on a Cash on Delivery booking, 2% of the COD collection amount
--   (shipments.cod_amount), at least 100; taxed with GST, part of net_amount. 0 on every other booking.
-- =============================================================================

ALTER TABLE shipment_charges
    ADD COLUMN cod_charge DECIMAL(19,4) NOT NULL DEFAULT 0 AFTER to_pay_charge;
