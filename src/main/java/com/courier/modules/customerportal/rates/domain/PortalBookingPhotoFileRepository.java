package com.courier.modules.customerportal.rates.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Always reached through a {@link PortalBookingPhoto} already checked to belong to the caller. */
public interface PortalBookingPhotoFileRepository extends JpaRepository<PortalBookingPhotoFile, UUID> {
}
