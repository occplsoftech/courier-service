package com.courier.modules.finance.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Schema(name = "CashHandoverResponse", description = "Cash a user hands over, with proof, and the receiver's decision")
public record CashHandoverResponse(
        UUID id, UUID branchId, UUID userId, String userName,
        BigDecimal amount, String mode, String reference, String remarks, LocalDate handoverDate,
        String proofImageUrl, Instant submittedAt,
        @Schema(description = "PENDING, APPROVED, REJECTED or VOIDED") String status,
        UUID decidedBy, String decidedByName, Instant decidedAt, String decisionRemarks,
        UUID voidedBy, String voidedByName, Instant voidedAt, String voidReason
) {
}
