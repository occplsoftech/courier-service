package com.courier.modules.shipment.application.command;

import com.courier.modules.ewaybill.application.command.EwayBillDataCommand;
import com.courier.modules.shipment.domain.DeliveryType;
import com.courier.modules.shipment.domain.FlowType;
import com.courier.modules.shipment.domain.ShipmentType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Everything Shipment Booking needs to book one shipment.
 *
 * @param manualShipmentNumber optional — used verbatim instead of the auto-generated
 *                              shipment number when supplied; must be unique within the
 *                              company
 * @param bookingDate      defaults to today when null, the same convention Rate Master's
 *                         and the Pricing Engine's own calculators use
 * @param items            the packed item grid; when empty, {@code actualWeight} (and
 *                         optionally the three dimensions, for a volumetric contribution)
 *                         must be supplied directly — a single-package booking with
 *                         nothing itemised is not refused, it just has one implicit item
 * @param actualWeight     required only when {@code items} is empty
 * @param length           optional, used only when {@code items} is empty
 * @param width            optional, used only when {@code items} is empty
 * @param height           optional, used only when {@code items} is empty
 * @param crossing          routes this shipment through {@code crossingBranchIds} in order,
 *                          instead of straight to the delivery branch
 * @param crossingBranchIds required, at least one branch, when {@code crossing} is true —
 *                          the intermediate hubs in the order the shipment passes through them
 * @param crossingCharge    the whole route's crossing charge (not per hop); defaults to
 *                          zero when {@code crossing} is true and this is null
 * @param invoiceValue      drives whether an E-Way Bill is mandatory — see
 *                          {@code EwayBillService.isRequired}; null is never mandatory
 * @param ewayBill          the E-Way Bill's own data, required when {@code invoiceValue}
 *                          exceeds the company's threshold (booking is refused otherwise),
 *                          optional and simply attached-if-given below it
 * @param destinationAreaId the specific Area of {@code deliveryPincode} the operator picked
 *                          from its Area dropdown, if any — resolves District Level
 *                          Freight's District/ODA off that exact pincode-area link. Null
 *                          falls back to the pincode's own legacy single area.
 * @param ratePerKgOverride optional operator override of District Level Freight's own
 *                          matched-slab rate/KG — must not be lower than that rate (refused
 *                          otherwise); a higher figure raises {@code freight} (and its GST,
 *                          on the difference only, same delta-algebra {@code odaCharge}
 *                          already uses) above the system-calculated figure. Null keeps the
 *                          system rate unchanged.
 * @param appointmentDelivery when true, {@code appointmentDate}/{@code appointmentTimeSlot}
 *                          are required — refused otherwise
 * @param appointmentDate   required only when {@code appointmentDelivery} is true
 * @param appointmentTimeSlot free text, e.g. {@code "1:00-2:00"} — required only when
 *                          {@code appointmentDelivery} is true
 * @param appointmentDeliveryCharge optional, defaults to zero — deliberately never taxed
 *                          with GST, unlike {@code otherCharges}
 * @param insuranceApplicable when true, insurance is charged at 2% of freight instead of
 *                          the Pricing Engine's own rate-driven insurance figure — same
 *                          delta-algebra as {@code odaCharge}, taxed like {@code otherCharges}
 * @param deliveryType      defaults to {@code DOOR} when null; {@code OFFICE} never charges
 *                          extra — {@code doorDeliveryCharge} is ignored server-side
 * @param doorDeliveryCharge optional, defaults to zero, meaningful only when {@code
 *                          deliveryType} is {@code DOOR} — deliberately never taxed with
 *                          GST, unlike {@code otherCharges}
 * @param flowType          null means {@link FlowType#STANDARD}; {@link FlowType#MSRTC}
 *                          books straight to {@code deliveryBranchId}
 * @param deliveryBranchId  required for {@link FlowType#MSRTC}, ignored for STANDARD (whose
 *                          delivery branch is assigned later, by Load Sheet)
 */
public record CreateShipmentCommand(
        UUID bookingBranchId,
        String manualShipmentNumber,
        String pickupPincode,
        String deliveryPincode,
        String senderName,
        String senderAddress,
        String senderContact,
        String receiverName,
        String receiverAddress,
        String receiverContact,
        UUID serviceTypeId,
        UUID packageTypeId,
        UUID paymentModeId,
        ShipmentType shipmentType,
        LocalDate bookingDate,
        BigDecimal declaredValue,
        Integer numberOfPackages,
        String remarks,
        BigDecimal otherCharges,
        BigDecimal odaCharge,
        BigDecimal freightFactorOverride,
        List<ShipmentItemCommand> items,
        BigDecimal actualWeight,
        BigDecimal length,
        BigDecimal width,
        BigDecimal height,
        Boolean crossing,
        List<UUID> crossingBranchIds,
        BigDecimal crossingCharge,
        BigDecimal invoiceValue,
        EwayBillDataCommand ewayBill,
        UUID destinationAreaId,
        BigDecimal ratePerKgOverride,
        Boolean appointmentDelivery,
        LocalDate appointmentDate,
        String appointmentTimeSlot,
        BigDecimal appointmentDeliveryCharge,
        Boolean insuranceApplicable,
        DeliveryType deliveryType,
        BigDecimal doorDeliveryCharge,
        FlowType flowType,
        UUID deliveryBranchId,
        UUID businessCustomerId,
        BigDecimal codAmount,
        BigDecimal finalAmount,
        String finalAmountReason
) {
    /** Without an edited final amount: the system's amount is charged. */
    public CreateShipmentCommand(
            UUID bookingBranchId, String manualShipmentNumber, String pickupPincode, String deliveryPincode,
            String senderName, String senderAddress, String senderContact,
            String receiverName, String receiverAddress, String receiverContact,
            UUID serviceTypeId, UUID packageTypeId, UUID paymentModeId, ShipmentType shipmentType,
            LocalDate bookingDate, BigDecimal declaredValue, Integer numberOfPackages, String remarks,
            BigDecimal otherCharges, BigDecimal odaCharge, BigDecimal freightFactorOverride,
            List<ShipmentItemCommand> items, BigDecimal actualWeight, BigDecimal length, BigDecimal width,
            BigDecimal height, Boolean crossing, List<UUID> crossingBranchIds, BigDecimal crossingCharge,
            BigDecimal invoiceValue, EwayBillDataCommand ewayBill, UUID destinationAreaId,
            BigDecimal ratePerKgOverride, Boolean appointmentDelivery, LocalDate appointmentDate,
            String appointmentTimeSlot, BigDecimal appointmentDeliveryCharge, Boolean insuranceApplicable,
            DeliveryType deliveryType, BigDecimal doorDeliveryCharge, FlowType flowType, UUID deliveryBranchId,
            UUID businessCustomerId, BigDecimal codAmount) {
        this(bookingBranchId, manualShipmentNumber, pickupPincode, deliveryPincode, senderName, senderAddress,
                senderContact, receiverName, receiverAddress, receiverContact, serviceTypeId, packageTypeId,
                paymentModeId, shipmentType, bookingDate, declaredValue, numberOfPackages, remarks, otherCharges,
                odaCharge, freightFactorOverride, items, actualWeight, length, width, height, crossing,
                crossingBranchIds, crossingCharge, invoiceValue, ewayBill, destinationAreaId, ratePerKgOverride,
                appointmentDelivery, appointmentDate, appointmentTimeSlot, appointmentDeliveryCharge,
                insuranceApplicable, deliveryType, doorDeliveryCharge, flowType, deliveryBranchId, businessCustomerId,
                codAmount, null, null);
    }

    /** Without a COD collection amount (every payment mode but COD). */
    public CreateShipmentCommand(
            UUID bookingBranchId, String manualShipmentNumber, String pickupPincode, String deliveryPincode,
            String senderName, String senderAddress, String senderContact,
            String receiverName, String receiverAddress, String receiverContact,
            UUID serviceTypeId, UUID packageTypeId, UUID paymentModeId, ShipmentType shipmentType,
            LocalDate bookingDate, BigDecimal declaredValue, Integer numberOfPackages, String remarks,
            BigDecimal otherCharges, BigDecimal odaCharge, BigDecimal freightFactorOverride,
            List<ShipmentItemCommand> items, BigDecimal actualWeight, BigDecimal length, BigDecimal width,
            BigDecimal height, Boolean crossing, List<UUID> crossingBranchIds, BigDecimal crossingCharge,
            BigDecimal invoiceValue, EwayBillDataCommand ewayBill, UUID destinationAreaId,
            BigDecimal ratePerKgOverride, Boolean appointmentDelivery, LocalDate appointmentDate,
            String appointmentTimeSlot, BigDecimal appointmentDeliveryCharge, Boolean insuranceApplicable,
            DeliveryType deliveryType, BigDecimal doorDeliveryCharge, FlowType flowType, UUID deliveryBranchId,
            UUID businessCustomerId) {
        this(bookingBranchId, manualShipmentNumber, pickupPincode, deliveryPincode, senderName, senderAddress,
                senderContact, receiverName, receiverAddress, receiverContact, serviceTypeId, packageTypeId,
                paymentModeId, shipmentType, bookingDate, declaredValue, numberOfPackages, remarks, otherCharges,
                odaCharge, freightFactorOverride, items, actualWeight, length, width, height, crossing,
                crossingBranchIds, crossingCharge, invoiceValue, ewayBill, destinationAreaId, ratePerKgOverride,
                appointmentDelivery, appointmentDate, appointmentTimeSlot, appointmentDeliveryCharge,
                insuranceApplicable, deliveryType, doorDeliveryCharge, flowType, deliveryBranchId, businessCustomerId, null);
    }

    /** The original shape, before MSRTC: a STANDARD booking with no delivery branch. */
    public CreateShipmentCommand(
            UUID bookingBranchId, String manualShipmentNumber, String pickupPincode, String deliveryPincode,
            String senderName, String senderAddress, String senderContact,
            String receiverName, String receiverAddress, String receiverContact,
            UUID serviceTypeId, UUID packageTypeId, UUID paymentModeId, ShipmentType shipmentType,
            LocalDate bookingDate, BigDecimal declaredValue, Integer numberOfPackages, String remarks,
            BigDecimal otherCharges, BigDecimal odaCharge, BigDecimal freightFactorOverride,
            List<ShipmentItemCommand> items, BigDecimal actualWeight, BigDecimal length, BigDecimal width,
            BigDecimal height, Boolean crossing, List<UUID> crossingBranchIds, BigDecimal crossingCharge,
            BigDecimal invoiceValue, EwayBillDataCommand ewayBill, UUID destinationAreaId,
            BigDecimal ratePerKgOverride, Boolean appointmentDelivery, LocalDate appointmentDate,
            String appointmentTimeSlot, BigDecimal appointmentDeliveryCharge, Boolean insuranceApplicable,
            DeliveryType deliveryType, BigDecimal doorDeliveryCharge) {
        this(bookingBranchId, manualShipmentNumber, pickupPincode, deliveryPincode, senderName, senderAddress,
                senderContact, receiverName, receiverAddress, receiverContact, serviceTypeId, packageTypeId,
                paymentModeId, shipmentType, bookingDate, declaredValue, numberOfPackages, remarks, otherCharges,
                odaCharge, freightFactorOverride, items, actualWeight, length, width, height, crossing,
                crossingBranchIds, crossingCharge, invoiceValue, ewayBill, destinationAreaId, ratePerKgOverride,
                appointmentDelivery, appointmentDate, appointmentTimeSlot, appointmentDeliveryCharge,
                insuranceApplicable, deliveryType, doorDeliveryCharge, null, null, null, null);
    }

    /** The shape before Business Customer pricing: an ordinary booking, no customer. */
    public CreateShipmentCommand(
            UUID bookingBranchId, String manualShipmentNumber, String pickupPincode, String deliveryPincode,
            String senderName, String senderAddress, String senderContact,
            String receiverName, String receiverAddress, String receiverContact,
            UUID serviceTypeId, UUID packageTypeId, UUID paymentModeId, ShipmentType shipmentType,
            LocalDate bookingDate, BigDecimal declaredValue, Integer numberOfPackages, String remarks,
            BigDecimal otherCharges, BigDecimal odaCharge, BigDecimal freightFactorOverride,
            List<ShipmentItemCommand> items, BigDecimal actualWeight, BigDecimal length, BigDecimal width,
            BigDecimal height, Boolean crossing, List<UUID> crossingBranchIds, BigDecimal crossingCharge,
            BigDecimal invoiceValue, EwayBillDataCommand ewayBill, UUID destinationAreaId,
            BigDecimal ratePerKgOverride, Boolean appointmentDelivery, LocalDate appointmentDate,
            String appointmentTimeSlot, BigDecimal appointmentDeliveryCharge, Boolean insuranceApplicable,
            DeliveryType deliveryType, BigDecimal doorDeliveryCharge, FlowType flowType, UUID deliveryBranchId) {
        this(bookingBranchId, manualShipmentNumber, pickupPincode, deliveryPincode, senderName, senderAddress,
                senderContact, receiverName, receiverAddress, receiverContact, serviceTypeId, packageTypeId,
                paymentModeId, shipmentType, bookingDate, declaredValue, numberOfPackages, remarks, otherCharges,
                odaCharge, freightFactorOverride, items, actualWeight, length, width, height, crossing,
                crossingBranchIds, crossingCharge, invoiceValue, ewayBill, destinationAreaId, ratePerKgOverride,
                appointmentDelivery, appointmentDate, appointmentTimeSlot, appointmentDeliveryCharge,
                insuranceApplicable, deliveryType, doorDeliveryCharge, flowType, deliveryBranchId, null, null);
    }
}
