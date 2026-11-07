package com.courier.modules.shipment.api;

import com.courier.modules.manifest.application.ManifestService;
import com.courier.modules.manifest.application.VehicleService;
import com.courier.modules.manifest.domain.Manifest;
import com.courier.modules.manifest.domain.Vehicle;
import com.courier.modules.shipment.api.dto.BulkMovementResponse;
import com.courier.modules.shipment.api.dto.DeliverRequest;
import com.courier.modules.shipment.api.dto.DeliveryOtpRequestRequest;
import com.courier.modules.shipment.api.dto.DeliveryOtpRequestResponse;
import com.courier.modules.shipment.api.dto.DeliveryOtpVerifyRequest;
import com.courier.modules.shipment.api.dto.DispatchManifestRequest;
import com.courier.modules.shipment.api.dto.DispatchManifestResponse;
import com.courier.modules.shipment.api.dto.DispatchOtpRequestRequest;
import com.courier.modules.shipment.api.dto.DispatchOtpRequestResponse;
import com.courier.modules.shipment.api.dto.DispatchOtpVerifyRequest;
import com.courier.modules.shipment.api.dto.InScanRequest;
import com.courier.modules.shipment.api.dto.OutForDeliveryRequest;
import com.courier.modules.shipment.api.dto.OverrideStatusRequest;
import com.courier.modules.shipment.api.dto.OverrideStatusResponse;
import com.courier.modules.shipment.api.dto.PodUploadResponse;
import com.courier.modules.shipment.api.dto.ShipmentResponse;
import com.courier.modules.shipment.application.ShipmentService;
import com.courier.modules.shipment.domain.Shipment;
import com.courier.modules.shipment.domain.ShipmentCriteria;
import com.courier.modules.shipment.domain.ShipmentStatus;
import com.courier.shared.api.ApiResponse;
import com.courier.shared.exception.BusinessRuleException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

/**
 * The four remaining movement steps, in business-flow order: Dispatch -&gt; In Scan -&gt;
 * Out For Delivery -&gt; Deliver. Out Scan is gone as its own step (V20, on direct
 * request) — adding a shipment to a manifest already is "loading sheet created", so Dispatch
 * now reads {@code MANIFEST_CREATED} shipments directly rather than a separately-scanned
 * subset. Every write is a {@link ShipmentService} method except Dispatch, which is
 * {@link ManifestService}'s own (it owns the manifest-side mutation) — see
 * {@code ManifestServiceImpl}'s class doc for why the dependency runs only that one
 * direction. {@code GET /shipments/{id}/timeline} lives on {@link ShipmentController}
 * instead, alongside the shipment it belongs to.
 */
@RestController
@RequestMapping("/api/v1/shipment-movement")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Shipment Movement", description = "Dispatch, In Scan, Out For Delivery, Deliver")
public class ShipmentMovementController {

    private final ShipmentService shipmentService;
    private final ManifestService manifestService;
    private final VehicleService vehicleService;
    private final ShipmentMapper shipmentMapper;

    @PostMapping("/dispatch-otp/request")
    @Operation(summary = "Request a driver OTP before dispatch (optional)",
            description = "Sends a 4-digit OTP to the assigned driver's mobile over the company's "
                    + "configured SMS channel. Verifying it (dispatch-otp/verify) is currently optional "
                    + "and does not gate /dispatch.")
    public ApiResponse<DispatchOtpRequestResponse> requestDispatchOtp(
            @Valid @RequestBody DispatchOtpRequestRequest request) {
        var issued = manifestService.requestDispatchOtp(request.manifestId(), request.driverUserId());
        return ApiResponse.success(new DispatchOtpRequestResponse(issued.maskedMobile(), issued.expiresInMinutes()),
                "OTP sent to " + issued.maskedMobile());
    }

    @PostMapping("/dispatch-otp/verify")
    @Operation(summary = "Verify the driver OTP",
            description = "A wrong code counts against a small attempt limit before the code is "
                    + "invalidated and a fresh one must be requested.")
    public ApiResponse<Void> verifyDispatchOtp(@Valid @RequestBody DispatchOtpVerifyRequest request) {
        manifestService.verifyDispatchOtp(request.manifestId(), request.driverUserId(), request.otp());
        return ApiResponse.success("OTP verified");
    }

    @PostMapping("/dispatch")
    @Operation(summary = "Dispatch a manifest",
            description = "Manifest must have at least one MANIFEST_CREATED shipment. Assigns the "
                    + "vehicle and driver, moves the manifest and every shipment on it to DISPATCHED. "
                    + "Driver OTP verification (dispatch-otp/verify) is optional and not required here.")
    public ApiResponse<DispatchManifestResponse> dispatch(@Valid @RequestBody DispatchManifestRequest request) {
        Manifest manifest = manifestService.dispatch(request.manifestId(), request.vehicleId(),
                request.driverUserId(), request.departureTime(),
                request.fuelCost(), request.driverAdvance(), request.tollAmount(), request.otherAmount());
        // The shipments themselves are already DISPATCHED by the time this call returns —
        // count them by that status rather than MANIFEST_CREATED, which none of them are
        // anymore. A DIRECT_COMPANY_DELIVERY manifest's shipments go one hop further, to
        // IN_SCAN, in this same call (see ManifestServiceImpl.dispatch/
        // ShipmentServiceImpl.markPickedUpForDirectDelivery — no delivery branch exists to
        // receive them later), so both statuses count as "dispatched" here.
        ShipmentCriteria dispatchedOnThisManifest = new ShipmentCriteria(
                Set.of(ShipmentStatus.DISPATCHED, ShipmentStatus.IN_SCAN), null, null, null, null, manifest.getId(),
                null, null, null, null, null, null, null, null);
        int dispatchedCount = (int) shipmentService.search(dispatchedOnThisManifest, Pageable.unpaged())
                .getTotalElements();
        return ApiResponse.success(new DispatchManifestResponse(
                manifest.getId(), manifest.getManifestNumber(), manifest.getStatus(),
                manifest.getVehicleId(), manifest.getDriverUserId(), manifest.getDispatchedAt(),
                manifest.getDepartureTime(), dispatchedCount,
                manifest.getFuelCost(), manifest.getDriverAdvance(), manifest.getTollAmount(),
                manifest.getOtherAmount()), "Manifest dispatched");
    }

    @PostMapping("/in-scan")
    @Operation(summary = "Receive shipments at the delivery branch",
            description = "Each tracking number must be DISPATCHED and its delivery branch must "
                    + "match the receiving branch. Bulk: per-item outcome. A non-empty "
                    + "missingTrackingNumbers auto-raises a Support ticket for the shortfall. "
                    + "Optional remarks/photoUrl (from in-scan-upload) are recorded on every "
                    + "shipment actually received.")
    public ApiResponse<BulkMovementResponse> inScan(@Valid @RequestBody InScanRequest request) {
        var result = shipmentService.inScan(request.receivingBranchId(), request.trackingNumbers(),
                request.manifestNumber(), request.missingTrackingNumbers(), request.remarks(), request.photoUrl());
        return ApiResponse.success(shipmentMapper.toResponse(result));
    }

    @PostMapping(value = "/in-scan-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload an In Scan photo",
            description = "Stores a photo (JPEG/PNG/WEBP/HEIC) taken while receiving shipments, "
                    + "not tied to any one shipment yet. Returns the URL to pass into in-scan() "
                    + "as photoUrl, which then gets attached to every shipment that call receives.")
    public ApiResponse<PodUploadResponse> uploadInScanPhoto(@RequestParam("file") MultipartFile file) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new BusinessRuleException("The uploaded file could not be read. Please retry.");
        }
        String url = shipmentService.uploadInScanPhoto(new ShipmentService.UploadInScanPhotoCommand(
                content, file.getOriginalFilename(), file.getContentType()));
        return ApiResponse.success(new PodUploadResponse(url), "File uploaded");
    }

    @PostMapping("/delivery-otp/request")
    @Operation(summary = "Request a delivery user OTP before Generate DRS (optional)",
            description = "Sends a 4-digit OTP to the delivery user's mobile over the company's "
                    + "configured SMS channel. Verifying it (delivery-otp/verify) is currently optional "
                    + "and does not gate /out-for-delivery.")
    public ApiResponse<DeliveryOtpRequestResponse> requestDeliveryOtp(
            @Valid @RequestBody DeliveryOtpRequestRequest request) {
        var issued = shipmentService.requestDeliveryDispatchOtp(request.deliveryUserId());
        return ApiResponse.success(new DeliveryOtpRequestResponse(issued.maskedMobile(), issued.expiresInMinutes()),
                "OTP sent to " + issued.maskedMobile());
    }

    @PostMapping("/delivery-otp/verify")
    @Operation(summary = "Verify the delivery user OTP",
            description = "A wrong code counts against a small attempt limit before the code is "
                    + "invalidated and a fresh one must be requested.")
    public ApiResponse<Void> verifyDeliveryOtp(@Valid @RequestBody DeliveryOtpVerifyRequest request) {
        shipmentService.verifyDeliveryDispatchOtp(request.deliveryUserId(), request.otp());
        return ApiResponse.success("OTP verified");
    }

    @PostMapping("/out-for-delivery")
    @Operation(summary = "Assign shipments to a delivery user",
            description = "Each shipment must be IN_SCAN. Bulk: per-item outcome. vehicleId/fuelCost/"
                    + "deliveryCharge are all optional trip fields, stamped on every DeliveryAssignment "
                    + "row this call touches. Delivery user OTP verification (delivery-otp/verify) is "
                    + "optional and not required here.")
    public ApiResponse<BulkMovementResponse> outForDelivery(@Valid @RequestBody OutForDeliveryRequest request) {
        if (request.vehicleId() != null) {
            Vehicle vehicle = vehicleService.getById(request.vehicleId());
            if (!vehicle.isActive()) {
                throw new BusinessRuleException("Vehicle %s is not active.".formatted(vehicle.getVehicleNumber()));
            }
        }
        var result = shipmentService.assignOutForDelivery(request.shipmentIds(), request.deliveryUserId(),
                request.vehicleId(), request.fuelCost(), request.deliveryCharge());
        return ApiResponse.success(shipmentMapper.toResponse(result));
    }

    @PostMapping("/deliver")
    @Operation(summary = "Close a delivery",
            description = "Shipment must be OUT_FOR_DELIVERY. Captures receiver name (required), "
                    + "remarks, and the optional OTP/signature/photo.")
    public ApiResponse<ShipmentResponse> deliver(@Valid @RequestBody DeliverRequest request) {
        Shipment delivered = shipmentService.deliver(request.shipmentId(),
                new ShipmentService.DeliverCommand(request.receiverName(), request.remarks(),
                        request.otp(), request.signatureUrl(), request.photoUrl()));
        return ApiResponse.success(
                shipmentMapper.toResponse(delivered, shipmentService.getItems(delivered.getId()),
                        shipmentService.getDeliveryAssignment(delivered.getId()),
                        shipmentService.getAssets(delivered.getId()),
                        shipmentService.getEwayBill(delivered.getId()).orElse(null)),
                "Shipment delivered");
    }

    public record UndeliveredRequest(@jakarta.validation.constraints.NotNull com.courier.modules.shipment.domain.UndeliveredReason reason,
                                     @jakarta.validation.constraints.Size(max = 255) String note) {
    }

    @PostMapping("/{shipmentId}/undelivered")
    @Operation(summary = "Record a failed delivery attempt",
            description = "Shipment must be OUT_FOR_DELIVERY. `reason`: RECEIVER_NOT_AVAILABLE, ADDRESS_INCORRECT, REFUSED, PREMISES_CLOSED, "
                    + "RESCHEDULE_REQUESTED, COD_NOT_READY or OTHER (`note` required). The shipment goes back to the delivery branch (IN_SCAN), "
                    + "the attempt is counted and the customer is told. Send it out again with out-for-delivery, or return it.")
    public ApiResponse<ShipmentResponse> undelivered(@PathVariable UUID shipmentId, @Valid @RequestBody UndeliveredRequest request) {
        Shipment s = shipmentService.markUndelivered(shipmentId, request.reason(), request.note());
        return ApiResponse.success(shipmentMapper.toResponse(s, shipmentService.getItems(s.getId()),
                shipmentService.getDeliveryAssignment(s.getId()), shipmentService.getAssets(s.getId()),
                shipmentService.getEwayBill(s.getId()).orElse(null)), "Delivery attempt recorded");
    }

    /** One open failed-delivery case for the staff queue. */
    public record UndeliveredCase(UUID shipmentId, String shipmentNumber, String trackingNumber, String receiverName,
                                  String receiverContact, String receiverAddress, String toCity, UUID currentLocationId,
                                  UUID deliveryBranchId, int attempts, String reason, String reasonLabel, String note,
                                  java.time.Instant failedAt, long daysWaiting, String choice, String choiceLabel,
                                  java.time.LocalDate choiceDate, java.time.Instant choiceAt, boolean returnFlagged) {
    }

    @GetMapping("/undelivered")
    @Operation(summary = "Open failed-delivery cases (staff queue)",
            description = "Shipments whose last delivery attempt failed and that nobody has sent out again, delivered or returned yet, oldest "
                    + "first. `choice` is what the customer asked for (REATTEMPT, CHANGE_ADDRESS, RETURN) or null. `returnFlagged`: 3 attempts, "
                    + "the customer asked for a return, or no answer for 7 days. A branch manager sees the ones at or for their branch.")
    public ApiResponse<java.util.List<UndeliveredCase>> undeliveredQueue() {
        java.time.Instant now = java.time.Instant.now();
        return ApiResponse.success(shipmentService.openUndelivered().stream().map(s -> new UndeliveredCase(s.getId(), s.getShipmentNumber(),
                s.getTrackingNumber(), s.getReceiverName(), s.getReceiverContact(), s.getReceiverAddress(), s.getToCity(),
                s.getCurrentLocationId(), s.getDeliveryBranchId(), s.getFailedAttempts(),
                s.getUndeliveredReason() == null ? null : s.getUndeliveredReason().name(),
                s.getUndeliveredReason() == null ? null : s.getUndeliveredReason().getLabel(), s.getUndeliveredNote(), s.getUndeliveredAt(),
                s.getUndeliveredAt() == null ? 0 : java.time.Duration.between(s.getUndeliveredAt(), now).toDays(),
                s.getUndeliveredChoice() == null ? null : s.getUndeliveredChoice().name(),
                s.getUndeliveredChoice() == null ? null : s.getUndeliveredChoice().getLabel(), s.getUndeliveredChoiceDate(),
                s.getUndeliveredChoiceAt(), s.isReturnFlagged(now))).toList());
    }

    public record ReturnRequest(@jakarta.validation.constraints.Size(max = 200) String reason) {
    }

    @PostMapping("/{shipmentId}/return")
    @Operation(summary = "Start the return to the sender (RTO)",
            description = "From the delivery branch (IN_SCAN) or out for delivery. The shipment goes back to its booking branch on a normal "
                    + "Load Sheet (it is offered there with the booking branch as the next stop), is in-scanned there and handed back with "
                    + "POST /{id}/hand-back. Delivered from its own booking branch, it waits there to be handed back. A portal customer is "
                    + "charged the forward freight once; the customer is told (return-to-origin message, when that template is active).")
    public ApiResponse<ShipmentResponse> returnToSender(@PathVariable UUID shipmentId,
                                                        @org.springframework.web.bind.annotation.RequestBody(required = false) ReturnRequest request) {
        Shipment s = shipmentService.returnToSender(shipmentId, request == null ? null : request.reason());
        return ApiResponse.success(full(s), "Return to sender started");
    }

    public record HandBackRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 120) String receivedBy,
                                  @jakarta.validation.constraints.Size(max = 200) String remarks,
                                  @jakarta.validation.constraints.Size(max = 1024) String photoUrl) {
    }

    @PostMapping("/{shipmentId}/hand-back")
    @Operation(summary = "Hand a returned parcel back to its sender",
            description = "At the booking branch, once the return is in-scanned there (or right away when it was delivered from that branch). "
                    + "Ends the shipment as RETURNED and records who received it.")
    public ApiResponse<ShipmentResponse> handBack(@PathVariable UUID shipmentId, @Valid @org.springframework.web.bind.annotation.RequestBody HandBackRequest r) {
        Shipment s = shipmentService.handBackToSender(shipmentId, r.receivedBy(), r.remarks(), r.photoUrl());
        return ApiResponse.success(full(s), "Returned to sender");
    }

    public record ReturnCase(UUID id, String shipmentNumber, String trackingNumber, String senderName, String senderContact,
                             String receiverName, String toCity, ShipmentStatus status, UUID currentLocationId, UUID bookingBranchId,
                             boolean backAtOrigin, java.time.Instant rtoStartedAt, long daysOnTheWay) {
    }

    @GetMapping("/returns")
    @Operation(summary = "Returns on their way back to the sender (staff queue)",
            description = "Return to sender started and not handed back yet, oldest first. `backAtOrigin`: in-scanned at the booking branch, "
                    + "ready to hand back. A branch manager sees the ones at or for their branch.")
    public ApiResponse<java.util.List<ReturnCase>> returnsQueue() {
        java.time.Instant now = java.time.Instant.now();
        return ApiResponse.success(shipmentService.openReturns().stream().map(s -> new ReturnCase(s.getId(), s.getShipmentNumber(),
                s.getTrackingNumber(), s.getSenderName(), s.getSenderContact(), s.getReceiverName(), s.getToCity(), s.getStatus(),
                s.getCurrentLocationId(), s.getBookingBranchId(),
                s.getStatus() == ShipmentStatus.IN_SCAN && java.util.Objects.equals(s.getCurrentLocationId(), s.getBookingBranchId()),
                s.getRtoStartedAt(), s.getRtoStartedAt() == null ? 0 : java.time.Duration.between(s.getRtoStartedAt(), now).toDays())).toList());
    }

    private ShipmentResponse full(Shipment s) {
        return shipmentMapper.toResponse(s, shipmentService.getItems(s.getId()), shipmentService.getDeliveryAssignment(s.getId()),
                shipmentService.getAssets(s.getId()), shipmentService.getEwayBill(s.getId()).orElse(null));
    }

    @PostMapping("/{shipmentId}/override-status")
    @Operation(summary = "Manually override a shipment's status (COMPANY_ADMIN/BRANCH_MANAGER)",
            description = "Opt-in per company (Company Settings > Shipment > Manual Status Override). "
                    + "Pushes the shipment to targetStatus from any current status, bypassing the normal "
                    + "THC/DRS/Deliver flow. When the jump is a legal edge a real service method already "
                    + "covers (->OUT_FOR_DELIVERY, ->DELIVERED, ->CANCELLED) and its own precondition is "
                    + "met, that method runs and its money/wallet/POD side effects apply as usual — supply "
                    + "its required fields (deliveryUserId, receiverName) on this request. Otherwise this "
                    + "is a raw status write with no side effect at all; response.warning explains when "
                    + "that happened.")
    public ApiResponse<OverrideStatusResponse> overrideStatus(@PathVariable UUID shipmentId,
                                                                @Valid @RequestBody OverrideStatusRequest request) {
        var result = shipmentService.overrideStatus(shipmentId, new ShipmentService.OverrideStatusCommand(
                request.targetStatus(), request.reason(), request.deliveryUserId(), request.vehicleId(),
                request.fuelCost(), request.deliveryCharge(), request.receiverName(), request.otp(),
                request.signatureUrl(), request.photoUrl()));
        Shipment shipment = result.shipment();
        var response = new OverrideStatusResponse(
                shipmentMapper.toResponse(shipment, shipmentService.getItems(shipment.getId()),
                        shipmentService.getDeliveryAssignment(shipment.getId()),
                        shipmentService.getAssets(shipment.getId()),
                        shipmentService.getEwayBill(shipment.getId()).orElse(null)),
                result.viaRealMethod(), result.warning());
        return ApiResponse.success(response,
                result.warning() == null ? "Status overridden" : result.warning());
    }

    @GetMapping("/drs")
    @Operation(summary = "List DRS runs",
            description = "One row per delivery user + delivery branch + calendar day, grouped from "
                    + "DeliveryAssignment (there is no separate DRS/batch table). Defaults to the "
                    + "trailing 30 days when from/to are omitted. deliveryBranchId restricts to one "
                    + "branch server-side, e.g. a BRANCH_MANAGER's own branch.")
    public ApiResponse<java.util.List<com.courier.modules.shipment.api.dto.DrsSummaryResponse>> listDrs(
            @RequestParam(required = false) java.time.LocalDate from,
            @RequestParam(required = false) java.time.LocalDate to,
            @RequestParam(required = false) UUID deliveryBranchId) {
        return ApiResponse.success(shipmentService.listDrs(from, to, deliveryBranchId).stream()
                .map(shipmentMapper::toResponse).toList());
    }

    @GetMapping("/drs/detail")
    @Operation(summary = "One DRS run's shipments",
            description = "Every shipment assigned to this delivery user, at this delivery branch, "
                    + "on this day.")
    public ApiResponse<com.courier.modules.shipment.api.dto.DrsDetailResponse> drsDetail(
            @RequestParam UUID deliveryUserId, @RequestParam UUID deliveryBranchId,
            @RequestParam java.time.LocalDate runDate) {
        return ApiResponse.success(
                shipmentMapper.toResponse(shipmentService.getDrsDetail(deliveryUserId, deliveryBranchId, runDate)));
    }

    @PostMapping(value = "/{shipmentId}/pod-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a POD file",
            description = "Stores a delivery photo or signature capture (JPEG/PNG/WEBP/HEIC image, "
                    + "MP4/MOV video, or PDF) in the configured object store. Returns the URL to pass "
                    + "into deliver() as signatureUrl/photoUrl.")
    public ApiResponse<PodUploadResponse> uploadPodFile(@PathVariable UUID shipmentId,
                                                         @RequestParam("file") MultipartFile file,
                                                         @RequestParam("kind") String kind) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new BusinessRuleException("The uploaded file could not be read. Please retry.");
        }
        String url = shipmentService.uploadPodFile(shipmentId, new ShipmentService.UploadPodFileCommand(
                content, file.getOriginalFilename(), file.getContentType(), kind));
        return ApiResponse.success(new PodUploadResponse(url), "File uploaded");
    }
}
