package com.courier.modules.finance.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/v1/branch-wallet/cash-handovers/{id}/void}. */
@Schema(name = "VoidCashHandoverRequest")
public record VoidCashHandoverRequest(@NotBlank @Size(max = 500) String reason) {
}
