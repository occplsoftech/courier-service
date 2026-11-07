package com.courier.modules.pickup.domain;

import java.util.UUID;

/** A branch booked the shipment for a pickup and linked it (the pickup is now BOOKED). Published after the link is saved. */
public record PickupShipmentLinked(UUID companyId, UUID pickupId, String pickupNo, UUID shipmentId) {
}
