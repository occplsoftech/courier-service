package com.courier.modules.customerportal.rates.domain;

/**
 * One courier source behind the customer portal (OCCPL's own network, or an external courier or
 * aggregator). Implementations never throw for a provider failure: rates answer
 * {@link ProviderResult#unavailable}, bookings a {@link BookingOutcome}, each with a reason the customer
 * may read (no secrets, no stack traces).
 */
public interface CourierProviderAdapter {

    /** Stable code, stored on quotes: OCCPL_NETWORK, SHIPROCKET, DELHIVERY, BLUEDART, EKART. */
    String code();

    String displayName();

    /** External providers get the configured markup; OCCPL's own network is priced from the customer's rate card. */
    boolean external();

    /** False when credentials/contract are missing: the provider is then shown as "not configured". */
    boolean configured();

    /** Whether this company can use the provider. Env-configured providers answer the same for every company. */
    default boolean configured(java.util.UUID companyId) {
        return configured();
    }

    /**
     * False for a provider that only ships abroad (sKart): the domestic comparison does not ask it or list it; it answers
     * through {@link InternationalRateProvider} instead.
     */
    default boolean domestic() {
        return true;
    }

    ProviderResult getRates(RateRequest request);

    /**
     * Makes one cheap authenticated call with the company's saved credentials, to tell its administrator whether the courier
     * accepts them. Must not throw for a provider failure and must not put credentials in the message.
     */
    default ConnectionTest testConnection(java.util.UUID companyId) {
        return ConnectionTest.failed(displayName() + " connections cannot be tested yet.");
    }

    /**
     * Books one service of a frozen quote. Must classify honestly: REJECTED only when the provider certainly
     * did not accept it; anything in doubt is UNKNOWN.
     */
    default BookingOutcome book(BookingRequest request) {
        return BookingOutcome.rejected("NOT_SUPPORTED", displayName() + " bookings are not available yet.");
    }

    /**
     * Cancels an order we placed that never got an AWB (an unconfirmed forward). {@code orderRef} is the courier's own id when we
     * kept one (may be null); {@code reference} is the order reference we sent, so the order can be looked up when there is no id.
     * Must not throw for a provider failure.
     */
    default CancelOutcome cancelOrder(java.util.UUID companyId, String orderRef, String reference) {
        return CancelOutcome.failed(displayName() + " orders cannot be cancelled from here yet. Cancel it in the " + displayName() + " panel.");
    }

    /** Whether a booking this provider confirmed can still be cancelled by the customer (not for couriers we cannot cancel with). */
    default boolean canCancelBooking(java.util.UUID companyId, java.util.UUID customerId, String orderRef) {
        return false;
    }

    /**
     * Cancels a booking this provider confirmed, before pickup, for the customer who made it. Must not throw for a provider failure:
     * a refusal is {@link CancelOutcome#failed} with a message the customer may read.
     */
    default CancelOutcome cancelBooking(java.util.UUID companyId, java.util.UUID customerId, String orderRef, String reason) {
        return CancelOutcome.failed("This booking cannot be cancelled online. Please contact support.");
    }

    /** The pickup date and slot the customer chose for a booking (OCCPL Network); {@link PickupSchedule#NONE} when unknown or not applicable. */
    default PickupSchedule pickupSchedule(java.util.UUID companyId, java.util.UUID customerId, String orderRef) {
        return PickupSchedule.NONE;
    }

    /**
     * Changes the pickup date and slot of a booking before the parcel is collected. Must not throw for a provider failure: a refusal is
     * {@link CancelOutcome#failed} with a message the customer may read ({@code CANCELLED} here means "changed").
     */
    default CancelOutcome reschedulePickup(java.util.UUID companyId, java.util.UUID customerId, String orderRef,
                                           java.time.LocalDate date, String slot) {
        return CancelOutcome.failed("The pickup time of this booking cannot be changed online. Please contact support.");
    }

    /** The shipment's progress now. Must not throw for a provider failure: answer {@link TrackingResult#unavailable}. */
    default TrackingResult track(ShipmentRef shipment) {
        return TrackingResult.unavailable("Tracking is not available for " + displayName() + " yet.");
    }

    /**
     * Where the shipping label is. A stored {@code labelUrl} is reused by the caller; this is asked only when there
     * is none. Must not throw for a provider failure.
     */
    default LabelResult label(ShipmentRef shipment) {
        return LabelResult.notAvailable("Labels are not available for " + displayName() + " yet.");
    }

    /** Hosts (exact, or a suffix starting with a dot) the server may download this provider's labels from. */
    default java.util.Set<String> labelHosts() {
        return java.util.Set.of();
    }
}
