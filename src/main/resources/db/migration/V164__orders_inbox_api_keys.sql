-- =============================================================================
-- V164 — Orders inbox and API keys (portal roadmap 13.3a).
--
-- portal_orders: orders that arrive for a business customer without re-typing (their own system
--   through an API key now; Shopify and WooCommerce next). The customer checks them in "Orders to
--   ship" and books them; a booked order points at its portal booking. One order per
--   (customer, source, external_ref), so a retried push never creates a second one.
-- customer_api_keys: keys a customer's system uses for the public order API. Only a SHA-256 hash
--   is stored; the key is shown once when created.
-- =============================================================================

CREATE TABLE portal_orders (
    id               BINARY(16)    NOT NULL,
    company_id       BINARY(16)    NOT NULL,
    customer_id      BINARY(16)    NOT NULL,
    source           VARCHAR(20)   NOT NULL,
    external_ref     VARCHAR(80)   NOT NULL,
    status           VARCHAR(20)   NOT NULL,

    delivery_name    VARCHAR(100)  NOT NULL,
    delivery_phone   VARCHAR(20)   NOT NULL,
    delivery_address VARCHAR(300)  NOT NULL,
    delivery_pincode VARCHAR(6)    NOT NULL,
    delivery_city    VARCHAR(80)   NULL,
    delivery_state   VARCHAR(80)   NULL,

    weight_kg        DECIMAL(10, 3) NOT NULL,
    length_cm        DECIMAL(10, 2) NOT NULL,
    breadth_cm       DECIMAL(10, 2) NOT NULL,
    height_cm        DECIMAL(10, 2) NOT NULL,
    package_count    INT            NOT NULL,
    contents         VARCHAR(200)   NOT NULL,
    declared_value   DECIMAL(14, 2) NOT NULL,
    payment_mode     VARCHAR(10)    NOT NULL,
    cod_amount       DECIMAL(14, 2) NULL,
    items_json       JSON           NULL,
    notes            VARCHAR(500)   NULL,

    booking_id       BINARY(16)     NULL,
    booked_at        DATETIME(6)    NULL,

    created_at TIMESTAMP(6) NOT NULL, created_by BINARY(16) NULL,
    updated_at TIMESTAMP(6) NOT NULL, updated_by BINARY(16) NULL,
    deleted    BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP(6) NULL, deleted_by BINARY(16) NULL,
    version    BIGINT NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    UNIQUE KEY uk_portal_orders_ref (company_id, customer_id, source, external_ref),
    KEY idx_portal_orders_customer_status (company_id, customer_id, status, created_at),
    CONSTRAINT fk_portal_orders_customer FOREIGN KEY (customer_id) REFERENCES customers (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Orders waiting to be booked in the customer portal (13.3)';

CREATE TABLE customer_api_keys (
    id           BINARY(16)  NOT NULL,
    company_id   BINARY(16)  NOT NULL,
    customer_id  BINARY(16)  NOT NULL,
    name         VARCHAR(60) NOT NULL,
    key_prefix   VARCHAR(16) NOT NULL,
    key_hash     CHAR(64)    NOT NULL,
    last_used_at DATETIME(6) NULL,
    revoked_at   DATETIME(6) NULL,

    created_at TIMESTAMP(6) NOT NULL, created_by BINARY(16) NULL,
    updated_at TIMESTAMP(6) NOT NULL, updated_by BINARY(16) NULL,
    deleted    BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP(6) NULL, deleted_by BINARY(16) NULL,
    version    BIGINT NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    UNIQUE KEY uk_customer_api_keys_hash (key_hash),
    KEY idx_customer_api_keys_customer (company_id, customer_id),
    CONSTRAINT fk_customer_api_keys_customer FOREIGN KEY (customer_id) REFERENCES customers (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'API keys of business customers for the public order API (hash only)';
