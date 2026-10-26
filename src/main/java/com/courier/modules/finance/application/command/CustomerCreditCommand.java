package com.courier.modules.finance.application.command;

import com.courier.modules.finance.domain.ReferenceType;
import com.courier.modules.finance.domain.SubTransactionType;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A company admin's manual credit to a customer's wallet. The reason defaults to {@code MCR}
 * when null. Carried as a command, like the branch {@link CreditCommand}, so the wallet
 * service never takes a bare amount.
 */
public record CustomerCreditCommand(
        UUID customerId,
        BigDecimal amount,
        SubTransactionType subTransactionType,
        ReferenceType referenceType,
        String referenceId,
        String remarks
) {
}
