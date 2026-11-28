package com.courier.modules.finance.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** Body of approve / reject / void on a cash handover. Required (non-blank) for reject and void. */
@Schema(name = "CashHandoverDecisionRequest")
public record CashHandoverDecisionRequest(@Size(max = 500) String reason) {
}
