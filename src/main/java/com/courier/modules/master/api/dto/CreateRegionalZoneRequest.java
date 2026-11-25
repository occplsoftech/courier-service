package com.courier.modules.master.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/v1/global-masters/regional-zones}. */
@Schema(name = "CreateRegionalZoneRequest", description = "New regional zone")
public record CreateRegionalZoneRequest(
        @NotBlank @Size(max = 50)
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_ -]{0,48}[A-Za-z0-9]$",
                message = "2-50 chars of letters, digits, space, hyphen or underscore")
        @Schema(example = "X_PUNE") String code,
        @NotBlank @Size(max = 150) @Schema(example = "X - Pune") String name,
        @Size(max = 500) String description,
        @PositiveOrZero Integer displayOrder
) {
}
