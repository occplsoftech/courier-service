package com.courier.modules.communication.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * A customer-portal booking made with an outside courier, seen as a {@link ShipmentSnapshot} (its id is the booking id) so the same
 * templates, switches and log rows serve it. Empty for an unknown booking and for an OCCPL Network booking (its shipment speaks for it).
 */
public interface PortalBookingDirectoryPort {

    Optional<ShipmentSnapshot> findSnapshot(UUID companyId, UUID bookingId);
}
