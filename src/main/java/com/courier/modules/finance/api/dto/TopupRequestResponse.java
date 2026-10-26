package com.courier.modules.finance.api.dto;

import com.courier.modules.finance.domain.TopupRequestStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Flat, like {@code WalletTransactionResponse} — ids only, no name resolution here; the
 *  frontend already carries a branch/user directory for labels. */
@Schema(name = "TopupRequestResponse", description = "A branch's wallet top-up request")
public record TopupRequestResponse(
        UUID id,
        UUID companyId,
        UUID walletId,
        UUID branchId,
        @io.swagger.v3.oas.annotations.media.Schema(description = "Set instead of branchId when the request is a customer's")
        UUID customerId,

        BigDecimal requestedAmount,
        String remarks,
        String proofImageUrl,
        TopupRequestStatus status,

        UUID requestedBy,
        Instant createdAt,

        UUID decidedBy,
        Instant decidedAt,
        String decisionRemarks,
        UUID transactionId,

        Long version
) {
}
