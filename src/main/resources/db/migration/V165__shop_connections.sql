-- =============================================================================
-- V165 — Shop connections (portal roadmap 13.3b: Shopify).
--
-- shop_connections: a business customer's online store, connected with an access token from a
--   custom app they created in their own store admin. The token is encrypted (the platform's
--   secrets key). Open, unfulfilled orders are imported into portal_orders every few minutes;
--   orders that cannot be imported are kept (latest 50) with the reason, for the customer to fix.
-- portal_orders gains the store's own order id (needed to send the tracking number back) and the
--   result of sending it.
-- =============================================================================

CREATE TABLE shop_connections (
    id               BINARY(16)   NOT NULL,
    company_id       BINARY(16)   NOT NULL,
    customer_id      BINARY(16)   NOT NULL,
    platform         VARCHAR(20)  NOT NULL,
    shop_domain      VARCHAR(120) NOT NULL,
    shop_name        VARCHAR(150) NULL,
    secrets_enc      TEXT         NULL,
    paused           BOOLEAN      NOT NULL DEFAULT FALSE,
    fulfil_back      BOOLEAN      NOT NULL DEFAULT TRUE,
    last_import_at   DATETIME(6)  NULL,
    imported_count   INT          NOT NULL DEFAULT 0,
    last_error       VARCHAR(300) NULL,
    skipped_json     JSON         NULL,

    created_at TIMESTAMP(6) NOT NULL, created_by BINARY(16) NULL,
    updated_at TIMESTAMP(6) NOT NULL, updated_by BINARY(16) NULL,
    deleted    BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP(6) NULL, deleted_by BINARY(16) NULL,
    version    BIGINT NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    KEY idx_shop_connections_customer (company_id, customer_id),
    KEY idx_shop_connections_due (paused, last_import_at),
    CONSTRAINT fk_shop_connections_customer FOREIGN KEY (customer_id) REFERENCES customers (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Online stores connected by business customers (13.3b); token encrypted';

ALTER TABLE portal_orders
    ADD COLUMN connection_id BINARY(16)  NULL AFTER external_ref,
    ADD COLUMN external_id   VARCHAR(40) NULL AFTER connection_id,
    ADD COLUMN shop_sync     VARCHAR(20) NULL COMMENT 'SENT or FAILED: tracking sent back to the store',
    ADD COLUMN shop_sync_error VARCHAR(300) NULL;
