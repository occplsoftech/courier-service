-- =============================================================================
-- V161 — Return to sender (RTO) as a tracked trip back (portal roadmap 12.1 A).
--
-- shipments: rto = the parcel is on its way back (or back) to the sender. Return to sender at
--   the delivery branch points the shipment at its booking branch (delivery_branch_id /
--   next_location_id) and puts it on the normal Load Sheet -> dispatch -> in-scan path; the
--   booking branch then hands it back (status RETURNED) recording who received it.
-- portal_bookings: the RTO charge debited from the customer's wallet (= the forward freight),
--   once per booking, for OCCPL Network and outside-courier bookings alike.
-- =============================================================================

ALTER TABLE shipments
    ADD COLUMN rto BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN rto_started_at DATETIME(6) NULL,
    ADD COLUMN rto_delivered_at DATETIME(6) NULL,
    ADD COLUMN rto_handed_to VARCHAR(120) NULL;

ALTER TABLE portal_bookings
    ADD COLUMN rto_charge DECIMAL(14, 2) NULL,
    ADD COLUMN rto_charged_at DATETIME(6) NULL;
