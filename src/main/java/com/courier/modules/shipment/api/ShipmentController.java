package com.courier.modules.shipment.api;

import com.courier.modules.shipment.api.dto.AddShipmentDocumentRequest;
import com.courier.modules.shipment.api.dto.BulkTrackRequest;
import com.courier.modules.shipment.api.dto.BulkTrackResponse;
import com.courier.modules.shipment.api.dto.BulkTrackRowResponse;
import com.courier.modules.shipment.api.dto.CreateShipmentRequest;
import com.courier.modules.shipment.api.dto.ShipmentChargeResponse;
import com.courier.modules.shipment.api.dto.ShipmentDocumentResponse;
import com.courier.modules.shipment.api.dto.ShipmentImageUploadResponse;
import com.courier.modules.shipment.api.dto.ShipmentItemResponse;
import com.courier.modules.shipment.api.dto.ShipmentResponse;
import com.courier.modules.shipment.api.dto.ShipmentSearchRequest;
import com.courier.modules.shipment.api.dto.ShipmentStatusHistoryResponse;
import com.courier.modules.shipment.api.dto.ShipmentSummaryResponse;
import com.courier.modules.shipment.api.dto.ShipmentSummaryStatsResponse;
import com.courier.modules.shipment.api.dto.UpdateShipmentRequest;
import com.courier.modules.shipment.application.ShipmentService;
import com.courier.modules.shipment.domain.Shipment;
import com.courier.shared.api.ApiResponse;
import com.courier.shared.api.PageResponse;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Shipment Booking: the core transaction of the platform. Enforced on
 * {@link ShipmentService}: {@code COMPANY_ADMIN}, {@code BRANCH_MANAGER} and
 * {@code OPERATOR} book, update, cancel and upload documents; every other authenticated
 * user of the company reads. The company is always the caller's, from the JWT.
 *
 * <p>The brief's {@code GET /shipments/{trackingNumber}} is exposed at
 * {@code GET /shipments/track/{trackingNumber}} instead — a bare second
 * {@code /shipments/{x}} route is ambiguous with {@code GET /shipments/{id}} at the Spring
 * MVC routing layer, the same "honest deviation from a literal path" every other module in
 * this project has made when the spec's literal shape did not fit (see, e.g., Branch
 * Wallet's singular path or Master Data's {@code new} before {@code :id}).
 */
@RestController
@RequestMapping("/api/v1/shipments")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Shipment Booking", description = "Book, price, track and cancel shipments")
public class ShipmentController {

    private static final Map<String, String> SORTABLE = Map.ofEntries(
            Map.entry("shipmentNumber", "shipmentNumber"),
            Map.entry("trackingNumber", "trackingNumber"),
            Map.entry("bookingDate", "bookingDate"),
            Map.entry("status", "status"),
            Map.entry("chargeableWeight", "chargeableWeight"),
            Map.entry("createdDate", "createdAt"),
            Map.entry("createdAt", "createdAt"));

    private static final int MAX_PAGE_SIZE = 100;

    private final ShipmentService shipmentService;
    private final ShipmentMapper mapper;

    @PostMapping
    @Operation(summary = "Book a shipment",
            description = "`COMPANY_ADMIN`, `BRANCH_MANAGER` or `OPERATOR`. Validates the "
                    + "sender/receiver/addresses, prices the booking through the Pricing "
                    + "Engine (route, serviceability, rate and weight-slab validation all "
                    + "happen inside that one call), generates the AWB and shipment number, "
                    + "and — for a payment mode that collects at booking — checks the "
                    + "branch wallet can afford it before committing, then debits it once "
                    + "the booking has committed.")
    public ResponseEntity<ApiResponse<ShipmentResponse>> create(
            @Valid @RequestBody CreateShipmentRequest request) {
        Shipment created = shipmentService.create(mapper.toCommand(request),
                new com.courier.modules.shipment.application.command.CounterPayment(
                        request.paymentMethod(), request.paymentReference()));
        return ResponseEntity
                .created(UriComponentsBuilder.fromPath("/api/v1/shipments/{id}")
                        .buildAndExpand(created.getId()).toUri())
                .body(ApiResponse.success(
                        mapper.toResponse(created, shipmentService.getItems(created.getId()),
                                shipmentService.getEwayBill(created.getId()).orElse(null)),
                        "Shipment booked"));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a shipment",
            description = "Full replacement, only while the shipment is still `BOOKED`. "
                    + "`version` required; a stale value returns 409. Re-prices the "
                    + "booking; does not touch a wallet already debited at booking time.")
    public ApiResponse<ShipmentResponse> update(@PathVariable UUID id,
                                                @Valid @RequestBody UpdateShipmentRequest request) {
        Shipment updated = shipmentService.update(id, mapper.toCommand(request));
        return ApiResponse.success(
                mapper.toResponse(updated, shipmentService.getItems(id),
                        shipmentService.getEwayBill(id).orElse(null)), "Shipment updated");
    }

    @GetMapping("/{id}/rate-per-kg/preview")
    @Operation(summary = "Preview a booked shipment's charges at a Rate/KG",
            description = "COMPANY_ADMIN only. Recalculates exactly like the update, saves nothing.")
    public ApiResponse<ShipmentChargeResponse> previewRatePerKg(@PathVariable UUID id,
            @RequestParam java.math.BigDecimal ratePerKg) {
        return ApiResponse.success(mapper.toResponse(shipmentService.previewRatePerKg(id, ratePerKg)));
    }

    @PutMapping("/{id}/rate-per-kg")
    @Operation(summary = "Set a booked shipment's Rate/KG",
            description = "COMPANY_ADMIN only, while `BOOKED`. Recomputes freight, GST, commissions "
                    + "and net amount from the new rate; nothing else changes. `version` required.")
    public ApiResponse<ShipmentChargeResponse> updateRatePerKg(@PathVariable UUID id,
            @Valid @RequestBody com.courier.modules.shipment.api.dto.UpdateRatePerKgRequest request) {
        shipmentService.updateRatePerKg(id, request.version(), request.ratePerKg());
        return ApiResponse.success(mapper.toResponse(shipmentService.getCharges(id)), "Rate/KG updated");
    }

    @GetMapping("/{id}/payment-mode")
    @Operation(summary = "Payment-mode switch options for a shipment",
            description = "COMPANY_ADMIN only. The PAID/TO_PAY targets a BOOKED shipment can move to, "
                    + "the resulting amounts, the booking wallet's balance, and whether it was switched before.")
    public ApiResponse<com.courier.modules.shipment.api.dto.PaymentModeChangeResponse> paymentModeOptions(
            @PathVariable UUID id) {
        return ApiResponse.success(shipmentService.paymentModeChangeOptions(id));
    }

    @PutMapping("/{id}/payment-mode")
    @Operation(summary = "Switch a BOOKED shipment between PAID and TO_PAY",
            description = "COMPANY_ADMIN only, while `BOOKED`. To PAID debits the booking branch's wallet "
                    + "(refused if short); to TO_PAY credits it back. Charges are recalculated. `version` required.")
    public ApiResponse<com.courier.modules.shipment.api.dto.PaymentModeChangeResponse> changePaymentMode(
            @PathVariable UUID id,
            @Valid @RequestBody com.courier.modules.shipment.api.dto.ChangePaymentModeRequest request) {
        shipmentService.changePaymentMode(id, request.version(), request.paymentModeId());
        return ApiResponse.success(shipmentService.paymentModeChangeOptions(id), "Payment mode updated");
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch a shipment", description = "Includes its item grid.")
    public ApiResponse<ShipmentResponse> get(@PathVariable UUID id) {
        Shipment shipment = shipmentService.getById(id);
        return ApiResponse.success(mapper.toResponse(shipment, shipmentService.getItems(id),
                shipmentService.getDeliveryAssignment(id), shipmentService.getAssets(id),
                shipmentService.getEwayBill(id).orElse(null)));
    }

    @GetMapping("/track/{trackingNumber}")
    @Operation(summary = "Fetch a shipment by tracking number (AWB)",
            description = "See the class-level note on why this is not a second "
                    + "`/shipments/{x}` route.")
    public ApiResponse<ShipmentResponse> getByTrackingNumber(@PathVariable String trackingNumber) {
        Shipment shipment = shipmentService.getByTrackingNumber(trackingNumber);
        return ApiResponse.success(mapper.toResponse(shipment, shipmentService.getItems(shipment.getId()),
                shipmentService.getDeliveryAssignment(shipment.getId()), shipmentService.getAssets(shipment.getId()),
                shipmentService.getEwayBill(shipment.getId()).orElse(null)));
    }

    @PostMapping("/track/bulk")
    @Operation(summary = "Bulk Shipment Tracking",
            description = "Look up many shipments at once by pasting their tracking or "
                    + "shipment numbers (up to 200). Each number is matched against either "
                    + "column and returned as its own row, in the order submitted "
                    + "(duplicates collapsed) — a number with no match comes back with "
                    + "`found: false` and no `shipment` rather than failing the whole call.")
    public ApiResponse<BulkTrackResponse> bulkTrack(@Valid @RequestBody BulkTrackRequest request) {
        List<String> numbers = new LinkedHashSet<>(request.numbers().stream().map(String::trim).toList())
                .stream().toList();
        List<Shipment> matches = shipmentService.bulkTrack(numbers);
        List<UUID> ids = matches.stream().map(Shipment::getId).toList();
        Map<UUID, BigDecimal> netAmounts = shipmentService.netAmountsFor(ids);
        Map<UUID, com.courier.modules.shipment.domain.ShipmentCharge> charges = shipmentService.chargesFor(ids);
        Map<UUID, java.time.Instant> deliveredAt = shipmentService.deliveredAtFor(ids);
        Map<UUID, String> invoiceNumbers = shipmentService.invoiceNumbersFor(ids);
        Map<UUID, java.time.Instant> receivedAt = shipmentService.receivedAtFor(ids);
        Map<UUID, String> ewayBillNumbers = shipmentService.ewayBillNumbersFor(ids);

        Map<String, ShipmentSummaryResponse> byTrackingNumber = new LinkedHashMap<>();
        Map<String, ShipmentSummaryResponse> byShipmentNumber = new LinkedHashMap<>();
        for (Shipment s : matches) {
            ShipmentSummaryResponse row = mapper.toSummary(s, netAmounts.get(s.getId()),
                    charges.get(s.getId()), deliveredAt.get(s.getId()), invoiceNumbers.get(s.getId()),
                    receivedAt.get(s.getId()), ewayBillNumbers.get(s.getId()));
            byTrackingNumber.put(s.getTrackingNumber(), row);
            byShipmentNumber.put(s.getShipmentNumber(), row);
        }

        List<BulkTrackRowResponse> results = numbers.stream()
                .map(n -> {
                    ShipmentSummaryResponse row = byTrackingNumber.getOrDefault(n, byShipmentNumber.get(n));
                    return new BulkTrackRowResponse(n, row != null, row);
                })
                .toList();
        long foundCount = results.stream().filter(BulkTrackRowResponse::found).count();
        return ApiResponse.success(new BulkTrackResponse(results, foundCount, results.size() - foundCount));
    }

    @GetMapping
    @Operation(summary = "List shipments",
            description = """
                    Paged, sorted, filtered, searchable. Sort: `shipmentNumber`,
                    `trackingNumber`, `bookingDate`, `status`, `chargeableWeight`,
                    `createdDate`. `size` capped at 100.
                    """)
    public ApiResponse<PageResponse<ShipmentSummaryResponse>> list(
            @Valid @ParameterObject ShipmentSearchRequest search,
            @ParameterObject @PageableDefault(size = 20, sort = "createdDate", direction = Sort.Direction.DESC)
            Pageable pageable) {
        Page<Shipment> page = shipmentService.search(mapper.toCriteria(search), sanitise(pageable));
        List<UUID> ids = page.getContent().stream().map(Shipment::getId).toList();
        Map<UUID, BigDecimal> netAmounts = shipmentService.netAmountsFor(ids);
        Map<UUID, com.courier.modules.shipment.domain.ShipmentCharge> charges = shipmentService.chargesFor(ids);
        Map<UUID, java.time.Instant> deliveredAt = shipmentService.deliveredAtFor(ids);
        Map<UUID, String> invoiceNumbers = shipmentService.invoiceNumbersFor(ids);
        Map<UUID, java.time.Instant> receivedAt = shipmentService.receivedAtFor(ids);
        Map<UUID, String> ewayBillNumbers = shipmentService.ewayBillNumbersFor(ids);
        return ApiResponse.success(PageResponse.from(page,
                s -> mapper.toSummary(s, netAmounts.get(s.getId()), charges.get(s.getId()), deliveredAt.get(s.getId()),
                        invoiceNumbers.get(s.getId()), receivedAt.get(s.getId()), ewayBillNumbers.get(s.getId()))));
    }

    @GetMapping("/summary")
    @Operation(summary = "Summary stats for a shipment search",
            description = "Same filters as the list endpoint (`ShipmentSearchRequest`), "
                    + "unpaged — total count, total chargeable weight, total net amount and "
                    + "a per-status breakdown over every matching shipment, not just one page. "
                    + "Powers the Booking/Delivery Report summary row.")
    public ApiResponse<ShipmentSummaryStatsResponse> summary(
            @Valid @ParameterObject ShipmentSearchRequest search) {
        return ApiResponse.success(mapper.toSummaryStats(
                shipmentService.summaryStats(mapper.toCriteria(search))));
    }

    @GetMapping("/commission-summary")
    @Operation(summary = "Commission totals grouped by booking branch",
            description = "Same filters as the list endpoint (`ShipmentSearchRequest`), "
                    + "unpaged — one row per booking branch with at least one matching, "
                    + "charged shipment, sorted by total commission descending. Powers the "
                    + "Commission Report's branch-wise summary table.")
    public ApiResponse<List<com.courier.modules.shipment.api.dto.BranchCommissionSummaryResponse>> commissionSummary(
            @Valid @ParameterObject ShipmentSearchRequest search) {
        return ApiResponse.success(shipmentService.commissionSummary(mapper.toCriteria(search)).stream()
                .map(mapper::toCommissionSummary).toList());
    }

    @GetMapping("/branch-performance")
    @Operation(summary = "Shipment volume/outcome totals grouped by booking branch",
            description = "Same filters as the list endpoint (`ShipmentSearchRequest`), "
                    + "unpaged — one row per booking branch with at least one matching "
                    + "shipment, sorted by shipment count descending. Powers the Branch "
                    + "Performance Report.")
    public ApiResponse<List<com.courier.modules.shipment.api.dto.BranchPerformanceSummaryResponse>> branchPerformance(
            @Valid @ParameterObject ShipmentSearchRequest search) {
        return ApiResponse.success(shipmentService.branchPerformance(mapper.toCriteria(search)).stream()
                .map(mapper::toBranchPerformance).toList());
    }

    @GetMapping("/vendor-audit")
    @Operation(summary = "Vendor Audit Report: per-branch paid/to-pay volumes, booking and "
                    + "delivery commission, ODA/other charges and cancellations",
            description = "Same filters as the list endpoint (`ShipmentSearchRequest`), "
                    + "unpaged — one row per company branch appearing as either a booking or "
                    + "a delivery branch in the search. See `VendorAuditRow` for the "
                    + "booking-vs-delivery grouping split and its one known limitation on "
                    + "the delivery-commission figures.")
    public ApiResponse<List<com.courier.modules.shipment.api.dto.VendorAuditRowResponse>> vendorAudit(
            @Valid @ParameterObject ShipmentSearchRequest search) {
        return ApiResponse.success(shipmentService.vendorAudit(mapper.toCriteria(search)).stream()
                .map(mapper::toVendorAuditRow).toList());
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a shipment",
            description = "A reason is mandatory. Refused once the shipment has left the "
                    + "branch (DISPATCHED onward — i.e. once its Trip Hire Challan has been "
                    + "created). Does not reverse a PREPAID debit already posted at booking.")
    public ApiResponse<ShipmentResponse> cancel(@PathVariable UUID id, @RequestParam String remarks) {
        Shipment cancelled = shipmentService.cancel(id, remarks);
        return ApiResponse.success(
                mapper.toResponse(cancelled, shipmentService.getItems(id),
                        shipmentService.getEwayBill(id).orElse(null)), "Shipment cancelled");
    }

    @PostMapping("/{id}/documents")
    @Operation(summary = "Attach a document to a shipment",
            description = "`documentType` one of `INVOICE`, `EWAY_BILL`, `PACKING_LIST`, "
                    + "`LR_COPY`, `POD`. There is no file-storage backend yet — "
                    + "`documentUrl` is supplied by the caller.")
    public ResponseEntity<ApiResponse<ShipmentDocumentResponse>> addDocument(
            @PathVariable UUID id, @Valid @RequestBody AddShipmentDocumentRequest request) {
        var created = shipmentService.addDocument(id, mapper.toCommand(request));
        return ResponseEntity
                .created(UriComponentsBuilder.fromPath("/api/v1/shipments/{id}/documents/{docId}")
                        .buildAndExpand(id, created.getId()).toUri())
                .body(ApiResponse.success(mapper.toResponse(created), "Document attached"));
    }

    @PostMapping(value = "/{id}/image-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a shipment photo",
            description = "JPEG/PNG/WEBP/HEIC only. Stores the image in the configured object "
                    + "store and records it against the shipment immediately — unlike POD "
                    + "upload, there is no separate confirm step.")
    public ApiResponse<ShipmentImageUploadResponse> uploadImage(@PathVariable UUID id,
                                                                 @RequestParam("file") MultipartFile file) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new BusinessRuleException("The uploaded file could not be read. Please retry.");
        }
        String url = shipmentService.uploadShipmentImage(id, new ShipmentService.UploadShipmentImageCommand(
                content, file.getOriginalFilename(), file.getContentType()));
        return ApiResponse.success(new ShipmentImageUploadResponse(url), "Image uploaded");
    }

    @GetMapping("/{id}/charges")
    @Operation(summary = "Fetch a shipment's charge breakup",
            description = "The Pricing Engine's own charge lines, persisted at booking time.")
    public ApiResponse<ShipmentChargeResponse> getCharges(@PathVariable UUID id) {
        return ApiResponse.success(mapper.toResponse(shipmentService.getCharges(id)));
    }

    @GetMapping("/{id}/history")
    @Operation(summary = "Fetch a shipment's status timeline", description = "Oldest first, append-only.")
    public ApiResponse<List<ShipmentStatusHistoryResponse>> getHistory(@PathVariable UUID id) {
        List<ShipmentStatusHistoryResponse> history = shipmentService.getHistory(id).stream()
                .map(mapper::toResponse).toList();
        return ApiResponse.success(history);
    }

    @GetMapping("/{id}/documents")
    @Operation(summary = "List a shipment's documents", description = "Newest first.")
    public ApiResponse<List<ShipmentDocumentResponse>> getDocuments(@PathVariable UUID id) {
        List<ShipmentDocumentResponse> documents = shipmentService.getDocuments(id).stream()
                .map(mapper::toResponse).toList();
        return ApiResponse.success(documents);
    }

    @GetMapping("/{id}/timeline")
    @Operation(summary = "Fetch a shipment's movement timeline",
            description = "Booked -> Manifest Created -> Dispatched -> Received -> "
                    + "Out For Delivery -> Delivered, each step flagged completed/pending with "
                    + "when and who — built from the same append-only log as `/history`, but one "
                    + "row per named step rather than the raw sequence of transitions.")
    public ApiResponse<List<com.courier.modules.shipment.api.dto.TimelineStepResponse>> getTimeline(
            @PathVariable UUID id) {
        List<com.courier.modules.shipment.api.dto.TimelineStepResponse> steps =
                shipmentService.timeline(id).stream().map(mapper::toResponse).toList();
        return ApiResponse.success(steps);
    }

    @GetMapping("/{id}/delivery-run")
    @Operation(summary = "Fetch the DRS this shipment is on",
            description = "Null data when the shipment has not been put Out For Delivery yet.")
    public ApiResponse<com.courier.modules.shipment.api.dto.ShipmentDrsResponse> getDeliveryRun(
            @PathVariable UUID id) {
        var d = shipmentService.getDeliveryAssignment(id);
        if (d == null) {
            return ApiResponse.<com.courier.modules.shipment.api.dto.ShipmentDrsResponse>success(null, null);
        }
        return ApiResponse.success(new com.courier.modules.shipment.api.dto.ShipmentDrsResponse(
                d.getDrsNumber(), d.getDeliveryBranchId(), d.getDeliveryUserId(), d.getVehicleId(),
                d.getFuelCost(), d.getDeliveryCharge(), d.getStatus().name(),
                d.getAssignedAt(), d.getDeliveredAt(), d.getReceiverName(), d.getDeliveryRemarks()));
    }

    @GetMapping("/{id}/items")
    @Operation(summary = "Fetch a shipment's item grid")
    public ApiResponse<List<ShipmentItemResponse>> getItems(@PathVariable UUID id) {
        List<ShipmentItemResponse> items = shipmentService.getItems(id).stream()
                .map(mapper::toResponse).toList();
        return ApiResponse.success(items);
    }

    // -------------------------------------------------------------------- helpers

    private Pageable sanitise(Pageable pageable) {
        int size = Math.min(pageable.getPageSize(), MAX_PAGE_SIZE);
        List<Sort.Order> orders = pageable.getSort().stream()
                .map(order -> {
                    String property = SORTABLE.get(order.getProperty());
                    if (property == null) {
                        throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED,
                                "Cannot sort by '%s'. Allowed: %s"
                                        .formatted(order.getProperty(),
                                                String.join(", ", new TreeSet<>(SORTABLE.keySet()))));
                    }
                    return new Sort.Order(order.getDirection(), property);
                })
                .toList();
        Sort sort = orders.isEmpty()
                ? Sort.by(Sort.Order.desc("createdAt"))
                : Sort.by(orders);
        return PageRequest.of(pageable.getPageNumber(), size, sort);
    }
}
