package com.courier.modules.finance.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(name = "RechargeAttemptResponse", description = "One recharge attempt: PENDING, SUCCESS or FAILED. bankSettlementStatus is SETTLED / AWAITING for a credited online recharge, null otherwise")
public record RechargeAttemptResponse(
        UUID id,
        String transactionNo,
        UUID branchId,
        @io.swagger.v3.oas.annotations.media.Schema(description = "Set instead of branchId when the recharge is a customer's")
        UUID customerId,
        String branchCode,
        String branchName,
        BigDecimal amount,
        String currency,
        String mode,
        String gateway,
        String gatewayOrderId,
        String gatewayPaymentId,
        String status,
        String failureReason,
        String remarks,
        String recordedByName,
        UUID ledgerTransactionId,
        Instant lastCheckedAt,
        Instant settledAt,
        String bankSettlementStatus,
        String bankSettlementId,
        String bankSettlementUtr,
        Instant bankSettledAt,
        Instant settlementLastCheckedAt,
        Instant createdAt
) {
}
