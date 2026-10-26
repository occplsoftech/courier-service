package com.courier.modules.finance.application;

import com.courier.modules.finance.application.command.CustomerBookingDebitCommand;
import com.courier.modules.finance.application.command.CustomerBookingRefundCommand;
import com.courier.modules.finance.application.command.CustomerDebitCommand;
import com.courier.modules.finance.application.event.WalletEvent;
import com.courier.modules.finance.domain.BranchDirectoryPort;
import com.courier.modules.finance.domain.PendingCommissionPort;
import com.courier.modules.finance.domain.RechargeOrderRepository;
import com.courier.modules.finance.domain.ReferenceType;
import com.courier.modules.finance.domain.SubTransactionType;
import com.courier.modules.finance.domain.TransactionType;
import com.courier.modules.finance.domain.Wallet;
import com.courier.modules.finance.domain.WalletRepository;
import com.courier.modules.finance.domain.WalletStatus;
import com.courier.modules.finance.domain.WalletTransaction;
import com.courier.modules.finance.domain.WalletTransactionRepository;
import com.courier.modules.finance.infrastructure.CompanyPaymentGatewayResolver;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import com.courier.shared.security.AuthenticatedUser;
import com.courier.shared.security.Roles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Money out of a customer's wallet: paying for a portal booking, refunding it, and an admin's manual
 * debit. The ledger, the no-overdraft rule and the locking are the branch wallet's own; these tests
 * pin that the customer paths reach them and never need a branch.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CustomerWalletMoneyOutTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID BOOKING = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    @Mock private WalletRepository walletRepository;
    @Mock private WalletTransactionRepository transactionRepository;
    @Mock private BranchDirectoryPort branchDirectory;
    @Mock private PendingCommissionPort pendingCommission;
    @Mock private CompanyPaymentGatewayResolver paymentGatewayResolver;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private RechargeOrderTracker rechargeOrders;
    @Mock private RechargeOrderRepository rechargeOrderRepository;
    @Mock private CustomerWalletService customerWallets;

    private WalletServiceImpl service;
    private Wallet wallet;

    @BeforeEach
    void setUp() {
        service = new WalletServiceImpl(walletRepository, transactionRepository, branchDirectory,
                pendingCommission, paymentGatewayResolver, auditService, eventPublisher,
                rechargeOrders, rechargeOrderRepository, customerWallets);
        CompanyContext.setCompanyId(TENANT);
        signedInAs(Roles.CUSTOMER);

        wallet = Wallet.forCustomer(CUSTOMER, "WLT2610CUSTOMER1");
        wallet.setCompanyId(TENANT);
        wallet.setId(UUID.randomUUID());
        wallet.applyCredit(new BigDecimal("1000.00"));

        when(customerWallets.provisionForCustomer(TENANT, CUSTOMER)).thenReturn(wallet);
        when(customerWallets.getForCustomer(CUSTOMER)).thenReturn(wallet);
        when(walletRepository.lockByCustomerIdWithinCompany(CUSTOMER, TENANT)).thenReturn(Optional.of(wallet));
        when(walletRepository.findByIdWithinCompany(wallet.getId(), TENANT)).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(i -> i.getArgument(0));
        when(transactionRepository.save(any(WalletTransaction.class))).thenAnswer(i -> {
            WalletTransaction t = i.getArgument(0);
            if (t.getId() == null) {
                t.setId(UUID.randomUUID());
            }
            return t;
        });
        when(transactionRepository.existsByTransactionNo(anyString())).thenReturn(false);
        when(transactionRepository.findSettledByReferenceWithinCompany(any(), anyString(), eq(TENANT)))
                .thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        CompanyContext.clear();
        SecurityContextHolder.clearContext();
    }

    private void signedInAs(String... roles) {
        AuthenticatedUser principal = new AuthenticatedUser(USER, TENANT, "user@legacy.test", Set.of(roles), "jti");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.authorities()));
    }

    private CustomerBookingDebitCommand pay(String amount) {
        return new CustomerBookingDebitCommand(CUSTOMER, BOOKING, new BigDecimal(amount), "Courier booking SHIPROCKET");
    }

    /** What the wallet has already recorded for the booking: its SBK debit (and optionally the SRF refund). */
    private WalletTransaction ledgerEntry(TransactionType type, SubTransactionType reason, String amount) {
        WalletTransaction t = WalletTransaction.builder().transactionNo("TXN-" + UUID.randomUUID())
                .walletId(wallet.getId()).transactionType(type).subTransactionType(reason)
                .amount(new BigDecimal(amount)).balanceBefore(BigDecimal.ZERO).balanceAfter(BigDecimal.ZERO)
                .referenceType(ReferenceType.PORTAL_BOOKING).referenceId(BOOKING.toString()).build();
        t.setId(UUID.randomUUID());
        return t;
    }

    private void ledgerHolds(WalletTransaction... entries) {
        when(transactionRepository.findSettledByReferenceWithinCompany(ReferenceType.PORTAL_BOOKING,
                BOOKING.toString(), TENANT)).thenReturn(List.of(entries));
    }

    // ------------------------------------------------------------ paying for a booking

    @Test
    @DisplayName("a booking is an SBK debit referenced to the booking, taken from the customer's wallet")
    void bookingDebit() {
        WalletTransaction entry = service.debitForCustomerBooking(pay("111.80"));

        assertThat(entry.getTransactionType()).isEqualTo(TransactionType.DR);
        assertThat(entry.getSubTransactionType()).isEqualTo(SubTransactionType.SBK);
        assertThat(entry.getReferenceType()).isEqualTo(ReferenceType.PORTAL_BOOKING);
        assertThat(entry.getReferenceId()).isEqualTo(BOOKING.toString());
        assertThat(entry.getBalanceAfter()).isEqualByComparingTo("888.20");
        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("888.20");
    }

    @Test
    @DisplayName("the wallet row is locked, and no branch wallet is touched")
    void bookingDebitLocksCustomerRow() {
        service.debitForCustomerBooking(pay("10"));

        verify(walletRepository).lockByCustomerIdWithinCompany(CUSTOMER, TENANT);
        verify(walletRepository, never()).lockByBranchIdWithinCompany(any(), any());
    }

    @Test
    @DisplayName("paying twice for one booking debits once and returns the first entry")
    void bookingDebitIsIdempotent() {
        WalletTransaction first = ledgerEntry(TransactionType.DR, SubTransactionType.SBK, "111.80");
        ledgerHolds(first);

        WalletTransaction again = service.debitForCustomerBooking(pay("111.80"));

        assertThat(again).isSameAs(first);
        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("1000.00");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("a short balance is refused and leaves no entry and no balance change")
    void bookingDebitRefusedWhenShort() {
        assertThatThrownBy(() -> service.debitForCustomerBooking(pay("1000.01")))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Insufficient wallet balance");

        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("1000.00");
        verify(transactionRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("a suspended wallet cannot pay for a booking")
    void bookingDebitRefusedWhenSuspended() {
        wallet.setStatus(WalletStatus.SUSPENDED);

        assertThatThrownBy(() -> service.debitForCustomerBooking(pay("10")))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("suspended");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("a zero or missing amount is refused")
    void bookingDebitNeedsAmount() {
        assertThatThrownBy(() -> service.debitForCustomerBooking(pay("0"))).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.debitForCustomerBooking(
                new CustomerBookingDebitCommand(CUSTOMER, BOOKING, null, "x"))).isInstanceOf(BusinessRuleException.class);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("the debit is audited and announces itself without a branch")
    void bookingDebitEvent() {
        service.debitForCustomerBooking(pay("10"));

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOfSatisfying(WalletEvent.WalletDebited.class, e -> {
            assertThat(e.branchId()).isNull();
            assertThat(e.reason()).isEqualTo(SubTransactionType.SBK);
        });
        verify(auditService).record(eq(com.courier.shared.audit.domain.AuditAction.WALLET_DEBITED), any(), any(), any());
    }

    // ------------------------------------------------------------------- refund

    @Test
    @DisplayName("a refund credits the booking's debit back (SRF), on the booking, to the customer's own wallet")
    void refund() {
        ledgerHolds(ledgerEntry(TransactionType.DR, SubTransactionType.SBK, "111.80"));

        List<WalletTransaction> refunds = service.refundCustomerBooking(
                new CustomerBookingRefundCommand(BOOKING, "Courier did not accept the booking"));

        assertThat(refunds).hasSize(1);
        WalletTransaction refund = refunds.get(0);
        assertThat(refund.getTransactionType()).isEqualTo(TransactionType.CR);
        assertThat(refund.getSubTransactionType()).isEqualTo(SubTransactionType.SRF);
        assertThat(refund.getReferenceType()).isEqualTo(ReferenceType.PORTAL_BOOKING);
        assertThat(refund.getReferenceId()).isEqualTo(BOOKING.toString());
        assertThat(refund.getAmount()).isEqualByComparingTo("111.80");
        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("1111.80");
        verify(walletRepository).lockByCustomerIdWithinCompany(CUSTOMER, TENANT);
    }

    @Test
    @DisplayName("a refund needs no signed-in user: the tracking-sync job calls it")
    void refundRunsWithoutAUser() {
        ledgerHolds(ledgerEntry(TransactionType.DR, SubTransactionType.SBK, "50.00"));
        SecurityContextHolder.clearContext();

        assertThat(service.refundCustomerBooking(new CustomerBookingRefundCommand(BOOKING, "cancelled"))).hasSize(1);
    }

    @Test
    @DisplayName("refunding twice credits once")
    void refundIsIdempotent() {
        ledgerHolds(ledgerEntry(TransactionType.DR, SubTransactionType.SBK, "111.80"),
                ledgerEntry(TransactionType.CR, SubTransactionType.SRF, "111.80"));

        assertThat(service.refundCustomerBooking(new CustomerBookingRefundCommand(BOOKING, "again"))).isEmpty();

        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("1000.00");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("a booking that was never debited refunds nothing")
    void refundOfUnpaidBooking() {
        assertThat(service.refundCustomerBooking(new CustomerBookingRefundCommand(BOOKING, "cancelled"))).isEmpty();

        verify(transactionRepository, never()).save(any());
        verify(walletRepository, never()).lockByCustomerIdWithinCompany(any(), any());
    }

    @Test
    @DisplayName("a refund never undoes anything but the booking debit")
    void refundOnlyReversesBookingDebit() {
        ledgerHolds(ledgerEntry(TransactionType.DR, SubTransactionType.SBK, "111.80"),
                ledgerEntry(TransactionType.DR, SubTransactionType.MDB, "5.00"));

        assertThat(service.refundCustomerBooking(new CustomerBookingRefundCommand(BOOKING, "x"))).hasSize(1);
        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("1111.80");
    }

    @Test
    @DisplayName("a refund into a suspended wallet is refused and leaves the debit un-refunded for a retry")
    void refundIntoSuspendedWallet() {
        ledgerHolds(ledgerEntry(TransactionType.DR, SubTransactionType.SBK, "111.80"));
        wallet.setStatus(WalletStatus.SUSPENDED);

        assertThatThrownBy(() -> service.refundCustomerBooking(new CustomerBookingRefundCommand(BOOKING, "x")))
                .isInstanceOf(BusinessRuleException.class);
        verify(transactionRepository, never()).save(any());
    }

    // ------------------------------------------------------------- manual debit

    private CustomerDebitCommand manualDebit(String amount, SubTransactionType reason) {
        return new CustomerDebitCommand(CUSTOMER, new BigDecimal(amount), reason, null, null, "correction");
    }

    @Test
    @DisplayName("an admin's manual debit defaults to MDB, is a MANUAL entry, and lowers the customer's balance")
    void manualDebit() {
        signedInAs(Roles.COMPANY_ADMIN);

        WalletTransaction entry = service.debitForCustomer(manualDebit("200.00", null));

        assertThat(entry.getTransactionType()).isEqualTo(TransactionType.DR);
        assertThat(entry.getSubTransactionType()).isEqualTo(SubTransactionType.MDB);
        assertThat(entry.getReferenceType()).isEqualTo(ReferenceType.MANUAL);
        assertThat(entry.getBalanceAfter()).isEqualByComparingTo("800.00");
        verify(customerWallets).getForCustomer(CUSTOMER);
    }

    @Test
    @DisplayName("a manual debit larger than the balance is refused: no overdraft")
    void manualDebitNoOverdraft() {
        signedInAs(Roles.COMPANY_ADMIN);

        assertThatThrownBy(() -> service.debitForCustomer(manualDebit("1000.01", null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Insufficient");

        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("1000.00");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("a reason that cannot appear on a debit is refused")
    void manualDebitReasonRefused() {
        signedInAs(Roles.COMPANY_ADMIN);

        assertThatThrownBy(() -> service.debitForCustomer(manualDebit("10", SubTransactionType.MCR)))
                .isInstanceOf(BusinessRuleException.class);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("debiting a customer outside the company is a 404 and changes nothing")
    void manualDebitForeignCustomer() {
        signedInAs(Roles.COMPANY_ADMIN);
        when(customerWallets.getForCustomer(CUSTOMER)).thenThrow(new ResourceNotFoundException("Customer", CUSTOMER));

        assertThatThrownBy(() -> service.debitForCustomer(manualDebit("10", null)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(transactionRepository, never()).save(any());
    }
}
