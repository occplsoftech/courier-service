package com.courier.modules.pickup.api;

import com.courier.modules.company.domain.Branch;
import com.courier.modules.pickup.api.PickupDtos.AssignRequest;
import com.courier.modules.pickup.api.PickupDtos.CompleteRequest;
import com.courier.modules.pickup.api.PickupDtos.LinkShipmentRequest;
import com.courier.modules.pickup.api.PickupDtos.PickupResponse;
import com.courier.modules.pickup.api.PickupDtos.ReasonRequest;
import com.courier.modules.pickup.api.PickupDtos.SaveRequest;
import com.courier.modules.pickup.api.PickupDtos.StatsResponse;
import com.courier.modules.pickup.application.PickupService;
import com.courier.modules.pickup.application.PickupService.SaveCommand;
import com.courier.modules.pickup.domain.PickupRequest;
import com.courier.modules.pickup.domain.PickupStatus;
import com.courier.shared.api.ApiResponse;
import com.courier.shared.api.PageResponse;
import com.courier.shared.exception.ResourceNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Pickup requests. Staff create/edit/assign/cancel; the assigned Pickup-Delivery Partner works
 * them (accept, reject, start, complete, fail). Role and branch rules live on the service.
 */
@RestController
@RequestMapping("/api/v1/pickups")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Pickups", description = "Pickup requests worked by Pickup-Delivery Partners")
public class PickupController {

    private static final int MAX_PAGE_SIZE = 100;

    private final PickupService service;
    private final com.courier.modules.company.domain.BranchRepository branchRepository;

    @GetMapping
    @Operation(summary = "List pickup requests", description = "Newest first. A partner-bound caller only sees "
            + "requests assigned to their own branch. `size` capped at 100.")
    public ApiResponse<PageResponse<PickupResponse>> list(@RequestParam(required = false) PickupStatus status,
                                                          @RequestParam(required = false) UUID pdpBranchId,
                                                          @RequestParam(required = false) UUID bookingBranchId,
                                                          @RequestParam(required = false) String search,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "20") int size) {
        Page<PickupRequest> result = service.search(status, pdpBranchId, bookingBranchId, search,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE)));
        Map<UUID, Branch> names = new HashMap<>();
        return ApiResponse.success(PageResponse.from(result, p -> toResponse(p, names)));
    }

    @GetMapping("/stats")
    @Operation(summary = "Pickup counts per status")
    public ApiResponse<StatsResponse> stats() {
        Map<PickupStatus, Long> by = service.stats();
        long open = by.entrySet().stream().filter(e -> e.getKey().isOpen()).mapToLong(Map.Entry::getValue).sum();
        return ApiResponse.success(new StatsResponse(by, open));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch a pickup request")
    public ApiResponse<PickupResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.get(id), new HashMap<>()));
    }

    @GetMapping("/{id}/suggested-partner")
    @Operation(summary = "Partner suggested for this pickup", description = "The active Pickup-Delivery Partner mapped to "
            + "the pickup's pincode in Pincode Branch Mapping. `data` is null when there is none. Admin only.")
    public ApiResponse<UUID> suggestedPartner(@PathVariable UUID id) {
        return ApiResponse.success(service.suggestPartner(id).orElse(null));
    }

    @PostMapping
    @Operation(summary = "Create a pickup request", description = "Staff only. With `pdpBranchId` it is assigned at once.")
    public ApiResponse<PickupResponse> create(@Valid @RequestBody SaveRequest r) {
        return ApiResponse.success(toResponse(service.create(toCommand(r)), new HashMap<>()), "Pickup created");
    }

    @PutMapping("/{id}")
    @Operation(summary = "Edit a pickup request", description = "Only while Requested or Assigned.")
    public ApiResponse<PickupResponse> update(@PathVariable UUID id, @Valid @RequestBody SaveRequest r) {
        return ApiResponse.success(toResponse(service.update(id, toCommand(r)), new HashMap<>()), "Pickup saved");
    }

    @PatchMapping("/{id}/assign")
    @Operation(summary = "Assign to a Pickup-Delivery Partner")
    public ApiResponse<PickupResponse> assign(@PathVariable UUID id, @Valid @RequestBody AssignRequest r) {
        return ApiResponse.success(toResponse(service.assign(id, r.pdpBranchId()), new HashMap<>()), "Pickup assigned");
    }

    @PatchMapping("/{id}/accept")
    @Operation(summary = "Partner accepts the assignment")
    public ApiResponse<PickupResponse> accept(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.accept(id), new HashMap<>()), "Pickup accepted");
    }

    @PatchMapping("/{id}/reject")
    @Operation(summary = "Partner rejects the assignment", description = "Returns the request to the pool.")
    public ApiResponse<PickupResponse> reject(@PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        return ApiResponse.success(toResponse(service.reject(id, r.reason()), new HashMap<>()), "Pickup rejected");
    }

    @PatchMapping("/{id}/start")
    @Operation(summary = "Partner heads out for the pickup")
    public ApiResponse<PickupResponse> start(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.start(id), new HashMap<>()), "Pickup started");
    }

    @PatchMapping("/{id}/complete")
    @Operation(summary = "Goods collected", description = "Records actual parcels and weight.")
    public ApiResponse<PickupResponse> complete(@PathVariable UUID id, @Valid @RequestBody CompleteRequest r) {
        return ApiResponse.success(toResponse(service.complete(id, r.actualParcels(), r.actualWeightKg()),
                new HashMap<>()), "Pickup collected");
    }

    @PatchMapping("/{id}/fail")
    @Operation(summary = "The pickup visit failed")
    public ApiResponse<PickupResponse> fail(@PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        return ApiResponse.success(toResponse(service.fail(id, r.reason()), new HashMap<>()), "Pickup marked failed");
    }

    @PatchMapping("/{id}/shipment")
    @Operation(summary = "Link the booked shipment", description = "Moves a collected pickup to Booked.")
    public ApiResponse<PickupResponse> linkShipment(@PathVariable UUID id, @Valid @RequestBody LinkShipmentRequest r) {
        return ApiResponse.success(toResponse(service.linkShipment(id, r.shipmentId(), r.shipmentNo()),
                new HashMap<>()), "Shipment linked");
    }

    @PatchMapping("/{id}/cancel")
    @Operation(summary = "Cancel a pickup request", description = "Staff only, until the goods are collected.")
    public ApiResponse<PickupResponse> cancel(@PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        return ApiResponse.success(toResponse(service.cancel(id, r.reason()), new HashMap<>()), "Pickup cancelled");
    }

    private static SaveCommand toCommand(SaveRequest r) {
        return new SaveCommand(r.customerId(), r.contactName(), r.contactMobile(), r.addressLine(), r.city(),
                r.districtId(), r.pincode(), r.expectedParcels(), r.expectedWeightKg(), r.preferredDate(),
                r.preferredSlot(), r.remarks(), r.pdpBranchId(), r.version());
    }

    private PickupResponse toResponse(PickupRequest p, Map<UUID, Branch> cache) {
        Branch b = branch(p.getPdpBranchId(), cache);
        Branch own = branch(p.getBookingBranchId(), cache);
        return new PickupResponse(p.getId(), p.getPickupNo(), p.getStatus(), p.getCustomerId(), p.getContactName(),
                p.getContactMobile(), p.getAddressLine(), p.getCity(), p.getDistrictId(), p.getPincode(),
                p.getExpectedParcels(), p.getExpectedWeightKg(), p.getPreferredDate(), p.getPreferredSlot(),
                p.getRemarks(), p.getPdpBranchId(), b == null ? null : b.getBranchCode(),
                b == null ? null : b.getBranchName(), p.getAssignedAt(), p.getAcceptedAt(), p.getStartedAt(),
                p.getPickedUpAt(), p.getActualParcels(), p.getActualWeightKg(), p.getRejectReason(),
                p.getFailReason(), p.getCancelReason(), p.getShipmentId(), p.getShipmentNo(), p.getBookingBranchId(),
                own == null ? null : own.getBranchCode(), own == null ? null : own.getBranchName(), p.getCreatedAt(),
                p.getUpdatedAt(), p.getVersion());
    }

    private Branch branch(UUID id, Map<UUID, Branch> cache) {
        if (id == null) {
            return null;
        }
        Branch b = cache.get(id);
        if (b == null) {
            // The repository, not BranchService: a branch manager may not "see" the partner branch there, but must see its name here.
            b = branchRepository.findByIdWithinCompany(id, com.courier.shared.company.CompanyContext.requireCompanyId()).orElse(null);
            if (b != null) {
                cache.put(id, b);
            }
        }
        return b;
    }
}
