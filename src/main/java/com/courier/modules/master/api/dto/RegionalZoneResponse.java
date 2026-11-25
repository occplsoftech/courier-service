package com.courier.modules.master.api.dto;

import com.courier.modules.master.domain.MasterStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/** A regional zone. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "RegionalZoneResponse", description = "Regional zone")
public record RegionalZoneResponse(
        UUID id, UUID companyId, String code, String name, String description,
        MasterStatus status, Integer displayOrder,
        UUID createdBy, Instant createdDate, UUID updatedBy, Instant updatedDate, Long version
) {
}
