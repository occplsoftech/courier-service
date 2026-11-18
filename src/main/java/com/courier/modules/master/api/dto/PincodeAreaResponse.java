package com.courier.modules.master.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/** One Area a pincode's postal record names, with its own ODA setting. */
@Schema(name = "PincodeAreaResponse", description = "An Area linked to a pincode, with its own ODA setting")
public record PincodeAreaResponse(
        UUID id,
        UUID areaId, String areaName, String cityName,
        @Schema(description = "The Area master_pincodes.area_id itself points to") boolean primary,
        boolean odaApplicable, BigDecimal odaAmount,
        @Schema(description = "This area can be booked to (the pincode must be serviceable too)") boolean serviceable
) {
}
