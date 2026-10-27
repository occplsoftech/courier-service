package com.courier.modules.districtfreight.api;

import com.courier.modules.districtfreight.api.dto.CreateDistrictLevelFreightRequest;
import com.courier.modules.districtfreight.api.dto.DistrictLevelFreightResponse;
import com.courier.modules.districtfreight.api.dto.DistrictLevelFreightSearchRequest;
import com.courier.modules.districtfreight.api.dto.FreightCalculationRequest;
import com.courier.modules.districtfreight.api.dto.FreightCalculationResponse;
import com.courier.modules.districtfreight.api.dto.ImportSummaryResponse;
import com.courier.modules.districtfreight.api.dto.UpdateDistrictLevelFreightRequest;
import com.courier.modules.districtfreight.application.DistrictLevelFreightExcelImportService;
import com.courier.modules.districtfreight.application.DistrictLevelFreightService;
import com.courier.modules.districtfreight.application.FreightCalculationService;
import com.courier.modules.districtfreight.domain.DistrictLevelFreight;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * District Level Freight: rate setup by From Station + Destination District + weight
 * slab. Enforced on {@link DistrictLevelFreightService}: {@code COMPANY_ADMIN} writes,
 * every authenticated user of the company reads. {@code /calculate} is Shipment Booking's
 * own live-preview seam onto {@link FreightCalculationService} — the same service
 * ShipmentServiceImpl calls authoritatively at booking time.
 */
@RestController
@RequestMapping("/api/v1/district-level-freight")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "District Level Freight", description = "Rate setup: From Station + District + six weight-slab rates + ODA")
public class DistrictLevelFreightController {

    private static final Map<String, String> SORTABLE = Map.ofEntries(
            Map.entry("status", "status"),
            Map.entry("createdDate", "createdAt"),
            Map.entry("createdAt", "createdAt"),
            Map.entry("updatedDate", "updatedAt"),
            Map.entry("updatedAt", "updatedAt"));

    private static final int MAX_PAGE_SIZE = 100;

    private final DistrictLevelFreightService service;
    private final DistrictLevelFreightMapper mapper;
    private final DistrictLevelFreightExcelImportService importService;
    private final FreightCalculationService freightCalculationService;

    @PostMapping("/calculate")
    @Operation(summary = "Calculate freight for a booking", description = """
            From Station + destination pincode + chargeable weight -> the matched
            District Level Freight row's weight-slab rate, base freight, ODA charge and
            total freight. Any authenticated company user (a booking clerk, not just
            `COMPANY_ADMIN`). The same calculation Shipment Booking itself performs and
            re-verifies server-side at Confirm Booking — this endpoint is for live preview
            only, nothing here is trusted at booking time.
            """)
    public ApiResponse<FreightCalculationResponse> calculate(@Valid @RequestBody FreightCalculationRequest request) {
        return ApiResponse.success(mapper.toResponse(freightCalculationService.calculate(
                request.bookingBranchId(), request.destinationPincode(), request.destinationAreaId(),
                request.chargeableWeight(), request.businessCustomerId(), request.declaredValue(),
                new FreightCalculationService.TariffInput(request.serviceTypeId(), request.packageTypeId(),
                        request.numberOfPackages(), request.bookingDate()))));
    }

    @PostMapping
    @Operation(summary = "Create a District Level Freight rate",
            description = "`COMPANY_ADMIN`. Branch must be active, district must be active. "
                    + "The From Station + District combination must be unique within the company.")
    public ResponseEntity<ApiResponse<DistrictLevelFreightResponse>> create(
            @Valid @RequestBody CreateDistrictLevelFreightRequest request) {
        DistrictLevelFreight created = service.create(mapper.toCommand(request));
        return ResponseEntity
                .created(UriComponentsBuilder.fromPath("/api/v1/district-level-freight/{id}")
                        .buildAndExpand(created.getId()).toUri())
                .body(ApiResponse.success(mapper.toResponse(created), "District Level Freight rate created"));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a District Level Freight rate",
            description = "Full replacement of the editable fields. `version` required; a "
                    + "stale value returns 409.")
    public ApiResponse<DistrictLevelFreightResponse> update(@PathVariable UUID id,
            @Valid @RequestBody UpdateDistrictLevelFreightRequest request) {
        DistrictLevelFreight updated = service.update(id, mapper.toCommand(request));
        return ApiResponse.success(mapper.toResponse(updated), "District Level Freight rate updated");
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch a District Level Freight rate")
    public ApiResponse<DistrictLevelFreightResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(mapper.toResponse(service.getById(id)));
    }

    @GetMapping
    @Operation(summary = "List District Level Freight rates", description = """
            Paged, filtered by From Station / District / status. Sort: `status`,
            `createdDate`, `updatedDate`. `size` capped at 100.
            """)
    public ApiResponse<PageResponse<DistrictLevelFreightResponse>> list(
            @Valid @ParameterObject DistrictLevelFreightSearchRequest search,
            @ParameterObject @PageableDefault(size = 20, sort = "createdDate", direction = Sort.Direction.DESC)
            Pageable pageable) {
        Page<DistrictLevelFreight> page = service.search(mapper.toCriteria(search), sanitise(pageable));
        return ApiResponse.success(mapper.toPage(page));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a District Level Freight rate",
            description = "Soft delete, `COMPANY_ADMIN` only. Rate setup only — nothing in "
                    + "this codebase references a row yet, so this is always permitted.")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ApiResponse.success("District Level Freight rate deleted");
    }

    @PatchMapping("/{id}/activate")
    @Operation(summary = "Activate a District Level Freight rate", description = "`COMPANY_ADMIN`. Idempotent.")
    public ApiResponse<DistrictLevelFreightResponse> activate(@PathVariable UUID id) {
        return ApiResponse.success(mapper.toResponse(service.activate(id)), "District Level Freight rate activated");
    }

    @PatchMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate a District Level Freight rate", description = "`COMPANY_ADMIN`. Idempotent.")
    public ApiResponse<DistrictLevelFreightResponse> deactivate(@PathVariable UUID id) {
        return ApiResponse.success(mapper.toResponse(service.deactivate(id)), "District Level Freight rate deactivated");
    }

    @PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Preview an Excel import", description = "`COMPANY_ADMIN`. Parses "
            + "and validates the sheet — From Station, District, and the six weight-slab "
            + "rate columns — without writing anything. Blank rows and the ODA note row "
            + "are ignored, not reported. Each row is classified WOULD_CREATE, "
            + "WOULD_UPDATE (an existing From Station + District combination) or ERROR.")
    public ApiResponse<ImportSummaryResponse> previewImport(@RequestParam("file") MultipartFile file) {
        return ApiResponse.success(importService.preview(file), "Import preview ready");
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Import an Excel file", description = "`COMPANY_ADMIN`. Same "
            + "validation as the preview, but each valid row is committed — a new From "
            + "Station + District combination is created, an existing one is updated "
            + "(upsert). Each row commits in its own transaction, so one bad row does not "
            + "block the rest.")
    public ApiResponse<ImportSummaryResponse> commitImport(@RequestParam("file") MultipartFile file) {
        ImportSummaryResponse result = importService.commit(file);
        return ApiResponse.success(result, "Import complete: %d succeeded, %d failed"
                .formatted(result.succeeded(), result.failed()));
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
        Sort sort = orders.isEmpty() ? Sort.by(Sort.Order.desc("createdAt")) : Sort.by(orders);
        return PageRequest.of(pageable.getPageNumber(), size, sort);
    }
}
