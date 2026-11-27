-- =============================================================================
-- V182 — A company admin uploads the company logo (Settings → Company Logo)
--
-- companies.logo_data_url: the uploaded logo as a data: URL (PNG, JPEG or WebP, at most 150 KB before encoding,
-- checked by the service), same as customers.logo_data_url. When set it wins over companies.logo (the link a
-- super admin types on the company form) on every print letterhead.
-- =============================================================================

ALTER TABLE companies
    ADD COLUMN logo_data_url MEDIUMTEXT NULL COMMENT 'Uploaded company logo as a data: URL (png/jpeg/webp)';
