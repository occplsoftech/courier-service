package com.courier.modules.finance.application.command;

import java.math.BigDecimal;
import java.util.UUID;

/** Step one of an online recharge of a customer's wallet: who, and how much to ask the gateway for. */
public record CustomerRechargeOrderCommand(UUID customerId, BigDecimal amount) {
}
