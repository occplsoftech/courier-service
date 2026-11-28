package com.courier.modules.finance.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Schema(name = "CashHandoverResponse", description = "Cash a user handed over to the branch")
public record CashHandoverResponse(
        UUID id, UUID branchId, UUID userId, String userName,
        BigDecimal amount, String mode, String reference, String remarks, LocalDate handoverDate,
        UUID receivedBy, String receivedByName, Instant receivedAt,
        boolean voided, UUID voidedBy, String voidedByName, Instant voidedAt, String voidReason
) {
}
