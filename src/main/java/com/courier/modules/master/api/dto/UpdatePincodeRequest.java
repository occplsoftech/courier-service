package com.courier.modules.master.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Body of {@code PUT /api/v1/master/pincodes/{id}}. The pincode itself is immutable. */
@Schema(name = "UpdatePincodeRequest", description = "Full replacement of a pincode's editable fields")
public record UpdatePincodeRequest(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 500) String description,
        @PositiveOrZero Integer displayOrder,
        @NotNull UUID areaId,
        Boolean serviceable,
        Boolean codAvailable,
        Boolean prepaidAvailable,
        Boolean pickupAvailable,
        @Size(max = 20) String zone,
        Boolean odaApplicable,
        @Schema(description = "Regional zone (pricing region) id") UUID regionalZoneId,
        @Valid @Schema(description = "Document network coverage") NetworkCoverageDto doc,
        @Valid @Schema(description = "Surface network coverage") NetworkCoverageDto surface,
        @Valid @Schema(description = "Air network coverage") NetworkCoverageDto air,
        @NotNull @PositiveOrZero Long version
) {
}
