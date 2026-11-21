package com.courier.modules.master.api.dto;

import com.courier.modules.master.domain.MasterStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

/** A service type. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "ServiceTypeResponse", description = "Service type")
public record ServiceTypeResponse(
        UUID id, UUID companyId, String code, String name, String description,
        MasterStatus status, Integer displayOrder,
        Integer deliveryDays, boolean express, LocalTime cutoffTime, Integer priority,
        Integer volumetricDivisor,
        UUID createdBy, Instant createdDate, UUID updatedBy, Instant updatedDate, Long version
) {
}
