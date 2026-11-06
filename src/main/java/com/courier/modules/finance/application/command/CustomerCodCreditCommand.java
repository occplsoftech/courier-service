package com.courier.modules.finance.application.command;

import java.math.BigDecimal;
import java.util.UUID;

/** The COD amount collected for a customer's shipment, paid into their wallet (once per shipment). */
public record CustomerCodCreditCommand(UUID customerId, BigDecimal amount, UUID shipmentId, String shipmentNumber) {
}
