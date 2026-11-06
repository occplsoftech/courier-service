package com.courier.modules.communication.domain;

/**
 * Business events a template/log row may be keyed on. {@code RTO_INITIATED}/
 * {@code RTO_DELIVERED} are declared for architecture readiness only — this codebase's
 * {@code ShipmentStatus} has no return-to-origin flow yet (only a generic {@code RETURNED}
 * terminal state that no service method currently writes, per its own class-level doc), so
 * no {@code ShipmentEvent} exists for either and nothing ever publishes them today. A future
 * RTO module can start publishing into these two rows with no schema or enum change here.
 */
public enum CommunicationEventType {
    SHIPMENT_BOOKED,
    /** The parcel was collected from the customer (a portal booking's pickup was completed). */
    SHIPMENT_PICKED_UP,
    SHIPMENT_DISPATCHED,
    SHIPMENT_RECEIVED,
    OUT_FOR_DELIVERY,
    SHIPMENT_DELIVERED,
    /** A delivery attempt failed (Phase 10.2); the customer chooses what to do next. */
    DELIVERY_FAILED,
    SHIPMENT_CANCELLED,
    RTO_INITIATED,
    RTO_DELIVERED,
    /** The 09:00 IST owner summary — not tied to a shipment; see {@code DailyReportService}. */
    DAILY_BUSINESS_SUMMARY;

    /** The events seeded with a default WhatsApp+SMS+Email template (the brief's four, and picked up). */
    public static final java.util.Set<CommunicationEventType> DEFAULT_ENABLED = java.util.Set.of(
            SHIPMENT_BOOKED, SHIPMENT_PICKED_UP, SHIPMENT_DISPATCHED, OUT_FOR_DELIVERY, SHIPMENT_DELIVERED, DELIVERY_FAILED);

    private static final java.util.Set<CommunicationEventType> SENDER_FACING = java.util.EnumSet.of(
            SHIPMENT_BOOKED, SHIPMENT_PICKED_UP, SHIPMENT_DISPATCHED, SHIPMENT_CANCELLED, DELIVERY_FAILED);

    /** True for events about the sender's own action (they booked/dispatched/cancelled it);
     *  false for events about the shipment arriving at the receiver — see {@code
     *  ShipmentSnapshot}'s own doc for why recipient choice lives on this enum rather than
     *  being re-derived (fragile address matching) at send time. */
    public boolean notifiesSender() {
        return SENDER_FACING.contains(this);
    }
}
