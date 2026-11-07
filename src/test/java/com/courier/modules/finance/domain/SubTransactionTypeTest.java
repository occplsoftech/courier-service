package com.courier.modules.finance.domain;

import com.courier.shared.exception.BusinessRuleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The reason codes and the direction each may appear in. These are the guard rails that stop
 * a booking charge being filed as a credit — a mistake that reconciles to nothing.
 */
class SubTransactionTypeTest {

    @Test
    @DisplayName("credit-only reasons refuse a debit, and vice versa")
    void directionIsEnforced() {
        assertThat(SubTransactionType.WRC.supports(TransactionType.CR)).isTrue();
        assertThat(SubTransactionType.WRC.supports(TransactionType.DR)).isFalse();

        assertThat(SubTransactionType.SBK.supports(TransactionType.DR)).isTrue();
        assertThat(SubTransactionType.SBK.supports(TransactionType.CR)).isFalse();
    }

    @Test
    @DisplayName("settlement-style reasons work in both directions")
    void bothDirections() {
        for (SubTransactionType both : new SubTransactionType[]{
                SubTransactionType.COD, SubTransactionType.COM,
                SubTransactionType.BST, SubTransactionType.ADJ}) {
            assertThat(both.supports(TransactionType.CR)).as(both.name()).isTrue();
            assertThat(both.supports(TransactionType.DR)).as(both.name()).isTrue();
        }
    }

    @Test
    @DisplayName("requireSupports throws a 422-mapped error naming the code")
    void requireSupports() {
        assertThatThrownBy(() -> SubTransactionType.SBK.requireSupports(TransactionType.CR))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("SBK")
                .hasMessageContaining("Shipment Booking");

        assertThatCode(() -> SubTransactionType.MCR.requireSupports(TransactionType.CR))
                .doesNotThrowAnyException();
        assertThatCode(() -> SubTransactionType.MDB.requireSupports(TransactionType.DR))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the creditable and debitable lists are exactly what the enum declares")
    void lists() {
        assertThat(SubTransactionType.creditable())
                .containsExactlyInAnyOrder(SubTransactionType.WRC, SubTransactionType.SRF,
                        SubTransactionType.COD, SubTransactionType.COM, SubTransactionType.BST,
                        SubTransactionType.MCR, SubTransactionType.TRI, SubTransactionType.ADJ,
                        SubTransactionType.DRS, SubTransactionType.DWC);

        assertThat(SubTransactionType.debitable())
                .containsExactlyInAnyOrder(SubTransactionType.SBK, SubTransactionType.COD,
                        SubTransactionType.TPY, SubTransactionType.COM, SubTransactionType.BST,
                        SubTransactionType.MDB, SubTransactionType.TRO, SubTransactionType.ADJ,
                        SubTransactionType.PNL, SubTransactionType.RTO);
    }

    @Test
    @DisplayName("all sixteen codes exist and every one is labelled")
    void catalogueIsComplete() {
        assertThat(SubTransactionType.values()).hasSize(16);
        for (SubTransactionType type : SubTransactionType.values()) {
            assertThat(type.getLabel()).as(type.name()).isNotBlank();
            assertThat(type.name()).as("code is three letters").hasSize(3);
        }
    }
}
