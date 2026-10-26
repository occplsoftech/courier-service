package com.courier.modules.finance.api.dto;

import com.courier.modules.finance.domain.SubTransactionType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** A company admin debits a customer's wallet by hand. The customer is in the path. */
@Schema(name = "CustomerWalletDebitRequest", description = "Manual debit of a customer's wallet")
public record CustomerWalletDebitRequest(
        @NotNull
        @DecimalMin(value = "0.0001", message = "must be greater than zero")
        @DecimalMax(value = "10000000.0000", message = "must not exceed 10,000,000")
        @Digits(integer = 15, fraction = 4)
        @Schema(example = "250.00") BigDecimal amount,
        @Schema(description = "Why. Defaults to MDB (manual debit). Must be a reason that can appear on a debit.",
                example = "MDB") SubTransactionType subTransactionType,
        @Size(max = 100) @Schema(description = "The document this debit answers to, if any") String referenceId,
        @Size(max = 500) @Schema(description = "Shown on the statement", example = "Duplicate recharge reversed")
        String remarks
) {
}
