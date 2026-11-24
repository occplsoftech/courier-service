-- =============================================================================
-- V169 — Minimum profit when forwarding a shipment to another courier.
--
-- company_settings.forward_min_profit_percent: the forwarding screen offers only couriers whose
--   cost leaves at least this % of the shipment's booked amount (net amount) as profit:
--   10 = a ₹100 booking is offered couriers costing up to ₹90. 0 offers every courier.
-- =============================================================================

ALTER TABLE company_settings_config
    ADD COLUMN forward_min_profit_percent DECIMAL(5,2) NOT NULL DEFAULT 10.00 AFTER to_pay_charge;
