package com.courier.modules.tariff.api;

import com.courier.modules.tariff.domain.LocationScope;
import com.courier.modules.tariff.domain.TariffZoneStatus;
import com.courier.modules.tariff.domain.ZoneUsage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Wire contract of {@code /api/v1/tariff-zones}. */
public final class TariffZoneDtos {

    private TariffZoneDtos() {
    }

    /** Only the id for the scope is needed: STATE stateId, DISTRICT districtId, CITY cityId, PINCODE pincode. */
    public record LocationRequest(@NotNull LocationScope scope, UUID stateId, UUID districtId, UUID cityId,
                                  @Size(max = 6) String pincode) {
    }

    public record CreateRequest(
            @NotBlank @Size(max = 30) String code,
            @NotBlank @Size(max = 100) String name,
            @NotNull ZoneUsage usage,
            @Size(max = 255) String description,
            @PositiveOrZero Integer displayOrder,
            @Valid @Size(max = 2000) List<LocationRequest> locations) {
    }

    public record UpdateRequest(
            @NotBlank @Size(max = 30) String code,
            @NotBlank @Size(max = 100) String name,
            @NotNull ZoneUsage usage,
            @Size(max = 255) String description,
            @PositiveOrZero Integer displayOrder,
            @NotNull @PositiveOrZero Long version) {
    }

    public record AddLocationsRequest(@NotEmpty @Size(max = 2000) @Valid List<LocationRequest> locations) {
    }

    public record LocationResponse(UUID id, LocationScope scope, String label, UUID stateId, String stateName,
                                   UUID districtId, String districtName, UUID cityId, String cityName, String pincode) {
    }

    public record ZoneResponse(UUID id, String code, String name, ZoneUsage usage, String description, int displayOrder,
                               TariffZoneStatus status, int locationCount, List<LocationResponse> locations,
                               Instant createdDate, Instant updatedDate, Long version) {
    }

    /** {@code zone} is null when no active zone covers the pincode for that direction. */
    public record ResolveResponse(String pincode, ZoneUsage direction, LocationResponse place, ZoneResponse zone,
                                  LocationResponse matchedBy) {
    }
}
