package com.courier.modules.finance.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a branch's users did with shipments: who booked what and took which payment at the counter, and who
 * delivered what and collected how much. Finance owns the question (the User Performance Report and cash
 * handover); the shipment module answers it ({@code shipment.infrastructure.ShipmentUserActivityDirectory}),
 * same seam as {@link PendingCommissionPort}. No authorisation here: the report's own service has already
 * decided which branch the caller may read.
 */
public interface BranchUserActivityPort {

    /** How a shipment is paid, from its payment mode. */
    enum PaymentKind { PAID, TO_PAY, COD, CREDIT }

    /**
     * A shipment booked at the branch.
     *
     * @param bookedBy       the user who booked it ({@code shipments.created_by}); null for system or portal work
     *                       done without a signed-in user
     * @param counterMethod  how a Paid booking was paid at the counter: {@code CASH}, {@code UPI_QR},
     *                       {@code RAZORPAY}; null for a mode that does not collect at booking
     * @param counterPaid    whether that counter payment is complete
     * @param counterAmount  what the counter took (the net amount for a Paid booking), zero otherwise
     */
    record Booking(UUID shipmentId, String shipmentNumber, LocalDate bookingDate, Instant bookedAt, UUID bookedBy,
                   String status, boolean cancelled, String paymentMode, PaymentKind kind,
                   BigDecimal netAmount, BigDecimal chargeableWeight,
                   String counterMethod, boolean counterPaid, BigDecimal counterAmount) {
    }

    /**
     * A shipment on a delivery run (DRS) of the branch.
     *
     * @param deliveredAt     null while the shipment is still out / not yet delivered
     * @param amountToCollect what the delivery boy collects from the receiver: the freight for To-Pay and counter
     *                        COD, the customer's COD amount for a portal COD shipment, zero otherwise
     */
    record Delivery(UUID shipmentId, String shipmentNumber, UUID deliveryUserId, String drsNumber,
                    Instant assignedAt, Instant deliveredAt, int failedAttempts,
                    String paymentMode, PaymentKind kind, BigDecimal amountToCollect) {
    }

    /** Who booked and who delivered a shipment, to credit its wallet entries to the right user. */
    record ShipmentOwners(String shipmentNumber, UUID bookedBy, UUID bookingBranchId,
                          UUID deliveryUserId, UUID deliveryBranchId) {
    }

    /** Shipments booked at the branch on the days given (booking date, inclusive). */
    List<Booking> bookings(UUID companyId, UUID branchId, LocalDate from, LocalDate to);

    /** Shipments on the branch's delivery runs that were assigned or delivered in {@code [from, to)}. */
    List<Delivery> deliveries(UUID companyId, UUID branchId, Instant from, Instant to);

    /** Booker and deliverer of each shipment number given; unknown numbers are left out. */
    Map<String, ShipmentOwners> ownersOf(UUID companyId, Collection<String> shipmentNumbers);
}
