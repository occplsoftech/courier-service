package com.courier.modules.shipment.api.dto;

import com.courier.modules.shipment.domain.DeliveryType;
import com.courier.modules.shipment.domain.FlowType;
import com.courier.modules.shipment.domain.ShipmentType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Body of {@code POST /api/v1/shipments}.
 *
 * <p>Not accepted: {@code companyId} (from the JWT), {@code trackingNumber} (always
 * generated), {@code status} (a new shipment starts {@code BOOKED}), {@code
 * deliveryBranchId} (not decided at booking at all any more — a real delivery branch,
 * or Direct Company Delivery, is assigned later at Load Sheet; see
 * {@code ShipmentServiceImpl}), {@code actualWeight}/{@code volumetricWeight}/
 * {@code chargeableWeight} as top-level writes (computed from {@code items}; the bare
 * {@code actualWeight}/dimension fields here are the fallback used only when
 * {@code items} is empty — see {@code ShipmentServiceImpl}).
 *
 * <p>{@code declaredValue} is mandatory and must equal {@code invoiceValue} whenever both
 * are supplied — the frontend derives both as the same sum of every item's own declared
 * value, on direct request, so they can never legitimately differ; see
 * {@code Shipment.applyInvariants}.
 */
@Schema(name = "CreateShipmentRequest", description = "New shipment booking")
public record CreateShipmentRequest(
        @NotNull UUID bookingBranchId,
        @Schema(description = "Optional — enter a specific shipment number instead of "
                + "the auto-generated \"<BRANCH_CODE>-<serial>\" one. Must be unique "
                + "within the company; booking is refused with a 422 if it's already in use.")
        @Size(max = 30) String manualShipmentNumber,
        @NotBlank @Size(max = 10) String pickupPincode,
        @NotBlank @Size(max = 10) String deliveryPincode,
        @NotBlank @Size(max = 150) String senderName,
        @NotBlank @Size(max = 500) String senderAddress,
        @NotBlank @Size(max = 20) String senderContact,
        @NotBlank @Size(max = 150) String receiverName,
        @NotBlank @Size(max = 500) String receiverAddress,
        @NotBlank @Size(max = 20) String receiverContact,
        @NotNull UUID serviceTypeId,
        @Schema(description = "No longer picked at booking — the concept never priced "
                + "anything once Route/Rate's old branch-pair matching stopped running "
                + "(delivery branch is never known at booking any more). Left optional, "
                + "not removed outright, so an existing integration that still sends one "
                + "keeps working.")
        UUID packageTypeId,
        @NotNull UUID paymentModeId,
        ShipmentType shipmentType,
        @Schema(description = "Defaults to today") LocalDate bookingDate,
        @NotNull(message = "Declared Value is required")
        @DecimalMin(value = "0.0", inclusive = false, message = "must be greater than zero")
        BigDecimal declaredValue,
        @Min(1) Integer numberOfPackages,
        @Size(max = 500) String remarks,
        @DecimalMin(value = "0") BigDecimal otherCharges,
        @Schema(description = "Optional override of the Pricing Engine's own ODA charge — "
                + "typed at booking time when the operator needs to adjust it. Null uses the "
                + "engine's own computed value unchanged. GST is recomputed on the difference "
                + "at the booking branch's GST percentage.")
        @DecimalMin(value = "0") BigDecimal odaCharge,
        @Schema(description = "Only meaningful when this lane falls back to the Freight "
                + "Factor grid (no route/rate available) — raises the matched cell's own "
                + "factor. Must be greater than or equal to the matched factor.")
        @DecimalMin(value = "0.0", inclusive = false, message = "must be greater than zero")
        BigDecimal freightFactorOverride,
        @Valid @Schema(description = "The packed item grid; may be empty if actualWeight is supplied instead")
        List<ShipmentItemRequest> items,
        @Schema(description = "Required only when items is empty") BigDecimal actualWeight,
        BigDecimal length,
        BigDecimal width,
        BigDecimal height,
        @Schema(description = "Route this shipment through one or more intermediate "
                + "branches/hubs, in order, instead of straight to the delivery branch. "
                + "When true, crossingBranchIds must carry at least one branch.")
        Boolean crossing,
        @Schema(description = "Required when crossing is true — the intermediate "
                + "branches/hubs, in the order the shipment passes through them")
        List<UUID> crossingBranchIds,
        @Schema(description = "The whole route's crossing charge — not per hop")
        @DecimalMin(value = "0") BigDecimal crossingCharge,
        @Schema(description = "Drives whether an E-Way Bill is mandatory before AWB "
                + "generation — see GET /company-settings for the company's own threshold "
                + "(default 50000.00). Null is never mandatory.")
        @DecimalMin(value = "0") BigDecimal invoiceValue,
        @Valid
        @Schema(description = "Required when invoiceValue exceeds the mandatory threshold "
                + "— booking is refused with a 422 otherwise. Optional and simply attached "
                + "when supplied below the threshold.")
        EwayBillBookingRequest ewayBill,
        @Schema(description = "The specific Area of deliveryPincode picked from its Area "
                + "dropdown, if any. Resolves District Level Freight's District/ODA off that "
                + "exact pincode-area link instead of the pincode's legacy single area.")
        UUID destinationAreaId,
        @Schema(description = "Optional override of District Level Freight's own matched-slab "
                + "rate/KG. Must not be lower than that rate — refused with a 422 otherwise. "
                + "A higher figure raises freight (and its GST, on the difference only) above "
                + "the system-calculated figure.")
        @DecimalMin(value = "0.0", inclusive = false, message = "must be greater than zero")
        BigDecimal ratePerKgOverride,
        @Schema(description = "When true, appointmentDate/appointmentTimeSlot are required "
                + "— booking is refused with a 422 otherwise.")
        Boolean appointmentDelivery,
        @Schema(description = "Required only when appointmentDelivery is true")
        LocalDate appointmentDate,
        @Schema(description = "Free text, e.g. \"1:00-2:00\" — required only when "
                + "appointmentDelivery is true")
        @Size(max = 20) String appointmentTimeSlot,
        @Schema(description = "Optional, defaults to zero. Deliberately never taxed with "
                + "GST, unlike otherCharges.")
        @DecimalMin(value = "0") BigDecimal appointmentDeliveryCharge,
        @Schema(description = "When true, insurance is charged at 2% of freight instead of "
                + "the Pricing Engine's own rate-driven insurance figure. GST is recomputed "
                + "on the difference, same as odaCharge.")
        Boolean insuranceApplicable,
        @Schema(description = "Defaults to DOOR. OFFICE never charges extra — "
                + "doorDeliveryCharge is ignored server-side when this is OFFICE.")
        DeliveryType deliveryType,
        @Schema(description = "Optional, defaults to zero, meaningful only when deliveryType "
                + "is DOOR. Deliberately never taxed with GST, unlike otherCharges.")
        @DecimalMin(value = "0") BigDecimal doorDeliveryCharge,
        @Schema(description = "Omit (or STANDARD) for the normal booking flow. MSRTC books straight "
                + "to deliveryBranchId, which is then required.")
        FlowType flowType,
        @Schema(description = "Required when flowType is MSRTC; ignored otherwise.")
        UUID deliveryBranchId,
        @Schema(description = "The Business Customer this booking is for. When that customer has an "
                + "ACTIVE pricing zone covering the destination, freight and ODA are priced from the "
                + "zone's rate card and its docket/mathadi/pack/fuel/FOV lines become the minimum "
                + "otherCharges. Omit for an ordinary booking.")
        UUID businessCustomerId,
        @Schema(description = "Cash on Delivery only: the amount the rider collects from the consignee for the shipper. "
                + "Required (above 0) on a COD payment mode; ignored on any other. Adds a COD charge of 2% of it, at least 100.")
        @DecimalMin(value = "0") BigDecimal codAmount,
        @Schema(description = "Paid bookings only: how the customer pays at the counter, RAZORPAY, CASH (default) or UPI_QR. "
                + "RAZORPAY books with the payment pending (open the QR with POST /shipments/{id}/payment/qr) and does not "
                + "debit the branch wallet.")
        com.courier.modules.shipment.domain.ShipmentPayment.Method paymentMethod,
        @Schema(description = "UPI_QR: the UPI reference (UTR) the customer's app shows. Optional.")
        @Size(max = 100) String paymentReference,
        @Schema(description = "The final amount (GST included) the operator typed in place of the system's. Optional. "
                + "Allowed between the company's Net Amount limits (Settings → Finance: max % lower / higher) and "
                + "needs finalAmountReason. Freight is worked back from it at the company GST %; GST is the rest.")
        @DecimalMin(value = "0.0", inclusive = false, message = "must be greater than zero") BigDecimal finalAmount,
        @Schema(description = "Why the final amount was changed. Required with finalAmount.")
        @Size(max = 255) String finalAmountReason,
        @Schema(description = "Charges Master charges the clerk ticked: [{code, amount}], code HANDLING, PICKUP, DELIVERY, AWB, "
                + "GREEN_TAX, REVERSE_PICKUP, COD or OTHER; amount optional (as calculated; COD is always calculated). "
                + "Omit on a screen without them: COD then comes from the Charges Master.")
        @jakarta.validation.Valid java.util.List<AdditionalCharge> additionalCharges
) {
    /** One ticked Charges Master charge. */
    public record AdditionalCharge(
            @jakarta.validation.constraints.NotNull com.courier.modules.charge.domain.BookingChargeCode code,
            @DecimalMin(value = "0") BigDecimal amount) {
    }
}
