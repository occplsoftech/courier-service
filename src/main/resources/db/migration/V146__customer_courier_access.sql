-- =============================================================================
-- V146 — Which couriers a business customer can see in the customer portal.
--
-- One row per (customer, courier code). A customer with no rows sees OCCPL Network only; with rows, exactly those couriers.
-- Managed by the company admin under Courier Integrations -> Customer access. Replaced as a whole on save (hard delete + insert).
-- =============================================================================

CREATE TABLE customer_courier_access (
    id            BINARY(16)  NOT NULL,
    company_id    BINARY(16)  NOT NULL,
    customer_id   BINARY(16)  NOT NULL,
    provider_code VARCHAR(40) NOT NULL,

    created_at TIMESTAMP(6) NOT NULL, created_by BINARY(16) NULL,
    updated_at TIMESTAMP(6) NOT NULL, updated_by BINARY(16) NULL,
    deleted    BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP(6) NULL, deleted_by BINARY(16) NULL,
    version    BIGINT NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    UNIQUE KEY uk_customer_courier_access (company_id, customer_id, provider_code),
    CONSTRAINT fk_customer_courier_access_customer FOREIGN KEY (customer_id) REFERENCES customers (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Couriers a business customer may use in the customer portal (none = OCCPL Network only)';
