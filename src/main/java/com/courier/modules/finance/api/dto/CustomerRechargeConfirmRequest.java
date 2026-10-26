package com.courier.modules.finance.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Step two: what the gateway checkout returned. The credited amount is fetched from the gateway, not read from here. */
@Schema(name = "CustomerRechargeConfirmRequest", description = "Settle the payment that checkout produced")
public record CustomerRechargeConfirmRequest(
        @NotBlank @Size(max = 100) @Schema(example = "order_Qk1a2B3c4D5e6F") String gatewayOrderId,
        @NotBlank @Size(max = 100)
        @Schema(description = "Also the idempotency key: settling the same payment twice credits once.",
                example = "pay_Qk1a2B3c4D5e6F") String paymentReference,
        @NotBlank @Size(max = 512) String signature
) {
}
