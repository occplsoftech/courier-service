package com.courier.modules.finance.application.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Reacts to wallet events after commit. Today it logs; this is the seam where a low-balance
 * alert, a recharge receipt email and the finance dashboard's cache eviction attach.
 * Exhaustive by construction — {@link WalletEvent} is sealed.
 *
 * <p>AFTER_COMMIT, deliberately: a receipt for a credit that rolled back is worse than no
 * receipt at all.
 */
@Slf4j
@Component
public class WalletEventListener {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(WalletEvent event) {
        String detail = switch (event) {
            case WalletEvent.WalletCreated e ->
                    "created %s for branch %s".formatted(e.walletNumber(), e.branchId());
            case WalletEvent.CustomerWalletCreated e ->
                    "created %s for customer %s".formatted(e.walletNumber(), e.customerId());
            case WalletEvent.WalletCredited e ->
                    "credited %s (%s), balance %s".formatted(e.amount(), e.reason(), e.balanceAfter());
            case WalletEvent.WalletDebited e ->
                    "debited %s (%s), balance %s".formatted(e.amount(), e.reason(), e.balanceAfter());
            case WalletEvent.WalletRecharged e ->
                    "recharged %s via %s, balance %s"
                            .formatted(e.amount(), e.paymentGateway(), e.balanceAfter());
        };
        log.info("Wallet {} [{}] {}", event.walletId(), event.companyId(), detail);
    }
}
