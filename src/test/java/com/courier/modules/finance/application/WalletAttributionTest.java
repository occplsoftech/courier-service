package com.courier.modules.finance.application;

import com.courier.modules.finance.application.UserPerformance.Attribution;
import com.courier.modules.finance.application.WalletAttribution.Bucket;
import com.courier.modules.finance.application.WalletAttribution.Share;
import com.courier.modules.finance.domain.BranchUserActivityPort.ShipmentOwners;
import com.courier.modules.finance.domain.ReferenceType;
import com.courier.modules.finance.domain.SubTransactionType;
import com.courier.modules.finance.domain.TransactionType;
import com.courier.modules.finance.domain.WalletTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WalletAttributionTest {

    private static final UUID BRANCH = UUID.randomUUID();
    private static final UUID OTHER_BRANCH = UUID.randomUUID();
    private static final UUID BOOKER = UUID.randomUUID();
    private static final UUID DRIVER = UUID.randomUUID();
    private static final UUID CLERK = UUID.randomUUID();
    private static final Map<String, ShipmentOwners> OWNERS = Map.of(
            "SHP1", new ShipmentOwners("SHP1", BOOKER, BRANCH, DRIVER, OTHER_BRANCH),
            "SHP2", new ShipmentOwners("SHP2", BOOKER, OTHER_BRANCH, null, BRANCH));

    private static WalletTransaction entry(TransactionType type, SubTransactionType sub, String shipment) {
        WalletTransaction t = WalletTransaction.builder()
                .transactionType(type).subTransactionType(sub).amount(new BigDecimal("100.0000"))
                .referenceType(shipment == null ? ReferenceType.MANUAL : ReferenceType.SHIPMENT)
                .referenceId(shipment).build();
        t.setCreatedBy(CLERK);
        return t;
    }

    private static Share work(WalletTransaction t) {
        return WalletAttribution.of(t, BRANCH, OWNERS, Attribution.WORK);
    }

    @Test
    @DisplayName("a booking debit and its refund go to whoever booked the shipment")
    void bookingDebitAndRefundToBooker() {
        assertThat(work(entry(TransactionType.DR, SubTransactionType.SBK, "SHP1")))
                .isEqualTo(new Share(BOOKER, Bucket.DEBIT, new BigDecimal("100.0000")));
        assertThat(work(entry(TransactionType.CR, SubTransactionType.SRF, "SHP1")))
                .isEqualTo(new Share(BOOKER, Bucket.REFUND, new BigDecimal("100.0000")));
    }

    @Test
    @DisplayName("a COD / To-Pay debit goes to the delivery boy on the DRS, else to whoever entered it")
    void codToDriver() {
        assertThat(work(entry(TransactionType.DR, SubTransactionType.COD, "SHP1")).userId()).isEqualTo(DRIVER);
        assertThat(work(entry(TransactionType.DR, SubTransactionType.TPY, "SHP1")).userId()).isEqualTo(DRIVER);
        assertThat(work(entry(TransactionType.DR, SubTransactionType.COD, "SHP2")).userId()).isEqualTo(CLERK);
    }

    @Test
    @DisplayName("commission goes to the booker on the booking branch's wallet, to the delivery boy elsewhere; a reversal is negative")
    void commission() {
        Share booking = work(entry(TransactionType.CR, SubTransactionType.COM, "SHP1"));
        assertThat(booking.userId()).isEqualTo(BOOKER);
        assertThat(booking.bucket()).isEqualTo(Bucket.COMMISSION);
        Share reversal = WalletAttribution.of(entry(TransactionType.DR, SubTransactionType.COM, "SHP1"), OTHER_BRANCH,
                OWNERS, Attribution.WORK);
        assertThat(reversal.userId()).isEqualTo(DRIVER);
        assertThat(reversal.amount()).isEqualByComparingTo("-100");
        assertThat(work(entry(TransactionType.CR, SubTransactionType.DRS, "SHP1")).userId()).isEqualTo(DRIVER);
    }

    @Test
    @DisplayName("manual debits belong to the branch, not to a user; recharges are no user's figure")
    void manualIsUnattributed() {
        assertThat(work(entry(TransactionType.DR, SubTransactionType.MDB, null)))
                .isEqualTo(new Share(null, Bucket.DEBIT, new BigDecimal("100.0000")));
        assertThat(work(entry(TransactionType.DR, SubTransactionType.PNL, "SHP1")).userId()).isNull();
        assertThat(work(entry(TransactionType.CR, SubTransactionType.WRC, null)).bucket()).isEqualTo(Bucket.NONE);
    }

    @Test
    @DisplayName("a shipment entry whose shipment is unknown is unattributed")
    void unknownShipment() {
        assertThat(work(entry(TransactionType.DR, SubTransactionType.SBK, "NOPE")).userId()).isNull();
    }

    @Test
    @DisplayName("entered-by puts every entry under whoever was signed in")
    void enteredBy() {
        WalletTransaction booking = entry(TransactionType.DR, SubTransactionType.SBK, "SHP1");
        WalletTransaction manual = entry(TransactionType.DR, SubTransactionType.MDB, null);
        assertThat(WalletAttribution.of(booking, BRANCH, OWNERS, Attribution.ENTERED_BY).userId()).isEqualTo(CLERK);
        assertThat(WalletAttribution.of(manual, BRANCH, OWNERS, Attribution.ENTERED_BY).userId()).isEqualTo(CLERK);
    }
}
