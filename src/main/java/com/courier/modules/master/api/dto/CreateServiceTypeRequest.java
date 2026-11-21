package com.courier.modules.master.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;

/** Body of {@code POST /api/v1/master/service-types}. */
@Schema(name = "CreateServiceTypeRequest", description = "New service type")
public record CreateServiceTypeRequest(
        @NotBlank @Size(max = 50)
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_ -]{0,48}[A-Za-z0-9]$",
                message = "2-50 chars of letters, digits, space, hyphen or underscore")
        @Schema(example = "EXPRESS") String code,
        @NotBlank @Size(max = 150) @Schema(example = "Express") String name,
        @Size(max = 500) String description,
        @PositiveOrZero Integer displayOrder,
        @PositiveOrZero
        @Schema(description = "Promised transit in days; 0 is same day") Integer deliveryDays,
        Boolean express,
        @Schema(description = "Last booking time that still makes today's promise",
                example = "18:00:00") LocalTime cutoffTime,
        @PositiveOrZero
        @Schema(description = "Ranking when more than one service can carry a shipment; higher wins")
        Integer priority,
        @Positive
        @Schema(description = "cm3-per-kg divisor of L x W x H for volumetric weight on this service; "
                + "empty uses the default (5000)", example = "5000")
        Integer volumetricDivisor
) {
}
