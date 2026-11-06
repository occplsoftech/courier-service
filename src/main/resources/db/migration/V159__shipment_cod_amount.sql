-- Phase 11.3: cash on delivery for customer-portal shipments. cod_amount is what the delivery boy collects from the receiver (the
-- customer's money); cod_collected_at is when it was collected and paid into the customer's wallet.
ALTER TABLE shipments
    ADD COLUMN cod_amount       DECIMAL(12, 2) NULL,
    ADD COLUMN cod_collected_at DATETIME(6)    NULL;
