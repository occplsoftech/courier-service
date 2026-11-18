package com.courier.modules.master.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;

/**
 * Body of {@code PATCH /api/v1/global-masters/pincodes/{id}/areas/{areaLinkId}}.
 *
 * <p>Both fields optional and independent: send only {@code odaApplicable} to flip the
 * flag (a fresh 250.00 fills in server-side when it turns true with no amount yet), or
 * only {@code odaAmount} to change the figure without touching the flag.
 */
@Schema(name = "UpdatePincodeAreaRequest", description = "Per-area ODA setting on a pincode")
public record UpdatePincodeAreaRequest(
        Boolean odaApplicable,
        @DecimalMin(value = "0", message = "must not be negative") BigDecimal odaAmount,
        Boolean serviceable
) {
}
