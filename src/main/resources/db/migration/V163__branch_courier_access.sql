-- =============================================================================
-- V163 — Branch-level courier assignment (portal roadmap 13.2).
--
-- customers.home_branch_id: the branch a business customer belongs to. Filled from the branch
--   serving the customer's default pickup pincode (here for existing customers, and when a
--   default pickup address is saved and none is set yet); staff can change it.
-- branch_courier_access: the couriers a branch gives its customers. A customer with no list of
--   their own (customer_courier_access) uses their home branch's list; with neither, OCCPL
--   Network only.
-- =============================================================================

ALTER TABLE customers
    ADD COLUMN home_branch_id BINARY(16) NULL,
    ADD KEY idx_customers_home_branch (company_id, home_branch_id);

CREATE TABLE branch_courier_access (
    id            BINARY(16)  NOT NULL,
    company_id    BINARY(16)  NOT NULL,
    branch_id     BINARY(16)  NOT NULL,
    provider_code VARCHAR(40) NOT NULL,

    created_at TIMESTAMP(6) NOT NULL, created_by BINARY(16) NULL,
    updated_at TIMESTAMP(6) NOT NULL, updated_by BINARY(16) NULL,
    deleted    BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP(6) NULL, deleted_by BINARY(16) NULL,
    version    BIGINT NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    UNIQUE KEY uk_branch_courier_access (company_id, branch_id, provider_code),
    CONSTRAINT fk_branch_courier_access_branch FOREIGN KEY (branch_id) REFERENCES branches (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Couriers a branch gives the business customers whose home branch it is (13.2)';

-- Existing business customers: the branch serving their default pickup pincode (one per pincode per company).
UPDATE customers c
    JOIN customer_addresses a ON a.customer_id = c.id AND a.company_id = c.company_id
         AND a.is_default_pickup = TRUE AND a.deleted = FALSE AND a.pincode_id IS NOT NULL
    JOIN branch_pincode_mapping m ON m.company_id = c.company_id AND m.pincode_id = a.pincode_id AND m.deleted = FALSE
SET c.home_branch_id = m.branch_id
WHERE c.customer_type = 'BUSINESS' AND c.deleted = FALSE AND c.home_branch_id IS NULL;
