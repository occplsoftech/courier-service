package com.courier.modules.finance.domain;

import com.courier.shared.exception.BusinessRuleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A customer wallet has the owner shape the database CHECK requires and the same money rules. */
class CustomerWalletTest {

    @Test
    @DisplayName("a customer wallet belongs to the customer and to no branch")
    void ownerShape() {
        UUID customer = UUID.randomUUID();

        Wallet w = Wallet.forCustomer(customer, "WLT2610ABCDEFGH");

        assertThat(w.getOwnerType()).isEqualTo(WalletOwnerType.CUSTOMER);
        assertThat(w.getCustomerId()).isEqualTo(customer);
        assertThat(w.getBranchId()).isNull();
        assertThat(w.isCustomerWallet()).isTrue();
    }

    @Test
    @DisplayName("it starts empty and active, in the default currency")
    void startsEmptyAndActive() {
        Wallet w = Wallet.forCustomer(UUID.randomUUID(), "WLT2610ABCDEFGH");

        assertThat(w.getStatus()).isEqualTo(WalletStatus.ACTIVE);
        assertThat(w.getAvailableBalance()).isEqualByComparingTo("0");
        assertThat(w.getHoldBalance()).isEqualByComparingTo("0");
        assertThat(w.getCurrency()).isEqualTo(Wallet.DEFAULT_CURRENCY);
    }

    @Test
    @DisplayName("a customer wallet cannot be built without a customer")
    void needsCustomer() {
        assertThatThrownBy(() -> Wallet.forCustomer(null, "WLT2610ABCDEFGH"))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("a wallet built the original way is still a branch wallet")
    void branchWalletUnchanged() {
        UUID branch = UUID.randomUUID();

        Wallet w = Wallet.builder().walletNumber("WLT2610ABCDEFGH").branchId(branch).build();

        assertThat(w.getOwnerType()).isEqualTo(WalletOwnerType.BRANCH);
        assertThat(w.getBranchId()).isEqualTo(branch);
        assertThat(w.getCustomerId()).isNull();
        assertThat(w.isCustomerWallet()).isFalse();
    }

    @Test
    @DisplayName("the same prepaid rules apply: credit, then no overdraft")
    void sameMoneyRules() {
        Wallet w = Wallet.forCustomer(UUID.randomUUID(), "WLT2610ABCDEFGH");

        w.applyCredit(new BigDecimal("500"));
        w.applyDebit(new BigDecimal("200"));

        assertThat(w.getAvailableBalance()).isEqualByComparingTo("300");
        assertThatThrownBy(() -> w.applyDebit(new BigDecimal("300.01")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Insufficient wallet balance");
        assertThat(w.getAvailableBalance()).isEqualByComparingTo("300");
    }
}
