-- =============================================================================
-- V162 — Reverse pickup (portal roadmap 12.1 B): the customer books a pickup from their buyer
-- back to themselves, from a delivered booking, on OCCPL Network.
--
-- portal_bookings.return_of_booking_id: the delivered booking this one brings back.
-- shipments.return_pickup: the OCCPL shipment is a return pickup (collected from the buyer,
--   delivered to the customer), so staff can tell it apart.
-- =============================================================================

ALTER TABLE portal_bookings
    ADD COLUMN return_of_booking_id BINARY(16) NULL;

ALTER TABLE shipments
    ADD COLUMN return_pickup BOOLEAN NOT NULL DEFAULT FALSE;
