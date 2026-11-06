package com.courier.modules.shipment.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * A customer-portal booking made with an outside courier, found by its AWB for the public tracking page (which otherwise only knows
 * OCCPL shipments). Shows what the tracking sync last saved; it never calls the courier, so a public page cannot be used to hammer it.
 */
public interface CourierBookingTrackingPort {

    /** Empty when no booking, or more than one across companies, has this AWB (never guess which). */
    Optional<CourierTrack> findByAwb(String awb);

    /** {@code stage}: AWAITING_PICKUP, PICKED_UP, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, RETURNED or CANCELLED (null if not known yet). */
    record CourierTrack(String trackingNumber, String reference, String courierName, String stage, String statusText,
                        String location, Instant bookedAt, Instant statusAt, String fromCity, String toCity) {
    }
}
