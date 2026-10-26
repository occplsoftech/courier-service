package com.courier.modules.finance.application.command;

import com.courier.modules.finance.domain.ReferenceType;
import com.courier.modules.finance.domain.SubTransactionType;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A company admin's manual debit of a customer's wallet (a correction, a charge raised outside the
 * portal). The reason defaults to {@code MDB} when null. Mirror of {@link CustomerCreditCommand}.
 */
public record CustomerDebitCommand(
        UUID customerId,
        BigDecimal amount,
        SubTransactionType subTransactionType,
        ReferenceType referenceType,
        String referenceId,
        String remarks
) {
}
