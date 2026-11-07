package com.courier.modules.finance.application.command;

import java.math.BigDecimal;
import java.util.UUID;

/** The return-to-sender charge of a customer's portal booking, debited from their wallet (once per booking). */
public record CustomerRtoChargeCommand(UUID customerId, BigDecimal amount, UUID bookingId, String reference) {
}
