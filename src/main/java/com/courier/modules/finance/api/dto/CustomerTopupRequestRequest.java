package com.courier.modules.finance.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** A customer asks the courier company to credit their wallet after paying by transfer or cash. */
@Schema(name = "CustomerTopupRequestRequest", description = "Ask the company to credit the signed-in customer's wallet")
public record CustomerTopupRequestRequest(
        @NotNull
        @DecimalMin(value = "0.0001", message = "must be greater than zero")
        @DecimalMax(value = "10000000.0000", message = "must not exceed 10,000,000")
        @Digits(integer = 15, fraction = 4)
        @Schema(example = "5000.00") BigDecimal amount,
        @Size(max = 500) @Schema(example = "Bank transfer, UTR 123456789") String remarks,
        @NotBlank(message = "Upload proof of payment before raising a top-up request")
        @Size(max = 500)
        @Schema(description = "URL of the uploaded proof image, from POST /customer-portal/wallet/topup-requests/upload-proof")
        String proofImageUrl
) {
}
