-- =============================================================================
-- V137 — Customer wallets, part 2: money in
--
-- Recharges and top-up requests may now be a customer's as well as a branch's.
-- Same shape as V136 for wallets: branch_id becomes nullable, customer_id is added, and a
-- CHECK allows only "exactly one owner".
--
--   wallet_recharge_orders  — online (Razorpay) and manual recharges
--   wallet_topup_requests   — "I paid by bank transfer / cash, please credit my wallet",
--                             decided by a company admin
--
-- Existing rows all have a branch and stay valid. No backfill.
-- =============================================================================

ALTER TABLE wallet_recharge_orders
    MODIFY COLUMN branch_id BINARY(16) NULL,
    ADD COLUMN customer_id BINARY(16) NULL AFTER branch_id,
    ADD KEY idx_recharge_orders_customer (company_id, customer_id, created_at),
    ADD CONSTRAINT ck_recharge_orders_owner CHECK (
        (branch_id IS NOT NULL AND customer_id IS NULL)
     OR (branch_id IS NULL AND customer_id IS NOT NULL));

ALTER TABLE wallet_topup_requests
    MODIFY COLUMN branch_id BINARY(16) NULL,
    ADD COLUMN customer_id BINARY(16) NULL AFTER branch_id,
    ADD KEY idx_topup_req_customer_status (company_id, customer_id, status),
    ADD CONSTRAINT fk_topup_req_customer FOREIGN KEY (customer_id)
        REFERENCES customers (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT ck_topup_req_owner CHECK (
        (branch_id IS NOT NULL AND customer_id IS NULL)
     OR (branch_id IS NULL AND customer_id IS NOT NULL));
