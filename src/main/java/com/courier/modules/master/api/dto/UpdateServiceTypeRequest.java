package com.courier.modules.master.api.dto;

import com.courier.modules.master.domain.ServiceNetwork;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;

/** Body of {@code PUT /api/v1/master/service-types/{id}}. */
@Schema(name = "UpdateServiceTypeRequest", description = "Full replacement of a service type's editable fields")
public record UpdateServiceTypeRequest(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 500) String description,
        @PositiveOrZero Integer displayOrder,
        @PositiveOrZero Integer deliveryDays,
        Boolean express,
        LocalTime cutoffTime,
        @PositiveOrZero Integer priority,
        @Positive Integer volumetricDivisor,
        ServiceNetwork network,
        @NotNull @PositiveOrZero Long version
) {
}
