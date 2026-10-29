package com.courier.modules.customerportal.rates.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/** The bytes of a {@link PortalBookingPhoto}; read only when the file itself is downloaded. */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "portal_booking_photo_files")
public class PortalBookingPhotoFile {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "photo_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID photoId;

    @JdbcTypeCode(SqlTypes.LONGVARBINARY)
    @Column(name = "content", columnDefinition = "LONGBLOB", nullable = false)
    private byte[] content;

    public PortalBookingPhotoFile(UUID photoId, byte[] content) {
        this.photoId = photoId;
        this.content = content;
    }
}
