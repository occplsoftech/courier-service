-- =============================================================================
-- V147 — Per-customer choice of rates: the customer's own (business) rate card, or the company's rates.
--
-- use_business_rates = TRUE  -> pricing zones / rate card of the customer price their bookings (counter and portal).
-- use_business_rates = FALSE -> the company rates (zone tariff / district freight) apply; the customer's rate card is ignored.
-- Customers that already have an active pricing zone keep today's behaviour (TRUE); everyone else starts on company rates.
-- =============================================================================

ALTER TABLE customers
    ADD COLUMN use_business_rates BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE customers c
   SET c.use_business_rates = TRUE
 WHERE c.deleted = 0
   AND EXISTS (SELECT 1 FROM customer_pricing_zones z WHERE z.customer_id = c.id AND z.deleted = 0 AND z.status = 'ACTIVE');
