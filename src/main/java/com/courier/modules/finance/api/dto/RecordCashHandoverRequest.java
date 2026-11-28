package com.courier.modules.finance.api.dto;

import com.courier.modules.finance.domain.CashHandoverMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Body of {@code POST /api/v1/branch-wallet/cash-handovers}. */
@Schema(name = "RecordCashHandoverRequest", description = "Cash a user handed over to the branch")
public record RecordCashHandoverRequest(
        @Schema(description = "The branch. Omit it for your own branch; a company admin names any branch. Another branch than your own is 404.")
        UUID branchId,
        @NotNull @Schema(description = "The user who handed the cash over") UUID userId,
        @NotNull
        @DecimalMin(value = "0.01", message = "must be greater than zero")
        @DecimalMax(value = "10000000.00", message = "must not exceed 10,000,000")
        @Digits(integer = 10, fraction = 2)
        @Schema(example = "12600.00") BigDecimal amount,
        @NotNull @Schema(example = "CASH") CashHandoverMode mode,
        @Size(max = 100) @Schema(description = "Deposit slip number or UPI UTR") String reference,
        @Size(max = 500) String remarks,
        @Schema(description = "Day the cash changed hands (IST); today when omitted") LocalDate handoverDate
) {
}
