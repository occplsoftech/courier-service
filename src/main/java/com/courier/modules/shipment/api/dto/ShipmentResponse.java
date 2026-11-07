package com.courier.modules.shipment.api.dto;

import com.courier.modules.shipment.domain.DeliveryType;
import com.courier.modules.shipment.domain.ShipmentStatus;
import com.courier.modules.shipment.domain.ShipmentType;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Full representation of a shipment, including its item grid. Nulls are serialised. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "ShipmentResponse", description = "Shipment in full, with its item grid")
public record ShipmentResponse(
        UUID id, UUID companyId,
        String shipmentNumber, String trackingNumber,
        LocalDate bookingDate,
        UUID bookingBranchId, UUID deliveryBranchId, UUID manifestId,
        @Schema(description = "The booking branch's own city, shown at booking instead of "
                + "a branch picker") String fromCity,
        @Schema(description = "The destination pincode/area's resolved city, shown at "
                + "booking instead of a Delivery Branch picker") String toCity,
        UUID currentLocationId, UUID nextLocationId,
        String pickupPincode, String deliveryPincode,
        @Schema(description = "The specific Area of deliveryPincode the operator picked at "
                + "booking, when that pincode has more than one; null for shipments booked "
                + "before this field existed") UUID destinationAreaId,
        String senderName, String senderAddress, String senderContact,
        String receiverName, String receiverAddress, String receiverContact,
        UUID serviceTypeId, UUID packageTypeId, UUID paymentModeId,
        ShipmentType shipmentType,
        LocalDate expectedDeliveryDate,
        BigDecimal actualWeight, BigDecimal volumetricWeight, BigDecimal chargeableWeight,
        BigDecimal declaredValue, Integer numberOfPackages,
        ShipmentStatus status,
        String remarks,
        boolean appointmentDelivery, LocalDate appointmentDate, String appointmentTimeSlot,
        boolean insuranceApplicable, DeliveryType deliveryType,
        Instant deliveredAt, String podPhotoUrl, String podSignatureUrl, String shipmentImageUrl,
        BigDecimal invoiceValue, boolean ewayBillRequired, EwayBillInfo ewayBill,
        UUID createdBy, String createdByName, Instant createdDate, UUID updatedBy, Instant updatedDate, Long version,
        @Schema(description = "STANDARD or MSRTC") String flowType,
        List<ShipmentItemResponse> items,
        @Schema(description = "Set when the shipment was handed to a courier partner from the hub") ForwardInfo forward,
        @Schema(description = "Set once a delivery attempt failed") UndeliveredInfo undelivered,
        @Schema(description = "Portal COD: the amount to collect from the receiver for the customer") BigDecimal codAmount,
        @Schema(description = "When the COD amount was collected (and paid into the customer's wallet)") Instant codCollectedAt,
        @Schema(description = "Set once a return to the sender started") RtoInfo rto
) {
    /** Return to the sender: when it started, whether it is back at the booking branch, and when / to whom it was handed back. */
    @Schema(name = "ShipmentRtoInfo")
    public record RtoInfo(Instant startedAt, boolean backAtOrigin, Instant deliveredAt, String handedTo) {
    }

    /** The last failed delivery attempt: why, when, how many so far, and whether it still waits for someone to act. */
    @Schema(name = "ShipmentUndeliveredInfo")
    public record UndeliveredInfo(int attempts, String reason, String reasonLabel, String note, Instant at, boolean open,
                                  @Schema(description = "3 attempts reached: staff should return it") boolean returnDue) {
    }

    /** The courier a hub handed the shipment to: its own AWB and label, what it charges us, and where the forward stands
     *  (SUBMITTING, UNKNOWN = needs checking with the courier, BOOKED). */
    @Schema(name = "ShipmentForwardInfo")
    public record ForwardInfo(String provider, String service, String state, String awb, String labelUrl, BigDecimal cost,
                              Instant forwardedAt, String note) {
    }

    /** The shipment's current E-Way Bill, read-only — plain fields only (no enum type),
     *  so this module never depends on {@code com.courier.modules.ewaybill}'s domain
     *  package, only its application interface (see {@code ShipmentMapper}). */
    @Schema(name = "ShipmentEwayBillInfo")
    public record EwayBillInfo(UUID id, String ewayBillNumber, String status, BigDecimal invoiceValue,
                               Instant validFrom, Instant validUntil, String documentUrl, String lastError) {
    }
}
