package com.courier.modules.finance.api.dto;

import com.courier.modules.finance.domain.CashHandoverMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Body of {@code POST /api/v1/branch-wallet/cash-handovers}: the signed-in user hands over their cash. */
@Schema(name = "SubmitCashHandoverRequest", description = "Cash you hand over, with proof, for approval")
public record SubmitCashHandoverRequest(
        @Schema(description = "The branch. Omit it for your own branch; a company admin names any branch.")
        UUID branchId,
        @NotNull
        @DecimalMin(value = "0.01", message = "must be greater than zero")
        @DecimalMax(value = "10000000.00", message = "must not exceed 10,000,000")
        @Digits(integer = 10, fraction = 2)
        @Schema(example = "12600.00") BigDecimal amount,
        @NotNull @Schema(example = "CASH") CashHandoverMode mode,
        @Size(max = 100) @Schema(description = "Deposit slip number or UPI UTR") String reference,
        @Size(max = 500) String remarks,
        @Schema(description = "Day the cash changed hands / was deposited (IST); today when omitted") LocalDate handoverDate,
        @NotBlank(message = "Upload a proof image first")
        @Size(max = 500)
        @Schema(description = "URL from POST .../cash-handovers/upload-proof") String proofImageUrl
) {
}
