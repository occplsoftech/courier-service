package com.courier.modules.master.api;

import com.courier.modules.master.api.dto.CreateRegionalZoneRequest;
import com.courier.modules.master.api.dto.MasterSearchRequest;
import com.courier.modules.master.api.dto.RegionalZoneResponse;
import com.courier.modules.master.api.dto.UpdateRegionalZoneRequest;
import com.courier.modules.master.application.RegionalZoneService;
import com.courier.modules.master.domain.RegionalZone;
import com.courier.modules.master.domain.MasterDataCriteria;
import com.courier.shared.api.ApiResponse;
import com.courier.shared.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.UUID;

/**
 * Regional zones: the pricing regions pincodes are grouped into, such as PUNE, X_PUNE or
 * REST_OF_INDIA. Global, like the pincodes; written by a super admin or a company admin.
 */
@RestController
@RequestMapping("/api/v1/global-masters/regional-zones")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Global Masters - Regional Zones", description = "Regional zone master")
public class RegionalZoneController {

    private final RegionalZoneService service;
    private final RegionalZoneMasterMapper mapper;
    private final MasterCriteriaMapper criteriaMapper;

    @PostMapping
    @Operation(summary = "Create a regional zone",
            description = "`SUPER_ADMIN` or `COMPANY_ADMIN`. The code is unique.")
    public ResponseEntity<ApiResponse<RegionalZoneResponse>> create(
            @Valid @RequestBody CreateRegionalZoneRequest request) {
        RegionalZone created = service.create(mapper.toCommand(request));
        return ResponseEntity
                .created(UriComponentsBuilder.fromPath("/api/v1/global-masters/regional-zones/{id}")
                        .buildAndExpand(created.getId()).toUri())
                .body(ApiResponse.success(mapper.toResponse(created), "Regional zone created"));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a regional zone",
            description = "Full replacement of the editable fields. `version` is required; "
                    + "a stale value returns 409. The code cannot be changed.")
    public ApiResponse<RegionalZoneResponse> update(@PathVariable UUID id,
                                           @Valid @RequestBody UpdateRegionalZoneRequest request) {
        return ApiResponse.success(mapper.toResponse(service.update(id, mapper.toCommand(request))),
                "Regional zone updated");
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch a regional zone", description = "Any authenticated company user.")
    public ApiResponse<RegionalZoneResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(mapper.toResponse(service.getById(id)));
    }

    @GetMapping
    @Operation(summary = "List regional zones",
            description = """
                    Paged, sorted, filtered, searchable. Sort: `code`, `name`, `status`,
                    `displayOrder`, `createdDate`, `updatedDate`.
                    """)
    public ApiResponse<PageResponse<RegionalZoneResponse>> list(
            @Valid @ParameterObject MasterSearchRequest search,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {

        MasterDataCriteria criteria = criteriaMapper.toCriteria(search);

        return ApiResponse.success(mapper.toPage(
                service.search(criteria, MasterSortSupport.sanitise(pageable))));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a regional zone",
            description = "Soft delete, `SUPER_ADMIN` or `COMPANY_ADMIN`. The code stays reserved.")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ApiResponse.success("Regional zone deleted");
    }

    @PatchMapping("/{id}/activate")
    @Operation(summary = "Activate a regional zone",
            description = "`COMPANY_ADMIN`. Idempotent.")
    public ApiResponse<RegionalZoneResponse> activate(@PathVariable UUID id) {
        return ApiResponse.success(mapper.toResponse(service.activate(id)), "Regional zone activated");
    }

    @PatchMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate a regional zone",
            description = "`COMPANY_ADMIN`. Withdraws it from the pickers; existing "
                    + "references keep resolving. Idempotent.")
    public ApiResponse<RegionalZoneResponse> deactivate(@PathVariable UUID id) {
        return ApiResponse.success(mapper.toResponse(service.deactivate(id)), "Regional zone deactivated");
    }
}
