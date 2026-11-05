-- The name under which a courier (Delhivery, Shiprocket) knows this branch's pickup address, used when the branch forwards a shipment.
ALTER TABLE branches ADD COLUMN courier_pickup_location VARCHAR(120) NULL;
