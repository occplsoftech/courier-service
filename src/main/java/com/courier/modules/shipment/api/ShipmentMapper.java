package com.courier.modules.shipment.api;

import com.courier.modules.ewaybill.application.EwayBillService;
import com.courier.modules.ewaybill.application.command.EwayBillDataCommand;
import com.courier.modules.shipment.api.dto.AddShipmentDocumentRequest;
import com.courier.modules.shipment.api.dto.BranchCommissionSummaryResponse;
import com.courier.modules.shipment.api.dto.BranchPerformanceSummaryResponse;
import com.courier.modules.shipment.api.dto.CreateShipmentRequest;
import com.courier.modules.shipment.api.dto.EwayBillBookingRequest;
import com.courier.modules.shipment.api.dto.ShipmentChargeResponse;
import com.courier.modules.shipment.api.dto.ShipmentDocumentResponse;
import com.courier.modules.shipment.api.dto.ShipmentItemRequest;
import com.courier.modules.shipment.api.dto.ShipmentItemResponse;
import com.courier.modules.shipment.api.dto.ShipmentResponse;
import com.courier.modules.shipment.api.dto.ShipmentSearchRequest;
import com.courier.modules.shipment.api.dto.ShipmentStatusHistoryResponse;
import com.courier.modules.shipment.api.dto.ShipmentSummaryResponse;
import com.courier.modules.shipment.api.dto.ShipmentSummaryStatsResponse;
import com.courier.modules.shipment.api.dto.UpdateShipmentRequest;
import com.courier.modules.shipment.application.ShipmentService;
import com.courier.modules.shipment.application.command.CreateShipmentCommand;
import com.courier.modules.shipment.application.command.ShipmentItemCommand;
import com.courier.modules.shipment.application.command.UpdateShipmentCommand;
import com.courier.modules.shipment.application.storage.SignedLinks;
import com.courier.modules.shipment.domain.BranchCommissionSummary;
import com.courier.modules.shipment.domain.BranchPerformanceSummary;
import com.courier.modules.shipment.domain.DeliveryAssignment;
import com.courier.modules.shipment.domain.Shipment;
import com.courier.modules.shipment.domain.ShipmentAsset;
import com.courier.modules.shipment.domain.ShipmentAssetType;
import com.courier.modules.shipment.domain.ShipmentCharge;
import com.courier.modules.shipment.domain.ShipmentCriteria;
import com.courier.modules.shipment.domain.ShipmentDocument;
import com.courier.modules.shipment.domain.ShipmentItem;
import com.courier.modules.shipment.domain.ShipmentStatusHistory;
import com.courier.modules.shipment.domain.UserLookupPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Wire contract <-> application/domain types for Shipment Booking. */
@Component
@RequiredArgsConstructor
public class ShipmentMapper {

    private final UserLookupPort userLookup;
    private final SignedLinks links;

    public CreateShipmentCommand toCommand(CreateShipmentRequest r) {
        return new CreateShipmentCommand(
                r.bookingBranchId(), r.manualShipmentNumber(),
                r.pickupPincode(), r.deliveryPincode(),
                r.senderName(), r.senderAddress(), r.senderContact(),
                r.receiverName(), r.receiverAddress(), r.receiverContact(),
                r.serviceTypeId(), r.packageTypeId(), r.paymentModeId(),
                r.shipmentType(), r.bookingDate(), r.declaredValue(), r.numberOfPackages(),
                r.remarks(), r.otherCharges(), r.odaCharge(), r.freightFactorOverride(), toItemCommands(r.items()),
                r.actualWeight(), r.length(), r.width(), r.height(),
                r.crossing(), r.crossingBranchIds(), r.crossingCharge(),
                r.invoiceValue(), toEwayBillData(r.ewayBill(), r.invoiceValue()),
                r.destinationAreaId(), r.ratePerKgOverride(),
                r.appointmentDelivery(), r.appointmentDate(), r.appointmentTimeSlot(),
                r.appointmentDeliveryCharge(), r.insuranceApplicable(),
                r.deliveryType(), r.doorDeliveryCharge(), r.flowType(), r.deliveryBranchId(),
                r.businessCustomerId());
    }

    public UpdateShipmentCommand toCommand(UpdateShipmentRequest r) {
        return new UpdateShipmentCommand(
                r.version(),
                r.pickupPincode(), r.deliveryPincode(),
                r.senderName(), r.senderAddress(), r.senderContact(),
                r.receiverName(), r.receiverAddress(), r.receiverContact(),
                r.serviceTypeId(), r.packageTypeId(), r.paymentModeId(),
                r.shipmentType(), r.bookingDate(), r.declaredValue(), r.numberOfPackages(),
                r.remarks(), r.otherCharges(), r.odaCharge(), r.freightFactorOverride(), toItemCommands(r.items()),
                r.actualWeight(), r.length(), r.width(), r.height(),
                r.invoiceValue(), toEwayBillData(r.ewayBill(), r.invoiceValue()),
                r.destinationAreaId(), r.ratePerKgOverride(),
                r.appointmentDelivery(), r.appointmentDate(), r.appointmentTimeSlot(),
                r.appointmentDeliveryCharge(), r.insuranceApplicable(),
                r.deliveryType(), r.doorDeliveryCharge());
    }

    /** The shipment's own invoiceValue rides along as the E-Way Bill's invoiceValue too —
     *  not asked for twice on the wire. */
    private EwayBillDataCommand toEwayBillData(EwayBillBookingRequest r, java.math.BigDecimal invoiceValue) {
        if (r == null) {
            return null;
        }
        return new EwayBillDataCommand(r.ewayBillNumber(), r.invoiceNumber(), r.invoiceDate(),
                invoiceValue, r.documentType(), r.documentNumber(), r.documentDate(),
                r.transporterId(), r.vehicleNumber(), r.distance(), r.validFrom(), r.validUntil(),
                r.documentUrl(), r.remarks(), r.consignorGstin(), r.consigneeGstin());
    }

    private List<ShipmentItemCommand> toItemCommands(List<ShipmentItemRequest> items) {
        if (items == null) {
            return List.of();
        }
        return items.stream().map(i -> new ShipmentItemCommand(
                i.itemName(), i.quantity(), i.weight(), i.lengthCm(), i.widthCm(), i.heightCm(),
                i.declaredValue(), i.fragile(), i.dangerousGoods())).toList();
    }

    public ShipmentCriteria toCriteria(ShipmentSearchRequest r) {
        ShipmentSearchRequest safe = r == null ? ShipmentSearchRequest.empty() : r;
        return new ShipmentCriteria(safe.status(), safe.bookingBranchId(), safe.deliveryBranchId(),
                safe.currentLocationId(), safe.nextLocationId(),
                safe.manifestId(), safe.bookingDateFrom(), safe.bookingDateTo(),
                safe.deliveredDateFrom(), safe.deliveredDateTo(), safe.paymentModeId(), safe.search(),
                safe.toCity(), safe.unassignedDeliveryBranch(), safe.flowType());
    }

    public ShipmentService.AddDocumentCommand toCommand(AddShipmentDocumentRequest r) {
        return new ShipmentService.AddDocumentCommand(
                r.documentType(), r.documentName(), r.documentUrl(), r.remarks());
    }

    public ShipmentResponse toResponse(Shipment s, List<ShipmentItem> items,
                                       EwayBillService.EwayBillSnapshot ewayBill) {
        return toResponse(s, items, null, List.of(), ewayBill);
    }

    public ShipmentResponse toResponse(Shipment s, List<ShipmentItem> items, DeliveryAssignment pod,
                                       List<ShipmentAsset> assets, EwayBillService.EwayBillSnapshot ewayBill) {
        return new ShipmentResponse(
                s.getId(), s.getCompanyId(), s.getShipmentNumber(), s.getTrackingNumber(),
                s.getBookingDate(), s.getBookingBranchId(), s.getDeliveryBranchId(), s.getManifestId(),
                s.getFromCity(), s.getToCity(),
                s.getCurrentLocationId(), s.getNextLocationId(),
                s.getPickupPincode(), s.getDeliveryPincode(), s.getDestinationAreaId(),
                s.getSenderName(), s.getSenderAddress(), s.getSenderContact(),
                s.getReceiverName(), s.getReceiverAddress(), s.getReceiverContact(),
                s.getServiceTypeId(), s.getPackageTypeId(), s.getPaymentModeId(),
                s.getShipmentType(), s.getExpectedDeliveryDate(),
                s.getActualWeight(), s.getVolumetricWeight(), s.getChargeableWeight(),
                s.getDeclaredValue(), s.getNumberOfPackages(), s.getStatus(), s.getRemarks(),
                s.isAppointmentDelivery(), s.getAppointmentDate(), s.getAppointmentTimeSlot(),
                s.isInsuranceApplicable(), s.getDeliveryType(),
                pod != null ? pod.getDeliveredAt() : null,
                latestAssetUrl(assets, ShipmentAssetType.POD, "PHOTO"),
                latestAssetUrl(assets, ShipmentAssetType.POD, "SIGNATURE"),
                latestAssetUrl(assets, ShipmentAssetType.BOOKING, "PHOTO"),
                s.getInvoiceValue(), s.isEwayBillRequired(), toEwayBillInfo(ewayBill),
                s.getCreatedBy(), createdByName(s), s.getCreatedAt(), s.getUpdatedBy(), s.getUpdatedAt(), s.getVersion(),
                s.getFlowType() == null ? null : s.getFlowType().name(),
                items.stream().map(this::toResponse).toList());
    }

    private String createdByName(Shipment s) {
        return userLookup.findUser(s.getCreatedBy(), s.getCompanyId())
                .map(UserLookupPort.UserRef::fullName)
                .orElse(null);
    }

    private ShipmentResponse.EwayBillInfo toEwayBillInfo(EwayBillService.EwayBillSnapshot s) {
        if (s == null) {
            return null;
        }
        return new ShipmentResponse.EwayBillInfo(s.id(), s.ewayBillNumber(), s.status(),
                s.invoiceValue(), s.validFrom(), s.validUntil(), links.of(s.documentUrl()), s.lastError());
    }

    /** Assets arrive newest-first ({@code ShipmentAssetRepository}'s own ordering) — the
     *  first match for this (type, kind) pair is the current one. */
    private String latestAssetUrl(List<ShipmentAsset> assets, ShipmentAssetType type, String kind) {
        return assets.stream()
                .filter(a -> a.getAssetType() == type && kind.equals(a.getKind()))
                .findFirst()
                .map(ShipmentAsset::getAssetUrl)
                .map(links::of)
                .orElse(null);
    }

    public ShipmentSummaryStatsResponse toSummaryStats(com.courier.modules.shipment.domain.ShipmentSummaryStats s) {
        return new ShipmentSummaryStatsResponse(
                s.totalCount(), s.totalChargeableWeight(), s.totalNetAmount(), s.statusCounts());
    }

    public BranchCommissionSummaryResponse toCommissionSummary(BranchCommissionSummary s) {
        return new BranchCommissionSummaryResponse(s.bookingBranchId(), s.shipmentCount(), s.totalNetAmount(),
                s.commissionOnBasicFreight(), s.branchCommissionOnOtherAmount(),
                s.companyCommissionOnBasicFreight(), s.totalCommission());
    }

    public BranchPerformanceSummaryResponse toBranchPerformance(BranchPerformanceSummary s) {
        return new BranchPerformanceSummaryResponse(s.bookingBranchId(), s.shipmentCount(), s.deliveredCount(),
                s.inTransitCount(), s.returnedCount(), s.cancelledCount(),
                s.totalChargeableWeight(), s.totalNetAmount());
    }

    public com.courier.modules.shipment.api.dto.VendorAuditRowResponse toVendorAuditRow(
            com.courier.modules.shipment.domain.VendorAuditRow r) {
        return new com.courier.modules.shipment.api.dto.VendorAuditRowResponse(
                r.branchId(), r.paidOrderCount(), r.paidOrderQuantity(), r.paidOrderAmount(),
                r.topayOrderCount(), r.topayOrderQuantity(), r.topayOrderAmount(),
                r.paidCommission(), r.deliveryCommission(), r.totalBookedOrderCount(), r.totalDeliveredOrderCount(),
                r.bookingTotalCommission(), r.deliveryTotalCommission(),
                r.odaCharges(), r.otherCharges(), r.cancelledOrderCount());
    }

    public ShipmentSummaryResponse toSummary(Shipment s, BigDecimal netAmount, ShipmentCharge charge,
                                             Instant deliveredAt, String invoiceNumber, Instant receivedAt,
                                             String ewayBillNumber) {
        return new ShipmentSummaryResponse(
                s.getId(), s.getShipmentNumber(), s.getTrackingNumber(), s.getBookingDate(),
                s.getBookingBranchId(), s.getDeliveryBranchId(), s.getCurrentLocationId(), s.getNextLocationId(),
                s.getFromCity(), s.getToCity(),
                s.getManifestId(), s.getPaymentModeId(), s.getDeliveryType(),
                s.getSenderName(), s.getSenderContact(), s.getReceiverName(), s.getReceiverContact(),
                s.getChargeableWeight(), netAmount,
                charge == null ? null : charge.getTotalCommission(),
                charge == null ? null : charge.getCommissionOnBasicFreight(),
                charge == null ? null : charge.getBranchCommissionOnOtherAmount(),
                charge == null ? null : charge.getCompanyCommissionOnBasicFreight(),
                s.getStatus(), deliveredAt, s.getCreatedAt(), s.getVersion(), invoiceNumber, receivedAt,
                ewayBillNumber, s.getNumberOfPackages());
    }

    public ShipmentItemResponse toResponse(ShipmentItem i) {
        return new ShipmentItemResponse(
                i.getId(), i.getItemName(), i.getQuantity(), i.getWeight(),
                i.getLengthCm(), i.getWidthCm(), i.getHeightCm(),
                i.getDeclaredValue(), i.isFragile(), i.isDangerousGoods());
    }

    public ShipmentChargeResponse toResponse(ShipmentService.ShipmentCharges c) {
        return new ShipmentChargeResponse(
                c.charge().getShipmentId(),
                c.charge().getFreight(), c.charge().getFuelCharge(), c.charge().getHandlingCharge(),
                c.charge().getOdaCharge(), c.charge().getInsuranceCharge(), c.charge().getApplicableCharges(),
                c.applicableChargeLines().stream()
                        .map(l -> new ShipmentChargeResponse.ApplicableChargeLine(l.chargeName(), l.amount()))
                        .toList(),
                c.charge().getGstAmount(),
                c.charge().getDiscountAmount(), c.charge().getRoundOff(), c.charge().getOtherCharges(),
                c.charge().getAppointmentDeliveryCharge(), c.charge().getDoorDeliveryCharge(),
                c.charge().getToPayCharge(),
                c.charge().getCommissionOnBasicFreight(), c.charge().getBranchCommissionOnOtherAmount(),
                c.charge().getCompanyCommissionOnBasicFreight(), c.charge().getTotalCommission(),
                c.charge().getNetAmount(),
                c.charge().getMatchedRouteId(), c.matchedRouteCode(),
                c.charge().getMatchedRateId(), c.matchedRateCode(),
                c.charge().getAppliedFreightFactor());
    }

    public ShipmentStatusHistoryResponse toResponse(ShipmentStatusHistory h) {
        return new ShipmentStatusHistoryResponse(
                h.getId(), h.getStatus(), h.getPreviousStatus(), h.getRemarks(),
                h.getBranchId(), h.getManifestId(), h.getVehicleId(),
                h.getChangedBy(), h.getChangedAt());
    }

    public com.courier.modules.shipment.api.dto.BulkMovementResponse toResponse(
            ShipmentService.BulkMovementResult r) {
        List<com.courier.modules.shipment.api.dto.MovementOutcomeResponse> results = r.results().stream()
                .map(o -> new com.courier.modules.shipment.api.dto.MovementOutcomeResponse(
                        o.reference(), o.success(), o.message()))
                .toList();
        return new com.courier.modules.shipment.api.dto.BulkMovementResponse(
                results, r.successCount(), r.failureCount(), r.drsNumber(), r.shortageTicketNumber());
    }

    public com.courier.modules.shipment.api.dto.TimelineStepResponse toResponse(ShipmentService.TimelineStep s) {
        return new com.courier.modules.shipment.api.dto.TimelineStepResponse(
                s.status(), s.label(), s.changedAt(), s.changedBy(), s.completed());
    }

    public com.courier.modules.shipment.api.dto.DrsSummaryResponse toResponse(ShipmentService.DrsSummary s) {
        return new com.courier.modules.shipment.api.dto.DrsSummaryResponse(
                s.deliveryUserId(), s.deliveryBranchId(), s.runDate(), s.drsNumber(),
                s.shipmentCount(), s.deliveredCount(), s.pendingCount());
    }

    public com.courier.modules.shipment.api.dto.DrsDetailResponse toResponse(ShipmentService.DrsDetail d) {
        return new com.courier.modules.shipment.api.dto.DrsDetailResponse(
                d.deliveryUserId(), d.deliveryBranchId(), d.runDate(), d.drsNumber(),
                d.shipments().stream().map(this::toResponse).toList());
    }

    private com.courier.modules.shipment.api.dto.DrsShipmentRowResponse toResponse(
            ShipmentService.DrsShipmentRow r) {
        return new com.courier.modules.shipment.api.dto.DrsShipmentRowResponse(
                r.shipmentId(), r.shipmentNumber(), r.trackingNumber(),
                r.receiverName(), r.receiverAddress(), r.receiverContact(), r.paymentModeId(),
                r.netAmount(), r.status(), r.deliveredAt(), r.ewayBillNumber(), r.fromCity(), r.toCity());
    }

    public ShipmentDocumentResponse toResponse(ShipmentDocument d) {
        return new ShipmentDocumentResponse(
                d.getId(), d.getDocumentType(), d.getDocumentName(), links.of(d.getDocumentUrl()),
                d.getRemarks(), d.getCreatedBy(), d.getCreatedAt());
    }
}
