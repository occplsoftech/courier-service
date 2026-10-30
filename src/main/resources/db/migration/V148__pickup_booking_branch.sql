-- =============================================================================
-- V148 — The branch a pickup (and the shipment created with it) belongs to.
--
-- Set when a customer books through the portal: the branch the pickup pincode is mapped to (Pincode Branch Mapping). That branch
-- assigns the pickup to its team / pickup partner. Null for pickups entered by staff before this existed.
-- =============================================================================

ALTER TABLE pickup_requests
    ADD COLUMN booking_branch_id BINARY(16) NULL AFTER shipment_no,
    ADD KEY idx_pickup_booking_branch (company_id, booking_branch_id);
