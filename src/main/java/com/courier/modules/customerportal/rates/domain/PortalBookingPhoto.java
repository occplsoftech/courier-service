package com.courier.modules.customerportal.rates.domain;

import com.courier.shared.domain.CompanyOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/** A photo of the parcel, or a document, attached to a portal booking (metadata; the bytes are in {@link PortalBookingPhotoFile}). */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "portal_booking_photos")
@Filter(name = CompanyOwnedEntity.COMPANY_FILTER, condition = "company_id = :companyId")
@SQLRestriction("deleted = false")
public class PortalBookingPhoto extends CompanyOwnedEntity {

    public static final String PARCEL = "PARCEL";
    public static final String DOCUMENT = "DOCUMENT";

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "customer_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID customerId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "booking_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID bookingId;

    @Column(name = "kind", nullable = false, length = 10, updatable = false)
    private String kind;

    @Column(name = "file_name", nullable = false, length = 200)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 40)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;
}
