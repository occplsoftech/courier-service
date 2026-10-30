-- =============================================================================
-- V145 — Photos and documents attached to a customer-portal booking.
--
-- PARCEL: photos of the parcel, shown to OCCPL's pickup staff and to the customer.
-- DOCUMENT: invoice or proof of contents, kept with the booking.
-- Metadata and bytes are in two tables so listing never reads the images (same split as KYC documents).
-- =============================================================================

CREATE TABLE portal_booking_photos (
    id           BINARY(16)   NOT NULL,
    company_id   BINARY(16)   NOT NULL,
    customer_id  BINARY(16)   NOT NULL,
    booking_id   BINARY(16)   NOT NULL,
    kind         VARCHAR(10)  NOT NULL,
    file_name    VARCHAR(200) NOT NULL,
    content_type VARCHAR(40)  NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    sha256       VARCHAR(64)  NOT NULL,

    created_at TIMESTAMP(6) NOT NULL, created_by BINARY(16) NULL,
    updated_at TIMESTAMP(6) NOT NULL, updated_by BINARY(16) NULL,
    deleted    BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP(6) NULL, deleted_by BINARY(16) NULL,
    version    BIGINT NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    KEY idx_portal_booking_photos_booking (company_id, booking_id, deleted),
    CONSTRAINT fk_portal_booking_photo_booking FOREIGN KEY (booking_id) REFERENCES portal_bookings (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Photos / documents attached to a portal booking (metadata)';

CREATE TABLE portal_booking_photo_files (
    photo_id BINARY(16) NOT NULL,
    content  LONGBLOB   NOT NULL,
    PRIMARY KEY (photo_id),
    CONSTRAINT fk_portal_booking_photo_file FOREIGN KEY (photo_id) REFERENCES portal_booking_photos (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Bytes of a portal booking photo (JPEG/PNG/WebP, PDF for documents; at most 5 MB)';
