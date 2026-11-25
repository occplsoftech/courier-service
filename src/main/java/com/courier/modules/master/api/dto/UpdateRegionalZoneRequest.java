package com.courier.modules.master.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Body of {@code PUT /api/v1/global-masters/regional-zones/{id}}. */
@Schema(name = "UpdateRegionalZoneRequest", description = "Full replacement of a regional zone's editable fields")
public record UpdateRegionalZoneRequest(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 500) String description,
        @PositiveOrZero Integer displayOrder,
        @NotNull @PositiveOrZero Long version
) {
}
