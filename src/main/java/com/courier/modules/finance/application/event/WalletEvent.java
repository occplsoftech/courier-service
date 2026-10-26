package com.courier.modules.finance.application.event;

import com.courier.modules.finance.domain.SubTransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Domain events published by the Branch Wallet module. Sealed, consumed
 * {@code @TransactionalEventListener(AFTER_COMMIT)} — nothing reacts to money that rolled
 * back. In-process, like the company events.
 *
 * <p>Sealed so that adding an event type is a compile error everywhere it is switched on,
 * rather than a case that is silently ignored.
 */
public sealed interface WalletEvent {

    UUID walletId();

    UUID companyId();

    Instant occurredAt();

    record WalletCreated(UUID walletId, UUID companyId, UUID branchId, String walletNumber,
                         Instant occurredAt) implements WalletEvent {
    }

    /** A customer's wallet was opened. Separate from {@link WalletCreated}, which is a branch's. */
    record CustomerWalletCreated(UUID walletId, UUID companyId, UUID customerId, String walletNumber,
                                 Instant occurredAt) implements WalletEvent {
    }

    /** Balance went up. {@code balanceAfter} is the available balance the entry left behind. */
    record WalletCredited(UUID walletId, UUID companyId, UUID branchId, UUID transactionId,
                          SubTransactionType reason, BigDecimal amount, BigDecimal balanceAfter,
                          Instant occurredAt) implements WalletEvent {
    }

    /** Balance went down. This is the hook a low-balance alert will hang off. */
    record WalletDebited(UUID walletId, UUID companyId, UUID branchId, UUID transactionId,
                         SubTransactionType reason, BigDecimal amount, BigDecimal balanceAfter,
                         Instant occurredAt) implements WalletEvent {
    }

    /** A gateway recharge settled. Distinct from a plain credit: a receipt is owed. */
    record WalletRecharged(UUID walletId, UUID companyId, UUID branchId, UUID transactionId,
                           String paymentGateway, String paymentReference, BigDecimal amount,
                           BigDecimal balanceAfter, Instant occurredAt) implements WalletEvent {
    }
}
