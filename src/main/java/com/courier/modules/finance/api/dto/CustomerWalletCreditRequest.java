package com.courier.modules.finance.api.dto;

import com.courier.modules.finance.domain.SubTransactionType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** A company admin credits a customer's wallet by hand. The customer is in the path. */
@Schema(name = "CustomerWalletCreditRequest", description = "Manual credit to a customer's wallet")
public record CustomerWalletCreditRequest(
        @NotNull
        @DecimalMin(value = "0.0001", message = "must be greater than zero")
        @DecimalMax(value = "10000000.0000", message = "must not exceed 10,000,000")
        @Digits(integer = 15, fraction = 4)
        @Schema(example = "2500.00") BigDecimal amount,
        @Schema(description = "Why. Defaults to MCR (manual credit). Must be a reason that can appear on a credit.",
                example = "MCR") SubTransactionType subTransactionType,
        @Size(max = 100) @Schema(description = "The document this credit answers to, if any") String referenceId,
        @Size(max = 500) @Schema(description = "Shown on the statement", example = "Bank transfer received, UTR 123456789")
        String remarks
) {
}
