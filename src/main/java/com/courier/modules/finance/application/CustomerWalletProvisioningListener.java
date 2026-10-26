package com.courier.modules.finance.application;

import com.courier.modules.customer.application.event.CustomerEvent;
import com.courier.modules.customer.domain.CustomerType;
import com.courier.shared.company.CompanyContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Gives every new <em>business</em> customer its wallet.
 *
 * <p>"A client gets a wallet when it is created" is a Finance rule, so it lives in Finance;
 * the customer module publishes {@code CustomerCreated} and knows nothing about wallets
 * (the same split as {@link WalletProvisioningListener} for branches).
 *
 * <p>Business customers only: they are the e-commerce portal's clients. An individual
 * customer is a contact record (and most are created automatically as the sender or receiver
 * of a booking); a wallet for each would be thousands of accounts nobody uses. Any customer
 * can still get one on first access ({@link CustomerWalletService#getForCustomer}), so this
 * rule decides only who gets one <em>by default</em>.
 *
 * <p>AFTER_COMMIT and {@code REQUIRES_NEW}: the customer is already durable when this runs,
 * so a wallet failure cannot roll it back. That window is covered by the on-first-access
 * fallback, which is also what gives customers created before wallets existed a wallet.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomerWalletProvisioningListener {

    private final CustomerWalletService customerWallets;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(CustomerEvent.CustomerCreated event) {
        if (event.customerType() != CustomerType.BUSINESS) {
            return;
        }
        try {
            CompanyContext.runAs(event.companyId(),
                    () -> customerWallets.provisionForCustomer(event.companyId(), event.customerId()));
        } catch (RuntimeException e) {
            // Never fail the request that created the customer. It is committed; the wallet is
            // opened on first access.
            log.error("Could not open a wallet for customer {} ({}); it will be opened on first access",
                    event.customerCode(), event.customerId(), e);
        }
    }
}
