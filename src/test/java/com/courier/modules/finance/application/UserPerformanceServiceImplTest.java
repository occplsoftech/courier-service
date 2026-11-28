package com.courier.modules.finance.application;

import com.courier.modules.company.domain.DefaultRoleCatalog;
import com.courier.modules.finance.application.UserPerformance.Attribution;
import com.courier.modules.finance.application.UserPerformance.DetailRow;
import com.courier.modules.finance.application.UserPerformance.Metric;
import com.courier.modules.finance.application.UserPerformance.Report;
import com.courier.modules.finance.application.UserPerformance.Row;
import com.courier.modules.finance.domain.BranchDirectoryPort;
import com.courier.modules.finance.domain.BranchUserActivityPort;
import com.courier.modules.finance.domain.BranchUserActivityPort.Booking;
import com.courier.modules.finance.domain.BranchUserActivityPort.Delivery;
import com.courier.modules.finance.domain.BranchUserActivityPort.PaymentKind;
import com.courier.modules.finance.domain.BranchUserActivityPort.ShipmentOwners;
import com.courier.modules.finance.domain.CashHandover;
import com.courier.modules.finance.domain.CashHandoverMode;
import com.courier.modules.finance.domain.CashHandoverRepository;
import com.courier.modules.finance.domain.ReferenceType;
import com.courier.modules.finance.domain.SubTransactionType;
import com.courier.modules.finance.domain.TransactionType;
import com.courier.modules.finance.domain.WalletTransaction;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.security.AuthenticatedUser;
import com.courier.shared.security.Roles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Window 27–28 Nov 2026, cash counted from 20 Nov. Ravi books at the counter, Suresh delivers.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserPerformanceServiceImplTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID BRANCH = UUID.randomUUID();
    private static final UUID MANAGER = UUID.randomUUID();
    private static final UUID RAVI = UUID.randomUUID();
    private static final UUID SURESH = UUID.randomUUID();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-28T12:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate D25 = LocalDate.of(2026, 11, 25);
    private static final LocalDate D26 = LocalDate.of(2026, 11, 26);
    private static final LocalDate D27 = LocalDate.of(2026, 11, 27);
    private static final LocalDate D28 = LocalDate.of(2026, 11, 28);

    @Mock private BranchUserActivityPort activity;
    @Mock private WalletService walletService;
    @Mock private CashHandoverRepository handovers;
    @Mock private BranchDirectoryPort branchDirectory;

    private UserPerformanceServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UserPerformanceServiceImpl(activity, walletService, handovers,
                new FinanceBranchScope(branchDirectory), CLOCK);
        CompanyContext.setCompanyId(COMPANY);
        for (UUID u : List.of(MANAGER, RAVI, SURESH)) {
            when(branchDirectory.branchOfUser(u, COMPANY)).thenReturn(Optional.of(BRANCH));
        }
        Map<UUID, String> names = Map.of(MANAGER, "Meera", RAVI, "Ravi", SURESH, "Suresh");
        when(branchDirectory.findUser(any(), eq(COMPANY))).thenAnswer(i -> Optional.ofNullable(names.get(i.getArgument(0)))
                .map(n -> new BranchDirectoryPort.UserRef(i.getArgument(0), COMPANY, n)));
        when(handovers.findTrackingFrom(any())).thenReturn(List.of(java.sql.Date.valueOf("2026-11-20")));

        when(activity.bookings(eq(COMPANY), eq(BRANCH), eq(LocalDate.of(2026, 11, 20)), eq(D28))).thenReturn(List.of(
                booking("B0", D25, RAVI, PaymentKind.PAID, "1000", "CASH", true, false),
                booking("B1", D27, RAVI, PaymentKind.PAID, "500", "CASH", true, false),
                booking("B2", D27, RAVI, PaymentKind.PAID, "300", "UPI_QR", true, false),
                booking("B3", D28, RAVI, PaymentKind.PAID, "200", "CASH", true, true),
                booking("B4", D28, RAVI, PaymentKind.TO_PAY, "400", null, false, false),
                booking("P1", D28, null, PaymentKind.CREDIT, "700", null, false, false)));
        when(activity.deliveries(eq(COMPANY), eq(BRANCH), any(), any())).thenReturn(List.of(
                new Delivery(UUID.randomUUID(), "D0", SURESH, "DRS1", ist(D26, 9), ist(D26, 15), 0, "To Pay",
                        PaymentKind.TO_PAY, new BigDecimal("250")),
                new Delivery(UUID.randomUUID(), "D1", SURESH, "DRS2", ist(D28, 9), ist(D28, 14), 1, "COD",
                        PaymentKind.COD, new BigDecimal("900")),
                new Delivery(UUID.randomUUID(), "D2", SURESH, "DRS2", ist(D28, 9), null, 0, "To Pay",
                        PaymentKind.TO_PAY, new BigDecimal("400"))));

        CashHandover voided = handover(SURESH, D28, "100");
        voided.voidWith(MANAGER, Instant.now(CLOCK), "typo");
        when(handovers.findForBranchBetween(COMPANY, BRANCH, LocalDate.of(2026, 11, 20), D28)).thenReturn(List.of(
                handover(RAVI, D26, "600"), handover(RAVI, D28, "500"), voided));

        List<WalletTransaction> entries = new ArrayList<>(List.of(
                entry(TransactionType.DR, SubTransactionType.SBK, "B1", "500", RAVI),
                entry(TransactionType.DR, SubTransactionType.SBK, "B2", "300", RAVI),
                entry(TransactionType.CR, SubTransactionType.SRF, "B3", "200", MANAGER),
                entry(TransactionType.DR, SubTransactionType.COD, "D1", "900", MANAGER),
                entry(TransactionType.DR, SubTransactionType.MDB, null, "50", MANAGER),
                entry(TransactionType.CR, SubTransactionType.WRC, null, "5000", MANAGER)));
        when(walletService.searchTransactions(eq(BRANCH), any(), any())).thenReturn(new PageImpl<>(entries));
        Map<String, ShipmentOwners> owners = new HashMap<>();
        for (String n : List.of("B1", "B2", "B3")) owners.put(n, new ShipmentOwners(n, RAVI, BRANCH, null, null));
        owners.put("D1", new ShipmentOwners("D1", UUID.randomUUID(), UUID.randomUUID(), SURESH, BRANCH));
        when(activity.ownersOf(eq(COMPANY), anyCollection())).thenReturn(owners);
    }

    @AfterEach
    void tearDown() {
        CompanyContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a manager gets a row per user with bookings, cash, deliveries, wallet debits and outstanding cash")
    void managerView() {
        signedInAs(MANAGER, Roles.BRANCH_MANAGER);
        Report r = service.report(null, D27, D28, null);

        assertThat(r.teamView()).isTrue();
        assertThat(r.canRecordHandover()).isTrue();
        assertThat(r.attribution()).isEqualTo(Attribution.WORK);
        assertThat(r.trackingFrom()).isEqualTo(LocalDate.of(2026, 11, 20));

        Row ravi = row(r, RAVI);
        assertThat(ravi.userName()).isEqualTo("Ravi");
        assertThat(ravi.booked()).isEqualTo(4);
        assertThat(ravi.cancelled()).isEqualTo(1);
        assertThat(ravi.bookingValue()).isEqualByComparingTo("1200");
        assertThat(ravi.paidCount()).isEqualTo(2);
        assertThat(ravi.toPayCount()).isEqualTo(1);
        assertThat(ravi.counterCash()).as("a cancelled booking's cash is not counted").isEqualByComparingTo("500");
        assertThat(ravi.counterOnline()).isEqualByComparingTo("300");
        assertThat(ravi.walletDebit()).isEqualByComparingTo("800");
        assertThat(ravi.walletRefund()).isEqualByComparingTo("200");
        assertThat(ravi.walletNet()).isEqualByComparingTo("600");
        assertThat(ravi.cashCollected()).isEqualByComparingTo("500");
        assertThat(ravi.openingOutstanding()).as("1000 on the 25th, 600 handed over on the 26th").isEqualByComparingTo("400");
        assertThat(ravi.handedOver()).isEqualByComparingTo("500");
        assertThat(ravi.closingOutstanding()).isEqualByComparingTo("400");

        Row suresh = row(r, SURESH);
        assertThat(suresh.delivered()).isEqualTo(1);
        assertThat(suresh.deliveryPending()).isEqualTo(1);
        assertThat(suresh.failedAttempts()).isEqualTo(1);
        assertThat(suresh.deliveryCash()).isEqualByComparingTo("900");
        assertThat(suresh.successRate()).isEqualByComparingTo("50.0");
        assertThat(suresh.walletDebit()).as("the COD debit goes to the delivery boy, not to who entered it")
                .isEqualByComparingTo("900");
        assertThat(suresh.handedOver()).as("a voided handover does not count").isEqualByComparingTo("0");
        assertThat(suresh.openingOutstanding()).isEqualByComparingTo("250");
        assertThat(suresh.closingOutstanding()).isEqualByComparingTo("1150");

        Row portal = r.rows().stream().filter(Row::system).findFirst().orElseThrow();
        assertThat(portal.booked()).isEqualTo(1);
        assertThat(portal.creditCount()).isEqualTo(1);
        assertThat(r.rows().get(r.rows().size() - 1).system()).as("the system row comes last").isTrue();
        assertThat(r.rows()).extracting(Row::userId).doesNotContain(MANAGER);

        assertThat(r.total().userName()).isEqualTo("Branch total");
        assertThat(r.total().cashCollected()).isEqualByComparingTo("1400");
        assertThat(r.total().closingOutstanding()).isEqualByComparingTo("1550");

        assertThat(r.reconciliation().walletDebits()).isEqualByComparingTo("1750");
        assertThat(r.reconciliation().walletCredits()).isEqualByComparingTo("5200");
        assertThat(r.reconciliation().attributedDebits()).isEqualByComparingTo("1700");
        assertThat(r.reconciliation().unattributedDebits()).isEqualByComparingTo("50");
        assertThat(r.reconciliation().attributedDebits().add(r.reconciliation().unattributedDebits()))
                .as("every debit is somewhere").isEqualByComparingTo(r.reconciliation().walletDebits());
    }

    @Test
    @DisplayName("entered-by puts the COD debit under whoever entered it")
    void enteredBy() {
        signedInAs(MANAGER, Roles.BRANCH_MANAGER);
        Report r = service.report(null, D27, D28, Attribution.ENTERED_BY);
        assertThat(row(r, MANAGER).walletDebit()).isEqualByComparingTo("950");
        assertThat(r.reconciliation().unattributedDebits()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a booking operator sees only their own row and no branch reconciliation")
    void ownRowOnly() {
        signedInAs(RAVI, DefaultRoleCatalog.BOOKING_OPERATOR);
        Report r = service.report(null, D27, D28, null);
        assertThat(r.teamView()).isFalse();
        assertThat(r.canRecordHandover()).isFalse();
        assertThat(r.rows()).extracting(Row::userId).containsExactly(RAVI);
        assertThat(r.total().cashCollected()).isEqualByComparingTo("500");
        assertThat(r.reconciliation()).isNull();
    }

    @Test
    @DisplayName("drill-downs list what is behind each figure; an operator cannot read someone else's")
    void details() {
        signedInAs(MANAGER, Roles.BRANCH_MANAGER);
        List<DetailRow> counter = service.details(null, D27, D28, RAVI, Metric.COUNTER, null);
        assertThat(counter).extracting(DetailRow::reference).containsExactlyInAnyOrder("B1", "B2", "B3");
        assertThat(counter).filteredOn(d -> d.reference().equals("B3")).extracting(DetailRow::status)
                .containsExactly("CANCELLED");
        assertThat(service.details(null, D27, D28, SURESH, Metric.DELIVERIES, null))
                .extracting(DetailRow::reference).containsExactlyInAnyOrder("D1", "D2");
        assertThat(service.details(null, D27, D28, null, Metric.WALLET, null))
                .extracting(DetailRow::detail).singleElement().asString().startsWith("Manual Debit");
        assertThat(service.details(null, D27, D28, SURESH, Metric.HANDOVERS, null))
                .extracting(DetailRow::status).containsExactly("VOIDED");
        assertThat(service.details(null, D27, D28, null, Metric.BOOKINGS, null))
                .extracting(DetailRow::reference).containsExactly("P1");

        signedInAs(RAVI, DefaultRoleCatalog.BOOKING_OPERATOR);
        assertThat(service.details(null, D27, D28, SURESH, Metric.DELIVERIES, null))
                .as("an operator's userId is always their own").isEmpty();
    }

    // ------------------------------------------------------------------------------------------------ fixtures

    private void signedInAs(UUID userId, String... roles) {
        AuthenticatedUser principal = new AuthenticatedUser(userId, COMPANY, "u@test", Set.of(roles), "jti");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.authorities()));
    }

    private static Row row(Report r, UUID userId) {
        return r.rows().stream().filter(x -> userId.equals(x.userId())).findFirst().orElseThrow();
    }

    private static Instant ist(LocalDate day, int hour) {
        return day.atTime(hour, 0).atZone(CashHandoverServiceImpl.IST).toInstant();
    }

    private static Booking booking(String number, LocalDate day, UUID by, PaymentKind kind, String amount,
                                   String method, boolean paid, boolean cancelled) {
        BigDecimal a = new BigDecimal(amount);
        return new Booking(UUID.randomUUID(), number, day, ist(day, 10), by, cancelled ? "CANCELLED" : "BOOKED",
                cancelled, kind.name(), kind, a, BigDecimal.ONE, method, paid, paid ? a : BigDecimal.ZERO);
    }

    private static CashHandover handover(UUID user, LocalDate day, String amount) {
        return CashHandover.builder().branchId(BRANCH).userId(user).amount(new BigDecimal(amount))
                .mode(CashHandoverMode.CASH).handoverDate(day).receivedBy(MANAGER).receivedAt(ist(day, 19)).build();
    }

    private static WalletTransaction entry(TransactionType type, SubTransactionType sub, String shipment, String amount,
                                           UUID createdBy) {
        WalletTransaction t = WalletTransaction.builder().transactionNo("TXN" + sub + shipment)
                .transactionType(type).subTransactionType(sub).amount(new BigDecimal(amount))
                .referenceType(shipment == null ? ReferenceType.MANUAL : ReferenceType.SHIPMENT)
                .referenceId(shipment).build();
        t.setCreatedBy(createdBy);
        t.setCreatedAt(ist(D28, 11));
        return t;
    }
}
