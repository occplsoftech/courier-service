package com.courier.modules.tariff.api;

import com.courier.modules.tariff.api.TariffZoneDtos.AddLocationsRequest;
import com.courier.modules.tariff.api.TariffZoneDtos.CreateRequest;
import com.courier.modules.tariff.api.TariffZoneDtos.LocationRequest;
import com.courier.modules.tariff.api.TariffZoneDtos.LocationResponse;
import com.courier.modules.tariff.api.TariffZoneDtos.ResolveResponse;
import com.courier.modules.tariff.api.TariffZoneDtos.UpdateRequest;
import com.courier.modules.tariff.api.TariffZoneDtos.ZoneResponse;
import com.courier.modules.tariff.application.TariffZoneService;
import com.courier.modules.tariff.application.TariffZoneService.LocationInput;
import com.courier.modules.tariff.application.TariffZoneService.ZoneInput;
import com.courier.modules.tariff.application.TariffZoneView;
import com.courier.modules.tariff.domain.TariffZoneStatus;
import com.courier.modules.tariff.domain.ZoneUsage;
import com.courier.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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

import java.util.List;
import java.util.UUID;

/** Company tariff zones. Writes are {@code COMPANY_ADMIN} (enforced on the service); reads are any signed-in user. */
@RestController
@RequestMapping("/api/v1/tariff-zones")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Tariff Zones", description = "Company zones (PUNE, METRO, KERALA…) and the states, districts, cities and pincodes in them")
public class TariffZoneController {

    private final TariffZoneService service;

    @PostMapping
    @Operation(summary = "Create a zone", description = "Code unique per company. Locations are optional and are all "
            + "checked before anything is saved.")
    public ResponseEntity<ApiResponse<ZoneResponse>> create(@Valid @RequestBody CreateRequest request) {
        TariffZoneView created = service.create(
                new ZoneInput(request.code(), request.name(), request.usage(), request.description(), request.displayOrder()),
                inputs(request.locations()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toResponse(created, true), "Zone created"));
    }

    @GetMapping
    @Operation(summary = "List zones", description = "Optionally by usage, status or a code/name search. Locations are "
            + "counted, not listed; fetch one zone for its locations.")
    public ApiResponse<List<ZoneResponse>> list(@RequestParam(required = false) ZoneUsage usage,
                                                @RequestParam(required = false) TariffZoneStatus status,
                                                @RequestParam(required = false) String search) {
        return ApiResponse.success(service.search(usage, status, search).stream().map(v -> toResponse(v, false)).toList());
    }

    @GetMapping("/resolve")
    @Operation(summary = "Which zone is this pincode in?", description = "`direction` is TO (delivered here) or FROM "
            + "(starts here). The most specific location wins: pincode, city, district, state. `zone` is null when none covers it.")
    public ApiResponse<ResolveResponse> resolve(@RequestParam String pincode,
                                                @RequestParam(defaultValue = "TO") ZoneUsage direction) {
        return ApiResponse.success(service.resolve(pincode, direction)
                .map(r -> new ResolveResponse(pincode.trim(), direction, location(r.place()),
                        toResponse(new TariffZoneView(r.zone(), List.of()), false), location(r.matchedBy())))
                .orElseGet(() -> new ResolveResponse(pincode.trim(), direction, null, null, null)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch a zone with its locations")
    public ApiResponse<ZoneResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.getById(id), true));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Edit a zone", description = "`version` required; stale returns 409. Widening the usage is "
            + "refused if the zone would then share a place with another zone.")
    public ApiResponse<ZoneResponse> update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest request) {
        return ApiResponse.success(toResponse(service.update(id,
                new ZoneInput(request.code(), request.name(), request.usage(), request.description(), request.displayOrder()),
                request.version()), true), "Zone updated");
    }

    @PostMapping("/{id}/locations")
    @Operation(summary = "Add states, districts, cities or pincodes", description = "All or nothing: one place already "
            + "in a competing zone refuses the whole list.")
    public ApiResponse<ZoneResponse> addLocations(@PathVariable UUID id, @Valid @RequestBody AddLocationsRequest request) {
        return ApiResponse.success(toResponse(service.addLocations(id, inputs(request.locations())), true), "Locations added");
    }

    @DeleteMapping("/{id}/locations/{locationId}")
    @Operation(summary = "Remove a location from a zone")
    public ApiResponse<ZoneResponse> removeLocation(@PathVariable UUID id, @PathVariable UUID locationId) {
        return ApiResponse.success(toResponse(service.removeLocation(id, locationId), true), "Location removed");
    }

    @PatchMapping("/{id}/activate")
    @Operation(summary = "Activate a zone")
    public ApiResponse<ZoneResponse> activate(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.activate(id), true), "Zone activated");
    }

    @PatchMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate a zone", description = "An inactive zone is skipped when a pincode is resolved.")
    public ApiResponse<ZoneResponse> deactivate(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.deactivate(id), true), "Zone deactivated");
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a zone", description = "Soft-deletes the zone and its locations.")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ApiResponse.success("Zone deleted");
    }

    private static List<LocationInput> inputs(List<LocationRequest> requests) {
        return requests == null ? List.of() : requests.stream()
                .map(l -> new LocationInput(l.scope(), l.stateId(), l.districtId(), l.cityId(), l.pincode())).toList();
    }

    private static ZoneResponse toResponse(TariffZoneView v, boolean withLocations) {
        var z = v.zone();
        return new ZoneResponse(z.getId(), z.getCode(), z.getName(), z.getZoneUsage(), z.getDescription(), z.getDisplayOrder(),
                z.getStatus(), v.locations().size(),
                withLocations ? v.locations().stream().map(TariffZoneController::location).toList() : List.of(),
                z.getCreatedAt(), z.getUpdatedAt(), z.getVersion());
    }

    private static LocationResponse location(TariffZoneView.LocationView l) {
        return l == null ? null : new LocationResponse(l.id(), l.scope(), l.label(), l.stateId(), l.stateName(),
                l.districtId(), l.districtName(), l.cityId(), l.cityName(), l.pincode());
    }
}
