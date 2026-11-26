-- =============================================================================
-- V178 — Delivery Updation (as in the previous software): how a delivery was proved and who took it
--
-- delivery_assignment gains
--   receiver_relation  the receiver's relation to the consignee (Self, Security, Neighbour …), free text
--   proof_type         DROP, SIGNATURE or STAMP_AND_SIGN
-- delivered_at may now be a chosen past date (Delivery Updation's Delivered Date), not only "now".
-- Undelivered reasons gain HOLD, MISROUTE, NO_SERVICE, SHIFTED and CUSTOMER_INSTRUCTION (stored as text, no DDL).
-- =============================================================================

ALTER TABLE delivery_assignment
    ADD COLUMN receiver_relation VARCHAR(50) NULL AFTER receiver_name,
    ADD COLUMN proof_type        VARCHAR(30) NULL AFTER receiver_relation;
