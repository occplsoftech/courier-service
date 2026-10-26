package com.courier.modules.finance.application;

import com.courier.modules.finance.application.command.CodDeliveryDebitCommand;
import com.courier.modules.finance.application.command.CreditCommand;
import com.courier.modules.finance.application.command.DebitCommand;
import com.courier.modules.finance.application.command.RechargeCommand;
import com.courier.modules.finance.application.command.ShipmentReversalCommand;
import com.courier.modules.finance.application.payment.PaymentGatewayPort;
import com.courier.modules.finance.domain.BranchDirectoryPort;
import com.courier.modules.finance.domain.PaymentStatus;
import com.courier.modules.finance.domain.RechargeOrder;
import com.courier.modules.finance.domain.RechargeOrderRepository;
import com.courier.modules.finance.domain.PendingCommissionPort;
import com.courier.modules.finance.domain.ReferenceType;
import com.courier.modules.finance.domain.SubTransactionType;
import com.courier.modules.finance.domain.TransactionType;
import com.courier.modules.finance.domain.Wallet;
import com.courier.modules.finance.domain.WalletRepository;
import com.courier.modules.finance.domain.WalletStatus;
import com.courier.modules.finance.domain.WalletTransaction;
import com.courier.modules.finance.domain.WalletTransactionCriteria;
import com.courier.modules.finance.domain.WalletTransactionRepository;
import com.courier.modules.finance.infrastructure.CompanyPaymentGatewayResolver;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.audit.domain.AuditAction;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ForbiddenException;
import com.courier.shared.exception.ResourceNotFoundException;
import com.courier.shared.security.AuthenticatedUser;
import com.courier.shared.security.Roles;
import com.courier.shared.company.CompanyContext;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

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
 * Wallet rules, with repositories, the gateway and the audit trail mocked.
 *
 * <p>{@code @PreAuthorize} is not exercised here — there is no proxy around a hand-built
 * service — so what these tests cover is the in-code scoping, which is where the
 * branch-level rules actually live.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WalletServiceImplTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID BRANCH = UUID.randomUUID();
    private static final UUID OTHER_BRANCH = UUID.randomUUID();

    @Mock private WalletRepository walletRepository;
    @Mock private WalletTransactionRepository transactionRepository;
    @Mock private BranchDirectoryPort branchDirectory;
    @Mock private PendingCommissionPort pendingCommission;
    @Mock private PaymentGatewayPort paymentGateway;
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
        when(paymentGatewayResolver.resolve(any())).thenReturn(paymentGateway);
        when(paymentGateway.name()).thenReturn("RAZORPAY");
        when(rechargeOrders.begin(any(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            RechargeOrder o = RechargeOrder.builder().transactionNo(inv.getArgument(6))
                    .status(PaymentStatus.PENDING).build();
            o.setId(java.util.UUID.randomUUID());
            return o;
        });
        when(pendingCommission.pendingCommissionFor(any(), any()))
                .thenReturn(PendingCommissionPort.PendingCommission.ZERO);

        CompanyContext.setCompanyId(TENANT);
        planted(ADMIN, Roles.COMPANY_ADMIN);

        wallet = walletWith("1000.00", WalletStatus.ACTIVE);

        when(branchDirectory.findBranch(BRANCH, TENANT)).thenReturn(Optional.of(
                new BranchDirectoryPort.BranchRef(BRANCH, TENANT, "PUNE_MAIN", "Pune Main", true)));
        when(branchDirectory.findBranch(OTHER_BRANCH, TENANT)).thenReturn(Optional.of(
                new BranchDirectoryPort.BranchRef(OTHER_BRANCH, TENANT, "MUMBAI", "Mumbai", true)));

        when(walletRepository.findByBranchIdWithinCompany(BRANCH, TENANT))
                .thenReturn(Optional.of(wallet));
        when(walletRepository.lockByBranchIdWithinCompany(BRANCH, TENANT))
                .thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(i -> i.getArgument(0));
        when(walletRepository.existsByWalletNumber(anyString())).thenReturn(false);

        when(transactionRepository.save(any(WalletTransaction.class)))
                .thenAnswer(i -> i.getArgument(0));
        when(transactionRepository.existsByTransactionNo(anyString())).thenReturn(false);
        when(transactionRepository.sumSettledSince(any(), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(transactionRepository.findRecent(any(), any(), any())).thenReturn(List.of());
        when(transactionRepository.findLatestSettledOfType(any(), any(), any(), any()))
                .thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        CompanyContext.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private void planted(UUID userId, String... roles) {
        AuthenticatedUser principal =
                new AuthenticatedUser(userId, TENANT, "admin@legacy.test", Set.of(roles), "jti");
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(new org.springframework.security.authentication
                        .UsernamePasswordAuthenticationToken(principal, null, principal.authorities()));
    }

    private Wallet walletWith(String available, WalletStatus status) {
        Wallet w = Wallet.builder()
                .walletNumber("WLT2607ABCDEFGH")
                .branchId(BRANCH)
                .status(status)
                .availableBalance(new BigDecimal(available))
                .holdBalance(Wallet.ZERO)
                .currency("INR")
                .build();
        w.setCompanyId(TENANT);
        return w;
    }

    private WalletTransaction captureEntry() {
        ArgumentCaptor<WalletTransaction> captor = ArgumentCaptor.forClass(WalletTransaction.class);
        verify(transactionRepository).save(captor.capture());
        return captor.getValue();
    }

    // -------------------------------------------------------------- provisioning

    @Test
    @DisplayName("a branch without a wallet gets one on first access")
    void createsMissingWallet() {
        when(walletRepository.findByBranchIdWithinCompany(BRANCH, TENANT)).thenReturn(Optional.empty());

        Wallet created = service.getForBranch(BRANCH);

        assertThat(created.getBranchId()).isEqualTo(BRANCH);
        assertThat(created.getWalletNumber()).startsWith("WLT");
        assertThat(created.getAvailableBalance()).isEqualByComparingTo("0");
        assertThat(created.getStatus()).isEqualTo(WalletStatus.ACTIVE);
        verify(auditService).record(eq(AuditAction.WALLET_CREATED), eq("Wallet"), any(), any());
    }

    @Test
    @DisplayName("an existing wallet is returned, not re-created")
    void reusesExistingWallet() {
        Wallet found = service.getForBranch(BRANCH);

        assertThat(found).isSameAs(wallet);
        verify(walletRepository, never()).save(any(Wallet.class));
        verify(auditService, never()).record(eq(AuditAction.WALLET_CREATED), any(), any(), any());
    }

    @Test
    @DisplayName("an unknown branch is a 404, not an empty wallet")
    void unknownBranch() {
        UUID ghost = UUID.randomUUID();
        when(branchDirectory.findBranch(ghost, TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getForBranch(ghost))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Branch");
    }

    // -------------------------------------------------------------------- scoping

    @Test
    @DisplayName("a branch user reads their own wallet without naming it")
    void branchUserReadsOwn() {
        UUID operator = UUID.randomUUID();
        planted(operator, Roles.OPERATOR);
        when(branchDirectory.branchOfUser(operator, TENANT)).thenReturn(Optional.of(BRANCH));

        assertThat(service.getForBranch(null)).isSameAs(wallet);
    }

    @Test
    @DisplayName("a branch manager with no placement falls back to the branch they manage")
    void managerFallsBackToManagedBranch() {
        UUID manager = UUID.randomUUID();
        planted(manager, Roles.BRANCH_MANAGER);
        when(branchDirectory.branchOfUser(manager, TENANT)).thenReturn(Optional.empty());
        when(branchDirectory.branchManagedBy(manager, TENANT)).thenReturn(Optional.of(BRANCH));

        assertThat(service.getForBranch(null)).isSameAs(wallet);
    }

    @Test
    @DisplayName("reading another branch's wallet is a 404 for a branch user")
    void branchUserCannotReadAnother() {
        UUID operator = UUID.randomUUID();
        planted(operator, Roles.OPERATOR);
        when(branchDirectory.branchOfUser(operator, TENANT)).thenReturn(Optional.of(BRANCH));

        assertThatThrownBy(() -> service.getForBranch(OTHER_BRANCH))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("transacting on another branch's wallet is a 403 for a branch user")
    void branchUserCannotTransactOnAnother() {
        UUID operator = UUID.randomUUID();
        planted(operator, Roles.OPERATOR);
        when(branchDirectory.branchOfUser(operator, TENANT)).thenReturn(Optional.of(BRANCH));

        assertThatThrownBy(() -> service.openRecharge(
                new RechargeCommand(OTHER_BRANCH, new BigDecimal("100"), null, null, null, null)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("a user with no branch at all is told so, rather than shown someone else's")
    void unplacedUser() {
        UUID nomad = UUID.randomUUID();
        planted(nomad, Roles.VIEWER);
        when(branchDirectory.branchOfUser(nomad, TENANT)).thenReturn(Optional.empty());
        when(branchDirectory.branchManagedBy(nomad, TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getForBranch(null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("not assigned to a branch");
    }

    @Test
    @DisplayName("without a bound company nothing is reachable")
    void noCompany() {
        CompanyContext.clear();

        assertThatThrownBy(() -> service.getForBranch(BRANCH))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("No company is bound");
    }

    // --------------------------------------------------------------------- credit

    @Test
    @DisplayName("credit writes a CR entry, raises the balance and records both figures")
    void credit() {
        WalletTransaction entry = service.credit(new CreditCommand(BRANCH,
                new BigDecimal("250.00"), SubTransactionType.MCR, ReferenceType.MANUAL,
                "REF-1", "goodwill"));

        assertThat(entry.getTransactionType()).isEqualTo(TransactionType.CR);
        assertThat(entry.getSubTransactionType()).isEqualTo(SubTransactionType.MCR);
        assertThat(entry.getAmount()).isEqualByComparingTo("250.00");
        assertThat(entry.getBalanceBefore()).isEqualByComparingTo("1000.00");
        assertThat(entry.getBalanceAfter()).isEqualByComparingTo("1250.00");
        assertThat(entry.getTransactionNo()).startsWith("TXN");
        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("1250.00");

        verify(walletRepository).save(wallet);
        verify(auditService).record(eq(AuditAction.WALLET_CREDITED), eq("Wallet"), any(), any());
    }

    @Test
    @DisplayName("credit defaults to MCR and MANUAL when the caller says nothing")
    void creditDefaults() {
        WalletTransaction entry = service.credit(
                new CreditCommand(BRANCH, new BigDecimal("10"), null, null, null, null));

        assertThat(entry.getSubTransactionType()).isEqualTo(SubTransactionType.MCR);
        assertThat(entry.getReferenceType()).isEqualTo(ReferenceType.MANUAL);
    }

    @Test
    @DisplayName("a manual recharge is recorded in the recharge table first, then linked to its ledger entry")
    void manualRechargeRecordedFirst() {
        RechargeOrder recharge = RechargeOrder.builder().status(PaymentStatus.SUCCESS).build();
        recharge.setId(java.util.UUID.randomUUID());
        when(rechargeOrders.recordManual(any(), any(), any(), any(), any(), any(), any())).thenReturn(recharge);

        WalletTransaction entry = service.credit(new CreditCommand(BRANCH, new BigDecimal("250.00"),
                SubTransactionType.MCR, null, null, "cash received"));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(rechargeOrders, transactionRepository);
        order.verify(rechargeOrders).recordManual(any(), any(), any(),
                org.mockito.ArgumentMatchers.argThat((BigDecimal a) -> a.compareTo(new BigDecimal("250")) == 0), any(), any(),
                eq("cash received"));
        order.verify(transactionRepository).save(any());
        verify(rechargeOrders).linkLedger(eq(recharge.getId()), any());
        assertThat(entry.getSubTransactionType()).isEqualTo(SubTransactionType.MCR);
    }

    @Test
    @DisplayName("a non-recharge credit reason never touches the recharge table")
    void commissionCreditIsNotARecharge() {
        service.credit(new CreditCommand(BRANCH, new BigDecimal("10.00"),
                SubTransactionType.COM, null, null, "commission"));
        verify(rechargeOrders, never()).recordManual(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("credit refuses a debit-only reason and changes nothing")
    void creditWithDebitReason() {
        assertThatThrownBy(() -> service.credit(new CreditCommand(BRANCH,
                new BigDecimal("10"), SubTransactionType.SBK, null, null, null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("SBK");

        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("1000.00");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("a non-positive amount is refused before anything is written")
    void nonPositiveAmount() {
        assertThatThrownBy(() -> service.credit(
                new CreditCommand(BRANCH, BigDecimal.ZERO, null, null, null, null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("greater than zero");

        verify(transactionRepository, never()).save(any());
    }

    // ---------------------------------------------------------------------- debit

    @Test
    @DisplayName("debit writes a DR entry and lowers the balance")
    void debit() {
        WalletTransaction entry = service.debit(new DebitCommand(BRANCH,
                new BigDecimal("400.00"), SubTransactionType.PNL, ReferenceType.MANUAL,
                null, "penalty"));

        assertThat(entry.getTransactionType()).isEqualTo(TransactionType.DR);
        assertThat(entry.getBalanceBefore()).isEqualByComparingTo("1000.00");
        assertThat(entry.getBalanceAfter()).isEqualByComparingTo("600.00");
        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("600.00");
        verify(auditService).record(eq(AuditAction.WALLET_DEBITED), eq("Wallet"), any(), any());
    }

    @Test
    @DisplayName("a debit beyond the balance is refused and leaves no ledger entry")
    void debitInsufficient() {
        assertThatThrownBy(() -> service.debit(new DebitCommand(BRANCH,
                new BigDecimal("1000.01"), null, null, null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Insufficient wallet balance");

        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("1000.00");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("TO_PAY receipt at branch overdraws the wallet instead of being refused")
    void toPayReceiptOverdraws() {
        WalletTransaction entry = service.debitForToPayReceivedAtBranch(
                new CodDeliveryDebitCommand(BRANCH, new BigDecimal("1880.00"), "C-KOL-000010",
                        "TO_PAY received at branch for shipment C-KOL-000010"));

        assertThat(entry.getSubTransactionType()).isEqualTo(SubTransactionType.TPY);
        assertThat(entry.getBalanceBefore()).isEqualByComparingTo("1000.00");
        assertThat(entry.getBalanceAfter()).isEqualByComparingTo("-880.00");
        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("-880.00");
        verify(auditService).record(eq(AuditAction.WALLET_DEBITED), eq("Wallet"), any(), any());
    }

    @Test
    @DisplayName("COD delivery debit is still refused beyond the balance, unlike TO_PAY")
    void codDeliveryStillRefused() {
        assertThatThrownBy(() -> service.debitForCodDelivery(
                new CodDeliveryDebitCommand(BRANCH, new BigDecimal("1000.01"), "SHP-9", "COD")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Insufficient wallet balance");

        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("1000.00");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("debit refuses a credit-only reason")
    void debitWithCreditReason() {
        assertThatThrownBy(() -> service.debit(new DebitCommand(BRANCH,
                new BigDecimal("10"), SubTransactionType.WRC, null, null, null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("WRC");
    }

    @Test
    @DisplayName("a wallet that is not ACTIVE refuses money in either direction")
    void suspendedWallet() {
        Wallet suspended = walletWith("500.00", WalletStatus.SUSPENDED);
        when(walletRepository.findByBranchIdWithinCompany(BRANCH, TENANT))
                .thenReturn(Optional.of(suspended));
        when(walletRepository.lockByBranchIdWithinCompany(BRANCH, TENANT))
                .thenReturn(Optional.of(suspended));

        assertThatThrownBy(() -> service.credit(
                new CreditCommand(BRANCH, new BigDecimal("10"), null, null, null, null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("suspended");
        assertThatThrownBy(() -> service.debit(
                new DebitCommand(BRANCH, new BigDecimal("10"), null, null, null, null)))
                .isInstanceOf(BusinessRuleException.class);
    }

    // ------------------------------------------------------------------- recharge

    private RechargeCommand settlement() {
        return new RechargeCommand(BRANCH, new BigDecimal("999999.00"),
                "order_1", "pay_1", "sig_1", "top-up");
    }

    private void gatewayReports(String amount, String currency, boolean captured) {
        when(paymentGateway.fetchPayment("pay_1")).thenReturn(
                new PaymentGatewayPort.GatewayPayment("RAZORPAY", "pay_1", "order_1",
                        new BigDecimal(amount), currency, captured));
    }

    @Test
    @DisplayName("opening a recharge fixes the amount at the gateway and credits nothing")
    void openRecharge() {
        when(paymentGateway.createOrder(any(), any(), any(), any())).thenReturn(
                new PaymentGatewayPort.GatewayOrder("RAZORPAY", "order_1", 500000L, "INR",
                        "rzp_test_key", "TXN..."));

        PaymentGatewayPort.GatewayOrder order = service.openRecharge(
                new RechargeCommand(BRANCH, new BigDecimal("5000.00"), null, null, null, null));

        assertThat(order.orderId()).isEqualTo("order_1");
        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("1000.00");
        verify(transactionRepository, never()).save(any());
        verify(auditService).record(eq(AuditAction.WALLET_RECHARGE_INITIATED), any(), any(), any());
        // Our record exists before the gateway order, and the gateway receipt is our txn number.
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(rechargeOrders, paymentGateway);
        inOrder.verify(rechargeOrders).begin(any(), any(), any(), any(), any(), eq("RAZORPAY"), any());
        inOrder.verify(paymentGateway).createOrder(any(), any(), any(), any());
        inOrder.verify(rechargeOrders).attachGatewayOrder(any(), eq("order_1"));
    }

    @Test
    @DisplayName("a gateway failure on opening is recorded as FAILED, not lost")
    void openRechargeGatewayDown() {
        when(paymentGateway.createOrder(any(), any(), any(), any()))
                .thenThrow(new BusinessRuleException("The payment gateway could not be reached."));

        assertThatThrownBy(() -> service.openRecharge(
                new RechargeCommand(BRANCH, new BigDecimal("5000.00"), null, null, null, null)))
                .isInstanceOf(BusinessRuleException.class);

        verify(rechargeOrders).markFailed(any(), org.mockito.ArgumentMatchers.contains("not created"));
        verify(rechargeOrders, never()).attachGatewayOrder(any(), any());
    }

    private RechargeOrder pendingOrder(String gatewayOrderId, java.time.Duration age) {
        RechargeOrder o = RechargeOrder.builder().branchId(BRANCH).transactionNo("TXN1")
                .gatewayOrderId(gatewayOrderId).status(PaymentStatus.PENDING).build();
        o.setId(java.util.UUID.randomUUID());
        o.setCompanyId(TENANT);
        org.springframework.test.util.ReflectionTestUtils.setField(o, "createdAt", java.time.Instant.now().minus(age));
        when(rechargeOrderRepository.findById(o.getId())).thenReturn(Optional.of(o));
        return o;
    }

    @Test
    @DisplayName("reconciliation credits a captured payment the browser never confirmed")
    void reconcileCaptured() {
        RechargeOrder o = pendingOrder("order_1", java.time.Duration.ofMinutes(5));
        when(paymentGateway.fetchOrderPayments("order_1")).thenReturn(
                java.util.List.of(new PaymentGatewayPort.OrderPayment("pay_1", "captured", true, null)));
        gatewayReports("500.00", "INR", true);

        service.reconcileRecharge(o.getId());

        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("1500.00");
        verify(rechargeOrders).markSuccess(eq(TENANT), eq("order_1"), eq("pay_1"), any());
    }

    @Test
    @DisplayName("reconciliation marks an order whose payments all failed as FAILED")
    void reconcileFailed() {
        RechargeOrder o = pendingOrder("order_1", java.time.Duration.ofMinutes(5));
        when(paymentGateway.fetchOrderPayments("order_1")).thenReturn(
                java.util.List.of(new PaymentGatewayPort.OrderPayment("pay_1", "failed", false, "Card declined")));

        service.reconcileRecharge(o.getId());

        verify(rechargeOrders).markFailed(o.getId(), "Card declined");
        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("reconciliation leaves a young unpaid order pending and gives up on an old one")
    void reconcileUnpaid() {
        RechargeOrder young = pendingOrder("order_1", java.time.Duration.ofMinutes(5));
        when(paymentGateway.fetchOrderPayments("order_1")).thenReturn(java.util.List.of());
        service.reconcileRecharge(young.getId());
        verify(rechargeOrders).touch(young.getId());
        verify(rechargeOrders, never()).markFailed(eq(young.getId()), any());

        RechargeOrder old = pendingOrder("order_1", java.time.Duration.ofMinutes(15));
        service.reconcileRecharge(old.getId());
        verify(rechargeOrders).markFailed(eq(old.getId()), org.mockito.ArgumentMatchers.contains("10 minutes"));
    }

    @Test
    @DisplayName("a gateway outage during reconciliation leaves the order pending")
    void reconcileGatewayDown() {
        RechargeOrder o = pendingOrder("order_1", java.time.Duration.ofMinutes(15));
        when(paymentGateway.fetchOrderPayments("order_1"))
                .thenThrow(new BusinessRuleException("The payment gateway could not be reached."));

        service.reconcileRecharge(o.getId());

        verify(rechargeOrders).touch(o.getId());
        verify(rechargeOrders, never()).markFailed(any(), any());
    }

    @Test
    @DisplayName("the gateway's amount is credited, not the client's")
    void rechargeCreditsGatewayAmount() {
        gatewayReports("500.00", "INR", true);

        WalletTransaction entry = service.completeRecharge(settlement());

        // The command claimed 999,999. The gateway said 500.
        assertThat(entry.getAmount()).isEqualByComparingTo("500.00");
        assertThat(entry.getBalanceAfter()).isEqualByComparingTo("1500.00");
        assertThat(entry.getSubTransactionType()).isEqualTo(SubTransactionType.WRC);
        assertThat(entry.getTransactionType()).isEqualTo(TransactionType.CR);
        assertThat(entry.getPaymentReference()).isEqualTo("pay_1");
        assertThat(entry.getReferenceType()).isEqualTo(ReferenceType.PAYMENT);
        verify(paymentGateway).verifyPayment(any());
        verify(auditService).record(eq(AuditAction.WALLET_RECHARGED), any(), any(), any());
    }

    @Test
    @DisplayName("settling the same payment twice credits the wallet once")
    void rechargeIsIdempotent() {
        WalletTransaction existing = WalletTransaction.builder()
                .transactionNo("TXN-EXISTING").walletId(wallet.getId())
                .transactionType(TransactionType.CR).subTransactionType(SubTransactionType.WRC)
                .amount(new BigDecimal("500.00")).balanceBefore(new BigDecimal("1000.00"))
                .balanceAfter(new BigDecimal("1500.00")).paymentReference("pay_1").build();
        when(transactionRepository.findByPaymentReferenceWithinCompany("pay_1", TENANT))
                .thenReturn(Optional.of(existing));

        WalletTransaction entry = service.completeRecharge(settlement());

        assertThat(entry).isSameAs(existing);
        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("1000.00");
        verify(transactionRepository, never()).save(any());
        verify(paymentGateway, never()).verifyPayment(any());
    }

    @Test
    @DisplayName("a payment already recorded by another company is refused without saying whose")
    void rechargeClaimedFromAnotherCompany() {
        when(transactionRepository.findByPaymentReferenceWithinCompany("pay_1", TENANT))
                .thenReturn(Optional.empty());
        when(transactionRepository.isPaymentReferenceRecorded("pay_1")).thenReturn(true);

        assertThatThrownBy(() -> service.completeRecharge(settlement()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("This payment has already been recorded.");

        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("an uncaptured payment credits nothing")
    void rechargeUncaptured() {
        gatewayReports("500.00", "INR", false);

        assertThatThrownBy(() -> service.completeRecharge(settlement()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("not been captured");

        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("a payment belonging to another order is refused")
    void rechargeOrderMismatch() {
        when(paymentGateway.fetchPayment("pay_1")).thenReturn(
                new PaymentGatewayPort.GatewayPayment("RAZORPAY", "pay_1", "order_SOMEONE_ELSE",
                        new BigDecimal("500.00"), "INR", true));

        assertThatThrownBy(() -> service.completeRecharge(settlement()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("does not belong to that order");
    }

    @Test
    @DisplayName("a currency mismatch is refused rather than credited at par")
    void rechargeCurrencyMismatch() {
        gatewayReports("500.00", "USD", true);

        assertThatThrownBy(() -> service.completeRecharge(settlement()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("USD");

        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("a failed signature check credits nothing")
    void rechargeBadSignature() {
        org.mockito.Mockito.doThrow(new BusinessRuleException("could not be verified"))
                .when(paymentGateway).verifyPayment(any());

        assertThatThrownBy(() -> service.completeRecharge(settlement()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("could not be verified");

        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("1000.00");
        verify(transactionRepository, never()).save(any());
        verify(paymentGateway, never()).fetchPayment(anyString());
    }

    @Test
    @DisplayName("settling without the gateway fields is a clear 422, not a null pointer")
    void rechargeMissingFields() {
        assertThatThrownBy(() -> service.completeRecharge(
                new RechargeCommand(BRANCH, new BigDecimal("100"), "order_1", null, "sig", null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("paymentReference");
    }

    // ----------------------------------------------------------------- statement

    @Test
    @DisplayName("a ledger search is pinned to the caller's own wallet and company")
    void searchIsPinned() {
        Pageable pageable = PageRequest.of(0, 20);
        when(transactionRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of()));

        // A criteria object arriving with somebody else's wallet id must not survive.
        WalletTransactionCriteria hostile =
                WalletTransactionCriteria.none().scopedTo(UUID.randomUUID(), UUID.randomUUID());

        Page<WalletTransaction> page = service.searchTransactions(BRANCH, hostile, pageable);

        assertThat(page).isEmpty();
        verify(transactionRepository).findAll(any(Specification.class), eq(pageable));
    }

    @Test
    @DisplayName("the summary reports the balances and the derived period figures")
    void summary() {
        when(transactionRepository.sumSettledSince(eq(wallet.getId()), eq(TENANT),
                eq(TransactionType.CR), any())).thenReturn(new BigDecimal("3000"));
        when(transactionRepository.sumSettledSince(eq(wallet.getId()), eq(TENANT),
                eq(TransactionType.DR), any())).thenReturn(new BigDecimal("1200"));
        when(transactionRepository.countByWallet(wallet.getId(), TENANT)).thenReturn(17L);

        WalletService.WalletSummary summary = service.summarise(BRANCH);

        assertThat(summary.wallet()).isSameAs(wallet);
        assertThat(summary.branchCode()).isEqualTo("PUNE_MAIN");
        assertThat(summary.branchName()).isEqualTo("Pune Main");
        assertThat(summary.totalCredit()).isEqualByComparingTo("3000");
        assertThat(summary.totalDebit()).isEqualByComparingTo("1200");
        assertThat(summary.transactionCount()).isEqualTo(17L);
        assertThat(summary.lastTransactionAt()).isNull();
        assertThat(summary.lastRechargeAmount()).isNull();
    }

    @Test
    @DisplayName("a transaction number collision is retried, not surfaced")
    void transactionNumberCollision() {
        when(transactionRepository.existsByTransactionNo(anyString()))
                .thenReturn(true, true, false);

        WalletTransaction entry = service.credit(
                new CreditCommand(BRANCH, new BigDecimal("10"), null, null, null, null));

        assertThat(entry.getTransactionNo()).startsWith("TXN");
    }

    @Test
    @DisplayName("every money path takes the row lock, never the plain read")
    void moneyPathsLock() {
        service.credit(new CreditCommand(BRANCH, new BigDecimal("10"), null, null, null, null));

        verify(walletRepository).lockByBranchIdWithinCompany(BRANCH, TENANT);
        assertThat(captureEntry().getWalletId()).isEqualTo(wallet.getId());
    }

    // ---------------------------------------------------------- shipment cancellation

    private WalletTransaction originalEntry(UUID walletId, TransactionType type,
                                            SubTransactionType reason, String amount, String shipmentNumber) {
        return WalletTransaction.builder()
                .transactionNo("TXN-ORIG-" + UUID.randomUUID())
                .walletId(walletId)
                .transactionType(type)
                .subTransactionType(reason)
                .amount(new BigDecimal(amount))
                .balanceBefore(BigDecimal.ZERO)
                .balanceAfter(BigDecimal.ZERO)
                .referenceType(ReferenceType.SHIPMENT)
                .referenceId(shipmentNumber)
                .build();
    }

    @Test
    @DisplayName("cancelling a shipment credits back its freight debit as SRF")
    void reverseCreditsBackADebit() {
        WalletTransaction original = originalEntry(wallet.getId(), TransactionType.DR,
                SubTransactionType.SBK, "300.00", "SHP-1");
        when(transactionRepository.findSettledByReferenceWithinCompany(
                ReferenceType.SHIPMENT, "SHP-1", TENANT))
                .thenReturn(List.of(original));
        when(walletRepository.findByIdWithinCompany(wallet.getId(), TENANT))
                .thenReturn(Optional.of(wallet));

        List<WalletTransaction> reversals = service.reverseForShipment(
                new ShipmentReversalCommand("SHP-1", "Shipment SHP-1 cancelled"));

        assertThat(reversals).hasSize(1);
        WalletTransaction reversal = reversals.get(0);
        assertThat(reversal.getTransactionType()).isEqualTo(TransactionType.CR);
        assertThat(reversal.getSubTransactionType()).isEqualTo(SubTransactionType.SRF);
        assertThat(reversal.getAmount()).isEqualByComparingTo("300.00");
        assertThat(reversal.getReferenceId()).isEqualTo("SHP-1");
        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("1300.00");
        verify(auditService).record(eq(AuditAction.WALLET_CREDITED), eq("Wallet"), any(), any());
    }

    @Test
    @DisplayName("cancelling a shipment debits back its commission credit as ADJ")
    void reverseDebitsBackACredit() {
        WalletTransaction original = originalEntry(wallet.getId(), TransactionType.CR,
                SubTransactionType.COM, "150.00", "SHP-2");
        when(transactionRepository.findSettledByReferenceWithinCompany(
                ReferenceType.SHIPMENT, "SHP-2", TENANT))
                .thenReturn(List.of(original));
        when(walletRepository.findByIdWithinCompany(wallet.getId(), TENANT))
                .thenReturn(Optional.of(wallet));

        List<WalletTransaction> reversals = service.reverseForShipment(
                new ShipmentReversalCommand("SHP-2", "Shipment SHP-2 cancelled"));

        assertThat(reversals).hasSize(1);
        WalletTransaction reversal = reversals.get(0);
        assertThat(reversal.getTransactionType()).isEqualTo(TransactionType.DR);
        assertThat(reversal.getSubTransactionType()).isEqualTo(SubTransactionType.ADJ);
        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("850.00");
        verify(auditService).record(eq(AuditAction.WALLET_DEBITED), eq("Wallet"), any(), any());
    }

    @Test
    @DisplayName("a reversal entry (SRF/ADJ) is never itself reversed")
    void reversalsAreNotReReversed() {
        WalletTransaction alreadyReversed = originalEntry(wallet.getId(), TransactionType.CR,
                SubTransactionType.SRF, "300.00", "SHP-3");
        when(transactionRepository.findSettledByReferenceWithinCompany(
                ReferenceType.SHIPMENT, "SHP-3", TENANT))
                .thenReturn(List.of(alreadyReversed));

        List<WalletTransaction> reversals = service.reverseForShipment(
                new ShipmentReversalCommand("SHP-3", "remarks"));

        assertThat(reversals).isEmpty();
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("one entry's reversal failure does not block the others")
    void partialFailureContinues() {
        Wallet inactiveWallet = Wallet.builder()
                .walletNumber("WLT-INACTIVE")
                .branchId(OTHER_BRANCH)
                .status(WalletStatus.INACTIVE)
                .availableBalance(new BigDecimal("500.00"))
                .holdBalance(Wallet.ZERO)
                .currency("INR")
                .build();
        inactiveWallet.setCompanyId(TENANT);

        WalletTransaction failing = originalEntry(inactiveWallet.getId(), TransactionType.CR,
                SubTransactionType.COM, "50.00", "SHP-4");
        WalletTransaction succeeding = originalEntry(wallet.getId(), TransactionType.DR,
                SubTransactionType.SBK, "200.00", "SHP-4");
        when(transactionRepository.findSettledByReferenceWithinCompany(
                ReferenceType.SHIPMENT, "SHP-4", TENANT))
                .thenReturn(List.of(failing, succeeding));
        when(walletRepository.findByIdWithinCompany(inactiveWallet.getId(), TENANT))
                .thenReturn(Optional.of(inactiveWallet));
        when(walletRepository.findByIdWithinCompany(wallet.getId(), TENANT))
                .thenReturn(Optional.of(wallet));
        when(walletRepository.findByBranchIdWithinCompany(OTHER_BRANCH, TENANT))
                .thenReturn(Optional.of(inactiveWallet));
        when(walletRepository.lockByBranchIdWithinCompany(OTHER_BRANCH, TENANT))
                .thenReturn(Optional.of(inactiveWallet));

        List<WalletTransaction> reversals = service.reverseForShipment(
                new ShipmentReversalCommand("SHP-4", "remarks"));

        assertThat(reversals).hasSize(1);
        assertThat(reversals.get(0).getSubTransactionType()).isEqualTo(SubTransactionType.SRF);
        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("1200.00");
        assertThat(inactiveWallet.getAvailableBalance()).isEqualByComparingTo("500.00");
    }
}
