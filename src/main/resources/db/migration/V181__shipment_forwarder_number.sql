-- Outscan "With Forwarder No.": the co-loader's own number for each AWB, entered or scanned at ASSIGN and printed on the manifest.
ALTER TABLE shipments ADD COLUMN forwarder_number VARCHAR(60) NULL;
