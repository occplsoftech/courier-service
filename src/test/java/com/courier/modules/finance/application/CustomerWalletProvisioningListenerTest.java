package com.courier.modules.finance.application;

import com.courier.modules.customer.application.event.CustomerEvent;
import com.courier.modules.customer.domain.CustomerType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Which new customers get a wallet by default: business customers only. */
class CustomerWalletProvisioningListenerTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();

    private final CustomerWalletService wallets = mock(CustomerWalletService.class);
    private final CustomerWalletProvisioningListener listener = new CustomerWalletProvisioningListener(wallets);

    private static CustomerEvent.CustomerCreated created(CustomerType type) {
        return new CustomerEvent.CustomerCreated(CUSTOMER, COMPANY, "CUST0001", type, Instant.now());
    }

    @Test
    @DisplayName("a new business customer gets a wallet")
    void businessGetsWallet() {
        listener.on(created(CustomerType.BUSINESS));

        verify(wallets).provisionForCustomer(COMPANY, CUSTOMER);
    }

    @Test
    @DisplayName("a new individual customer does not")
    void individualDoesNot() {
        listener.on(created(CustomerType.INDIVIDUAL));

        verify(wallets, never()).provisionForCustomer(any(), any());
    }

    @Test
    @DisplayName("a wallet failure never fails the customer that was already saved")
    void failureIsSwallowed() {
        when(wallets.provisionForCustomer(COMPANY, CUSTOMER)).thenThrow(new IllegalStateException("boom"));

        assertThatCode(() -> listener.on(created(CustomerType.BUSINESS))).doesNotThrowAnyException();
    }
}
