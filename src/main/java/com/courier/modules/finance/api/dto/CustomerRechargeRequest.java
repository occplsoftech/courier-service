package com.courier.modules.finance.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** Step one of a customer's online recharge: how much. The customer is the signed-in account. */
@Schema(name = "CustomerRechargeRequest", description = "Open a gateway order to recharge the signed-in customer's wallet")
public record CustomerRechargeRequest(
        @NotNull
        @DecimalMin(value = "1.00", message = "must be at least 1.00")
        @DecimalMax(value = "10000000.0000", message = "must not exceed 10,000,000")
        @Digits(integer = 15, fraction = 4)
        @Schema(example = "5000.00") BigDecimal amount
) {
}
