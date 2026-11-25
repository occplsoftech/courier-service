package com.courier.modules.master.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;

/** How one delivery network (document, surface or air) covers a pincode. Every field optional. */
@Schema(name = "NetworkCoverage", description = "Serviceability, ODA and delivery time of one network at a pincode")
public record NetworkCoverageDto(
        @Schema(description = "Whether this network delivers here; null = use the pincode's own serviceable flag")
        Boolean serviceable,
        @Schema(description = "Whether it delivers here as ODA; null = use the pincode's own ODA flag")
        Boolean oda,
        @Positive @Max(2400) @Schema(description = "Promised delivery time in hours", example = "48")
        Integer tatHours
) {
}
