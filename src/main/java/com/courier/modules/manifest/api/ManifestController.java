package com.courier.modules.manifest.api;

import com.courier.modules.manifest.api.dto.CreateManifestRequest;
import com.courier.modules.manifest.api.dto.ManifestResponse;
import com.courier.modules.manifest.api.dto.ManifestSearchRequest;
import com.courier.modules.manifest.api.dto.ManifestSummaryStatsResponse;
import com.courier.modules.manifest.application.ManifestService;
import com.courier.modules.manifest.domain.Manifest;
import com.courier.modules.manifest.domain.ManifestShipmentAggregate;
import com.courier.modules.shipment.api.ShipmentMapper;
import com.courier.modules.shipment.api.dto.ShipmentSummaryResponse;
import com.courier.modules.shipment.application.ShipmentService;
import com.courier.modules.shipment.domain.Shipment;
import com.courier.modules.shipment.domain.ShipmentCriteria;
import com.courier.shared.api.ApiResponse;
import com.courier.shared.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The minimal Manifest module Shipment Movement needs underneath it — see {@code Manifest}'s
 * own class-level note. Not part of the brief's own REST API list (which starts at
 * {@code /shipment-movement/out-scan}, assuming a manifest already exists); this is the
 * prerequisite that makes one.
 */
@RestController
@RequestMapping("/api/v1/manifests")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Manifest", description = "Group shipments travelling one branch-to-branch run")
public class ManifestController {

    private final ManifestService manifestService;
    private final ManifestMapper mapper;
    private final ShipmentService shipmentService;
    private final ShipmentMapper shipmentMapper;

    @PostMapping
    @Operation(summary = "Create a manifest",
            description = "Every shipment id must be BOOKED and travel exactly this "
                    + "booking/delivery branch pair — each is transitioned to MANIFEST_CREATED.")
    public ResponseEntity<ApiResponse<ManifestResponse>> create(
            @Valid @RequestBody CreateManifestRequest request) {
        Manifest created = manifestService.create(mapper.toCommand(request));
        ManifestShipmentAggregate aggregate = aggregateFor(created.getId());
        return ResponseEntity
                .created(UriComponentsBuilder.fromPath("/api/v1/manifests/{id}")
                        .buildAndExpand(created.getId()).toUri())
                .body(ApiResponse.success(mapper.toResponse(created, aggregate), "Manifest created"));
    }

    @GetMapping("/{id}")
    public ApiResponse<ManifestResponse> get(@PathVariable UUID id) {
        Manifest manifest = manifestService.getById(id);
        return ApiResponse.success(mapper.toResponse(manifest, aggregateFor(manifest.getId())));
    }

    @GetMapping
    @Operation(summary = "List manifests", description = "Paged, filtered by status/branch/search.")
    public ApiResponse<PageResponse<ManifestResponse>> list(
            @ParameterObject ManifestSearchRequest search,
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        Page<Manifest> page = manifestService.search(mapper.toCriteria(search), pageable);
        List<UUID> ids = page.getContent().stream().map(Manifest::getId).toList();
        Map<UUID, ManifestShipmentAggregate> aggregates = shipmentService.findByManifestIds(ids).stream()
                .collect(Collectors.groupingBy(Shipment::getManifestId,
                        Collectors.collectingAndThen(Collectors.toList(), ManifestShipmentAggregate::of)));
        return ApiResponse.success(PageResponse.from(page,
                m -> mapper.toResponse(m, aggregates.getOrDefault(m.getId(), ManifestShipmentAggregate.EMPTY))));
    }

    @GetMapping("/summary")
    @Operation(summary = "Summary stats for a manifest search",
            description = "Same filters as the list endpoint (`ManifestSearchRequest`), unpaged — "
                    + "manifest count, total shipment count, total weight and total package count "
                    + "over every matching manifest, not just one page. Powers the THC Report summary row.")
    public ApiResponse<ManifestSummaryStatsResponse> summary(@ParameterObject ManifestSearchRequest search) {
        return ApiResponse.success(mapper.toSummaryStats(manifestService.summaryStats(mapper.toCriteria(search))));
    }

    @GetMapping("/eligible-destinations")
    @Operation(summary = "Distinct destination cities eligible for a new Load Sheet from this branch",
            description = "Every distinct destination city among this branch's own BOOKED/READY_FOR_MANIFEST "
                    + "shipments with no delivery branch resolved yet — Load Sheet's first picker, ahead of "
                    + "the shipment checklist.")
    public ApiResponse<List<String>> eligibleDestinations(@RequestParam UUID bookingBranchId) {
        return ApiResponse.success(shipmentService.findEligibleDestinationCities(bookingBranchId));
    }

    private ManifestShipmentAggregate aggregateFor(UUID manifestId) {
        return ManifestShipmentAggregate.of(shipmentService.findByManifestIds(List.of(manifestId)));
    }

    @GetMapping("/{id}/shipments")
    @Operation(summary = "List the shipments scanned onto this manifest",
            description = "Loading Sheet's own \"Search Manifest -> Display Shipments\" and Dispatch's "
                    + "manifest picker both read this.")
    public ApiResponse<List<ShipmentSummaryResponse>> shipments(@PathVariable UUID id) {
        manifestService.getById(id); // 404s a foreign/unknown manifest before listing anything
        ShipmentCriteria criteria = new ShipmentCriteria(null, null, null, null, null, id, null, null, null, null, null, null, null, null);
        Page<Shipment> page = shipmentService.search(criteria, Pageable.unpaged());
        List<UUID> ids = page.getContent().stream().map(Shipment::getId).toList();
        Map<UUID, BigDecimal> netAmounts = shipmentService.netAmountsFor(ids);
        Map<UUID, com.courier.modules.shipment.domain.ShipmentCharge> charges = shipmentService.chargesFor(ids);
        Map<UUID, java.time.Instant> deliveredAt = shipmentService.deliveredAtFor(ids);
        Map<UUID, String> invoiceNumbers = shipmentService.invoiceNumbersFor(ids);
        Map<UUID, java.time.Instant> receivedAt = shipmentService.receivedAtFor(ids);
        Map<UUID, String> ewayBillNumbers = shipmentService.ewayBillNumbersFor(ids);
        List<ShipmentSummaryResponse> summaries = page.getContent().stream()
                .map(s -> shipmentMapper.toSummary(s, netAmounts.get(s.getId()), charges.get(s.getId()),
                        deliveredAt.get(s.getId()), invoiceNumbers.get(s.getId()), receivedAt.get(s.getId()),
                        ewayBillNumbers.get(s.getId())))
                .toList();
        return ApiResponse.success(summaries);
    }

    @DeleteMapping("/{id}/shipments/{shipmentId}")
    @Operation(summary = "Remove a shipment from a manifest",
            description = "Only while the manifest is still CREATED — reverts the shipment to BOOKED "
                    + "so a future manifest can pick it up.")
    public ApiResponse<Void> removeShipment(@PathVariable UUID id, @PathVariable UUID shipmentId) {
        manifestService.removeShipment(id, shipmentId);
        return ApiResponse.success("Shipment removed from the manifest");
    }

    // ---- draft (CREATED) editing: Scan → Manifest tab → Edit, before Dispatch

    /** Co-loader, own vehicle / driver and bags of a draft manifest. */
    public record UpdateManifestDraftRequest(UUID coLoaderId, UUID vehicleId, UUID driverUserId,
                                             @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(9999) Integer bagsCount) {
    }

    public record AddManifestShipmentsRequest(@jakarta.validation.constraints.NotEmpty java.util.List<UUID> shipmentIds) {
    }

    public record ForwarderNumbersRequest(@jakarta.validation.constraints.NotNull Map<UUID, @jakarta.validation.constraints.Size(max = 60) String> forwarderNumbers) {
    }

    @org.springframework.web.bind.annotation.PatchMapping("/{id}")
    @Operation(summary = "Change a draft manifest's trip details", description = "Only while CREATED: co-loader, vehicle, driver, bags.")
    public ApiResponse<ManifestResponse> updateDraft(@PathVariable UUID id, @Valid @RequestBody UpdateManifestDraftRequest request) {
        Manifest m = manifestService.updateDraft(id, request.coLoaderId(), request.vehicleId(), request.driverUserId(), request.bagsCount());
        return ApiResponse.success(mapper.toResponse(m, aggregateFor(m.getId())));
    }

    @PostMapping("/{id}/shipments")
    @Operation(summary = "Add shipments to a draft manifest", description = "Only while CREATED; same checks as create.")
    public ApiResponse<ManifestResponse> addShipments(@PathVariable UUID id, @Valid @RequestBody AddManifestShipmentsRequest request) {
        Manifest m = manifestService.addShipments(id, request.shipmentIds());
        return ApiResponse.success(mapper.toResponse(m, aggregateFor(m.getId())));
    }

    @org.springframework.web.bind.annotation.PutMapping("/{id}/forwarder-numbers")
    @Operation(summary = "Set the co-loader's number per AWB on a draft manifest", description = "Shipment id → number; blank clears.")
    public ApiResponse<Void> setForwarderNumbers(@PathVariable UUID id, @Valid @RequestBody ForwarderNumbersRequest request) {
        manifestService.setForwarderNumbers(id, request.forwarderNumbers());
        return ApiResponse.success("Forwarder numbers saved");
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a draft manifest", description = "Only while CREATED; its shipments go back to BOOKED (Pending Outscan).")
    public ApiResponse<Void> deleteDraft(@PathVariable UUID id) {
        manifestService.deleteDraft(id);
        return ApiResponse.success("Manifest deleted");
    }
}
