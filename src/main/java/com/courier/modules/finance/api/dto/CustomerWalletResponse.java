package com.courier.modules.finance.api.dto;

import com.courier.modules.finance.domain.Wallet;
import com.courier.modules.finance.domain.WalletStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A customer's wallet. Read-only: no field here can be written back — the balance moves only
 * through transactions (later parts of the feature).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "CustomerWalletResponse", description = "A business customer's prepaid wallet")
public record CustomerWalletResponse(
        UUID id,
        UUID customerId,
        String walletNumber,
        WalletStatus status,
        @Schema(description = "Spendable now") BigDecimal availableBalance,
        @Schema(description = "Reserved against in-flight work; not spendable") BigDecimal holdBalance,
        @Schema(description = "available + hold") BigDecimal totalBalance,
        @Schema(example = "INR") String currency,
        Instant createdAt
) {

    public static CustomerWalletResponse of(Wallet w) {
        return new CustomerWalletResponse(w.getId(), w.getCustomerId(), w.getWalletNumber(), w.getStatus(),
                w.getAvailableBalance(), w.getHoldBalance(), w.getTotalBalance(), w.getCurrency(),
                w.getCreatedAt());
    }
}
