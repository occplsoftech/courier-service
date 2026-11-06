package com.courier.modules.customerportal.rates.domain;

import java.util.UUID;

/**
 * Something the customer or receiver may be told about a portal booking: {@code BOOKED} when an outside courier confirmed it, or a
 * delivery stage the tracking sync saw ({@code PICKED_UP}, {@code OUT_FOR_DELIVERY}, {@code DELIVERED}, {@code CANCELLED}...). Published
 * for every booking; the Communication Center only sends for bookings with an outside courier (OCCPL Network bookings are covered by
 * their shipment's own events) and never twice for the same booking, event and channel.
 */
public record PortalBookingNotice(UUID bookingId, UUID companyId, String event) {
}
