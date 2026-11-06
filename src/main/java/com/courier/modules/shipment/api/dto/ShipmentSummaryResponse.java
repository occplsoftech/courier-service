package com.courier.modules.shipment.api.dto;

import com.courier.modules.shipment.domain.DeliveryType;
import com.courier.modules.shipment.domain.ShipmentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * List-row projection of a shipment.
 *
 * <p>{@code netAmount} is not a column on {@code shipments} — it lives on the separate
 * {@code shipment_charges} row this module writes at booking time (see {@code
 * ShipmentCharge}) — so the service batch-fetches it per page rather than per row, and
 * it comes in {@code null} only for a row that somehow has no charge record.
 */
@Schema(name = "ShipmentSummaryResponse", description = "Shipment list row")
public record ShipmentSummaryResponse(
        UUID id, String shipmentNumber, String trackingNumber, LocalDate bookingDate,
        UUID bookingBranchId, UUID deliveryBranchId, UUID currentLocationId, UUID nextLocationId,
        /** The booking branch's own city — no longer a Delivery Branch pick at booking, see
         *  {@code Shipment.fromCity}/{@code toCity}. */
        String fromCity,
        /** The destination pincode/area's resolved city. */
        String toCity,
        UUID manifestId, UUID paymentModeId, DeliveryType deliveryType,
        String senderName, String senderContact, String receiverName, String receiverContact,
        BigDecimal chargeableWeight, BigDecimal netAmount,
        BigDecimal totalCommission, BigDecimal commissionOnBasicFreight,
        BigDecimal branchCommissionOnOtherAmount, BigDecimal companyCommissionOnBasicFreight,
        ShipmentStatus status, Instant deliveredAt, Instant createdDate, Long version,
        /** Current E-Way Bill's invoice number, null where the shipment has none —
         *  the THC's own INVOICE NO column. */
        String invoiceNumber,
        /** When the shipment was last IN_SCAN'd (received at a branch/hub) — the latest of
         *  possibly several such entries if it crossed more than one hop. Null until it's
         *  been received anywhere. */
        Instant receivedAt,
        /** The government-issued E-Way Bill number itself (distinct from {@link
         *  #invoiceNumber}) — null until Part-A has actually succeeded. The THC's own
         *  E-WAY BILL NO column. */
        String ewayBillNumber,
        /** Package count — the THC's own QTY column. */
        Integer numberOfPackages,
        /** Set once a delivery attempt failed (Phase 10.2), so the Delivery list can show it. */
        ShipmentResponse.UndeliveredInfo undelivered,
        /** Portal COD: the amount to collect from the receiver for the customer; null otherwise. */
        java.math.BigDecimal codAmount
) {
}
