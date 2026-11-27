package com.courier.modules.master.api;

import com.courier.modules.master.api.dto.BulkImportPincodesRequest;
import com.courier.modules.master.api.dto.BulkImportPincodesResponse;
import com.courier.modules.master.api.dto.CreatePincodeRequest;
import com.courier.modules.master.api.dto.MasterSearchRequest;
import com.courier.modules.master.api.dto.PincodeAreaLookupResponse;
import com.courier.modules.master.api.dto.PincodeAreaResponse;
import com.courier.modules.master.api.dto.PincodeGeoResponse;
import com.courier.modules.master.api.dto.PincodeResponse;
import com.courier.modules.master.api.dto.UpdatePincodeAreaRequest;
import com.courier.modules.master.api.dto.UpdatePincodeRequest;
import com.courier.modules.master.application.PincodeAreaLookupResult;
import com.courier.modules.master.application.PincodeAreaService;
import com.courier.modules.master.application.PincodeBulkImportResult;
import com.courier.modules.master.application.PincodeBulkImportService;
import com.courier.modules.master.application.PincodeService;
import com.courier.modules.master.domain.Pincode;
import com.courier.modules.master.domain.MasterDataCriteria;
import com.courier.shared.api.ApiResponse;
import com.courier.shared.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Pincodes, the leaf of the geography hierarchy.
 *
 * <p>The {@code code} is the postal code itself and is digits only; {@code name} is the
 * post office label. One pincode belongs to exactly one area and exists once per company.
 *
 * <p>Setting {@code serviceable} false forces the COD, prepaid and pickup flags false with
 * it: a pincode nobody delivers to cannot offer cash on delivery.
 */
@RestController
@RequestMapping("/api/v1/global-masters/pincodes")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Global Masters - Pincodes", description = "Pincode master, within an area. Global: SUPER_ADMIN writes, anyone signed in reads.")
public class PincodeController {

    private final PincodeService service;
    private final PincodeMasterMapper mapper;
    private final MasterCriteriaMapper criteriaMapper;
    private final PincodeBulkImportService bulkImportService;
    private final PincodeAreaService pincodeAreaService;

    @PostMapping
    @Operation(summary = "Create a pincode",
            description = "`COMPANY_ADMIN` only. The area must belong to this company and be active. The pincode is unique within the company.")
    public ResponseEntity<ApiResponse<PincodeResponse>> create(
            @Valid @RequestBody CreatePincodeRequest request) {
        Pincode created = service.create(mapper.toCommand(request));
        return ResponseEntity
                .created(UriComponentsBuilder.fromPath("/api/v1/master/pincodes/{id}")
                        .buildAndExpand(created.getId()).toUri())
                .body(ApiResponse.success(mapper.toResponse(created), "Pincode created"));
    }

    @GetMapping("/{code}/geo")
    @Operation(summary = "Area/City/District already on file for a pincode",
            description = """
                    Read-only — no postal-directory call, nothing created, same read
                    audience as every other GET here. `matched=false` when no pincode
                    with this code is on file yet; use `/lookup/{code}` (COMPANY_ADMIN)
                    to resolve one from the postal directory first. Pass `areaId` when
                    the caller already knows which of this pincode's Areas it wants
                    (e.g. a booked shipment's own selection) — otherwise resolves off
                    the pincode's single fixed "primary" Area.
                    """)
    public ApiResponse<PincodeGeoResponse> geo(@PathVariable String code,
                                                @RequestParam(required = false) UUID areaId) {
        return ApiResponse.success(service.geography(code, areaId)
                .map(g -> new PincodeGeoResponse(true, g.areaName(), g.cityName(), g.districtName(), g.stateName()))
                .orElseGet(PincodeGeoResponse::notFound));
    }

    @GetMapping("/lookup/{code}")
    @Operation(summary = "Resolve the Area for a raw pincode",
            description = """
                    Looks the pincode up in India's postal directory and resolves (auto-
                    creating if missing) the matching State/District/City/Area chain. Same
                    write audience as create — a match can create master rows. `matched`
                    is false, not an error, when the directory has no record of this
                    pincode; the create form falls back to the manual Area picker.
                    """)
    public ApiResponse<PincodeAreaLookupResponse> lookupArea(@PathVariable String code) {
        return ApiResponse.success(service.lookupPostalArea(code)
                .map(this::toLookupResponse)
                .orElseGet(PincodeAreaLookupResponse::notFound));
    }

    private PincodeAreaLookupResponse toLookupResponse(PincodeAreaLookupResult r) {
        List<PincodeAreaLookupResponse.PincodeAreaPreview> areas = new ArrayList<>();
        for (int i = 0; i < r.allMatches().size(); i++) {
            var match = r.allMatches().get(i);
            areas.add(new PincodeAreaLookupResponse.PincodeAreaPreview(
                    match.area().getId(), match.area().getName(), match.city().getName(), i == 0));
        }
        return new PincodeAreaLookupResponse(true,
                r.area().getId(), r.area().getName(),
                r.city().getName(), r.district().getName(), r.state().getName(), r.country().getName(),
                r.postOfficeName(), r.alternateCount(), areas);
    }

    @PostMapping("/bulk-import")
    @Operation(summary = "Bulk-import pincodes across numeric ranges",
            description = """
                    Same write audience as create. Probes every code in each range against
                    the postal directory and creates the ones that resolve to a real post
                    office (auto-creating the State/District/City/Area chain, same as the
                    single-pincode lookup). Safe to re-run the same range — a code already
                    on file is skipped, not duplicated. Synchronous: a large range may take
                    a long time (one HTTP round trip to the postal directory per candidate
                    code), so keep ranges to real, dense blocks rather than scanning an
                    entire numeric span blind.
                    """)
    public ApiResponse<BulkImportPincodesResponse> bulkImport(
            @Valid @RequestBody BulkImportPincodesRequest request) {
        PincodeBulkImportResult result = bulkImportService.importRanges(
                request.ranges().stream()
                        .map(r -> new PincodeBulkImportService.Range(r.fromCode(), r.toCode()))
                        .toList());
        return ApiResponse.success(new BulkImportPincodesResponse(result.probed(), result.created(),
                        result.alreadyExisted(), result.noPostalMatch(), result.failed()),
                "Bulk import: %d created, %d already existed, %d no match, %d failed (of %d probed)"
                        .formatted(result.created(), result.alreadyExisted(), result.noPostalMatch(),
                                result.failed(), result.probed()));
    }

    @GetMapping("/{id}/areas")
    @Operation(summary = "Every Area this pincode's postal record names",
            description = """
                    A pincode routinely maps to several real post offices — this lists
                    every one this platform has discovered for it (the row matching the
                    pincode's own `areaId` first), each with its own ODA setting. Any
                    authenticated company user reads it.
                    """)
    public ApiResponse<List<PincodeAreaResponse>> areas(@PathVariable UUID id) {
        return ApiResponse.success(pincodeAreaService.list(id).stream()
                .map(this::toAreaResponse)
                .toList());
    }

    @PatchMapping("/{id}/areas/{areaLinkId}")
    @Operation(summary = "Set ODA and/or serviceability for one Area of this pincode",
            description = "Same write audience as create. A fresh 250.00 fills in "
                    + "server-side the moment `odaApplicable` turns true with no amount given. `serviceable=false` stops bookings to this area.")
    public ApiResponse<PincodeAreaResponse> updateArea(@PathVariable UUID id, @PathVariable UUID areaLinkId,
                                                        @Valid @RequestBody UpdatePincodeAreaRequest request) {
        return ApiResponse.success(toAreaResponse(
                pincodeAreaService.update(id, areaLinkId, request.odaApplicable(), request.odaAmount(), request.serviceable())));
    }

    private PincodeAreaResponse toAreaResponse(PincodeAreaService.Row row) {
        return new PincodeAreaResponse(row.link().getId(), row.link().getAreaId(), row.areaName(), row.cityName(),
                row.link().isPrimary(), row.link().isOdaApplicable(), row.link().getOdaAmount(), row.link().isServiceable());
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a pincode",
            description = "Full replacement of the editable fields. `version` is required; "
                    + "a stale value returns 409. The code cannot be changed.")
    public ApiResponse<PincodeResponse> update(@PathVariable UUID id,
                                           @Valid @RequestBody UpdatePincodeRequest request) {
        return ApiResponse.success(mapper.toResponse(service.update(id, mapper.toCommand(request))),
                "Pincode updated");
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch a pincode", description = "Any authenticated company user.")
    public ApiResponse<PincodeResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(mapper.toResponse(service.getById(id)));
    }

    @GetMapping
    @Operation(summary = "List pincodes",
            description = """
                    Paged, sorted, filtered, searchable. Filter by `areaId`, `districtId` or
                    `stateId` (each widens to the areas it covers; `areaId` combined with
                    one of those intersects), by `serviceable`, `odaApplicable`, or `zone`
                    (case-insensitive exact match) — the booking/ops screen's usual filters.
                    Sort: `code`, `name`, `status`, `displayOrder`, `createdDate`, `updatedDate`.
                    """)
    public ApiResponse<PageResponse<PincodeResponse>> list(
            @Valid @ParameterObject MasterSearchRequest search,
            @Parameter(description = "Only pincodes of this area")
            @RequestParam(required = false) UUID areaId,
            @Parameter(description = "Only pincodes within this district")
            @RequestParam(required = false) UUID districtId,
            @Parameter(description = "Only pincodes within this state")
            @RequestParam(required = false) UUID stateId,
            @Parameter(description = "Only serviceable (or only unserviceable) pincodes")
            @RequestParam(required = false) Boolean serviceable,
            @Parameter(description = "Only ODA (or only non-ODA) pincodes")
            @RequestParam(required = false) Boolean odaApplicable,
            @Parameter(description = "Only pincodes in this rate-master delivery zone")
            @RequestParam(required = false) String zone,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {

        MasterDataCriteria criteria = criteriaMapper.toCriteria(search)
                .with("serviceable", serviceable).with("odaApplicable", odaApplicable)
                .with("zone", zone);

        Set<UUID> geographyAreaIds = mapper.resolveAreaIdsForGeography(districtId, stateId);
        if (geographyAreaIds != null) {
            if (areaId != null) {
                geographyAreaIds = geographyAreaIds.contains(areaId) ? Set.of(areaId) : Set.of();
            }
            criteria = criteria.with("areaId", geographyAreaIds);
        } else {
            criteria = criteria.with("areaId", areaId);
        }

        return ApiResponse.success(mapper.toPage(
                service.search(criteria, MasterSortSupport.sanitise(pageable))));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a pincode",
            description = "Soft delete, `COMPANY_ADMIN` only. The pincode stays reserved.")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ApiResponse.success("Pincode deleted");
    }

    @PatchMapping("/{id}/activate")
    @Operation(summary = "Activate a pincode",
            description = "`COMPANY_ADMIN`. Refused with 422 if the parent area is inactive. Idempotent.")
    public ApiResponse<PincodeResponse> activate(@PathVariable UUID id) {
        return ApiResponse.success(mapper.toResponse(service.activate(id)), "Pincode activated");
    }

    @PatchMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate a pincode",
            description = "`COMPANY_ADMIN`. Withdraws it from the pickers; existing "
                    + "references keep resolving. Idempotent.")
    public ApiResponse<PincodeResponse> deactivate(@PathVariable UUID id) {
        return ApiResponse.success(mapper.toResponse(service.deactivate(id)), "Pincode deactivated");
    }
}
