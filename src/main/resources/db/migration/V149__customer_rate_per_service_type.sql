-- =============================================================================
-- V149 — A customer's zone rate card per service type (AIR, SURFACE, CARGO, PREMIUM...).
--
-- service_type_id NULL = the card for "all services" (what every existing card becomes, so nothing changes for them).
-- A zone may now hold one card per service type plus one default; uniqueness is enforced by the service (MySQL does not treat
-- NULLs as equal in a unique key).
-- =============================================================================

ALTER TABLE customer_zone_rates
    ADD COLUMN service_type_id BINARY(16) NULL AFTER customer_id,
    ADD KEY idx_czr_zone_service (company_id, zone_id, service_type_id);
