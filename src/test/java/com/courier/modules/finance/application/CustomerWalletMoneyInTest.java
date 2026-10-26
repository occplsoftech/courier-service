package com.courier.modules.finance.application;

import com.courier.modules.finance.application.command.CustomerRechargeOrderCommand;
import com.courier.modules.finance.application.command.CustomerCreditCommand;
import com.courier.modules.finance.application.event.WalletEvent;
import com.courier.modules.finance.application.payment.PaymentGatewayPort;
import com.courier.modules.finance.domain.BranchDirectoryPort;
import com.courier.modules.finance.domain.PaymentStatus;
import com.courier.modules.finance.domain.PendingCommissionPort;
import com.courier.modules.finance.domain.RechargeOrder;
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
import com.courier.shared.audit.domain.AuditAction;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * Money in for a customer's wallet: online recharge, webhook and reconciliation settlement,
 * manual credit. Repositories, the gateway and the audit trail are mocked.
 *
 * <p>The point of these tests is that the customer paths reach the <em>same</em> ledger and gateway
 * rules as a branch's (the gateway's amount is credited, a payment settles once, a refused credit
 * leaves nothing behind) while never touching a branch.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CustomerWalletMoneyInTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();

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
        CompanyContext.setCompanyId(TENANT);
        signedInAs(ADMIN, Roles.COMPANY_ADMIN);

        wallet = Wallet.forCustomer(CUSTOMER, "WLT2610CUSTOMER1");
        wallet.setCompanyId(TENANT);
        wallet.applyCredit(new BigDecimal("1000.00"));

        when(customerWallets.provisionForCustomer(TENANT, CUSTOMER)).thenReturn(wallet);
        when(customerWallets.getForCustomer(CUSTOMER)).thenReturn(wallet);
        when(walletRepository.lockByCustomerIdWithinCompany(CUSTOMER, TENANT)).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(i -> i.getArgument(0));
        when(transactionRepository.save(any(WalletTransaction.class))).thenAnswer(i -> i.getArgument(0));
        when(transactionRepository.existsByTransactionNo(anyString())).thenReturn(false);

        when(paymentGatewayResolver.resolve(any())).thenReturn(paymentGateway);
        when(paymentGateway.name()).thenReturn("RAZORPAY");
        when(rechargeOrders.beginForCustomer(any(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            RechargeOrder o = RechargeOrder.builder().transactionNo(inv.getArgument(6))
                    .status(PaymentStatus.PENDING).build();
            o.setId(UUID.randomUUID());
            return o;
        });
    }

    @AfterEach
    void tearDown() {
        CompanyContext.clear();
        SecurityContextHolder.clearContext();
    }

    private void signedInAs(UUID userId, String... roles) {
        AuthenticatedUser principal = new AuthenticatedUser(userId, TENANT, "user@legacy.test", Set.of(roles), "jti");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.authorities()));
    }

    private void gatewayReports(String amount, boolean captured) {
        when(paymentGateway.fetchPayment("pay_1")).thenReturn(new PaymentGatewayPort.GatewayPayment(
                "RAZORPAY", "pay_1", "order_1", new BigDecimal(amount), "INR", captured));
    }

    // ------------------------------------------------------------------ open a recharge

    @Test
    @DisplayName("opening a customer's recharge records it against the customer and credits nothing")
    void opensRecharge() {
        signedInAs(UUID.randomUUID(), Roles.CUSTOMER);
        when(paymentGateway.createOrder(any(), any(), any(), any())).thenReturn(
                new PaymentGatewayPort.GatewayOrder("RAZORPAY", "order_1", 500000L, "INR", "rzp_test_key", "TXN"));

        PaymentGatewayPort.GatewayOrder order = service.openRechargeForCustomer(new CustomerRechargeOrderCommand(CUSTOMER, new BigDecimal("5000.00")));

        assertThat(order.orderId()).isEqualTo("order_1");
        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("1000.00");
        verify(transactionRepository, never()).save(any());
        verify(rechargeOrders).beginForCustomer(eq(TENANT), any(), eq(CUSTOMER), any(), eq("INR"), eq("RAZORPAY"), any());
        verify(rechargeOrders, never()).begin(any(), any(), any(), any(), any(), any(), any());
        verify(rechargeOrders).attachGatewayOrder(any(), eq("order_1"));
        verify(auditService).record(eq(AuditAction.WALLET_RECHARGE_INITIATED), any(), any(), any());
    }

    @Test
    @DisplayName("the gateway notes name the customer, so the webhook can find the wallet")
    void gatewayNotesNameTheCustomer() {
        when(paymentGateway.createOrder(any(), any(), any(), any())).thenReturn(
                new PaymentGatewayPort.GatewayOrder("RAZORPAY", "order_1", 500000L, "INR", "k", "TXN"));

        service.openRechargeForCustomer(new CustomerRechargeOrderCommand(CUSTOMER, new BigDecimal("5000.00")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> notes = ArgumentCaptor.forClass(Map.class);
        verify(paymentGateway).createOrder(any(), any(), any(), notes.capture());
        assertThat(notes.getValue()).containsEntry("customerId", CUSTOMER.toString())
                .containsEntry("companyId", TENANT.toString())
                .doesNotContainKey("branchId");
    }

    @Test
    @DisplayName("a gateway failure on opening is recorded as FAILED, not lost")
    void gatewayDownOnOpen() {
        when(paymentGateway.createOrder(any(), any(), any(), any())).thenThrow(new BusinessRuleException("down"));

        assertThatThrownBy(() -> service.openRechargeForCustomer(new CustomerRechargeOrderCommand(CUSTOMER, new BigDecimal("100"))))
                .isInstanceOf(BusinessRuleException.class);

        verify(rechargeOrders).markFailed(any(), org.mockito.ArgumentMatchers.contains("not created"));
    }

    @Test
    @DisplayName("a suspended customer wallet cannot be recharged")
    void suspendedWallet() {
        wallet.setStatus(WalletStatus.SUSPENDED);

        assertThatThrownBy(() -> service.openRechargeForCustomer(new CustomerRechargeOrderCommand(CUSTOMER, new BigDecimal("100"))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("suspended");

        verify(paymentGateway, never()).createOrder(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a zero or missing amount is refused before anything is recorded")
    void amountRequired() {
        assertThatThrownBy(() -> service.openRechargeForCustomer(new CustomerRechargeOrderCommand(CUSTOMER, BigDecimal.ZERO)))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.openRechargeForCustomer(new CustomerRechargeOrderCommand(CUSTOMER, null)))
                .isInstanceOf(BusinessRuleException.class);

        verify(rechargeOrders, never()).beginForCustomer(any(), any(), any(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------- complete a recharge

    @Test
    @DisplayName("the gateway's amount is credited to the customer wallet, not the client's")
    void creditsGatewayAmount() {
        gatewayReports("500.00", true);

        WalletTransaction entry = service.completeRechargeForCustomer(CUSTOMER, "order_1", "pay_1", "sig_1");

        assertThat(entry.getAmount()).isEqualByComparingTo("500.00");
        assertThat(entry.getBalanceAfter()).isEqualByComparingTo("1500.00");
        assertThat(entry.getTransactionType()).isEqualTo(TransactionType.CR);
        assertThat(entry.getSubTransactionType()).isEqualTo(SubTransactionType.WRC);
        assertThat(entry.getPaymentReference()).isEqualTo("pay_1");
        verify(paymentGateway).verifyPayment(any());
        verify(rechargeOrders).markSuccess(eq(TENANT), eq("order_1"), eq("pay_1"), any());
        verify(auditService).record(eq(AuditAction.WALLET_RECHARGED), any(), any(), any());
    }

    @Test
    @DisplayName("the recharge event carries no branch")
    void rechargeEventHasNoBranch() {
        gatewayReports("500.00", true);

        service.completeRechargeForCustomer(CUSTOMER, "order_1", "pay_1", "sig_1");

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOfSatisfying(WalletEvent.WalletRecharged.class, e -> {
            assertThat(e.branchId()).isNull();
            assertThat(e.walletId()).isEqualTo(wallet.getId());
            assertThat(e.amount()).isEqualByComparingTo("500.00");
        });
    }

    @Test
    @DisplayName("settling the same payment twice credits the customer wallet once")
    void idempotent() {
        WalletTransaction existing = WalletTransaction.builder().transactionNo("TXN-EXISTING")
                .walletId(wallet.getId()).transactionType(TransactionType.CR)
                .subTransactionType(SubTransactionType.WRC).amount(new BigDecimal("500.00"))
                .balanceBefore(new BigDecimal("1000.00")).balanceAfter(new BigDecimal("1500.00"))
                .paymentReference("pay_1").build();
        when(transactionRepository.findByPaymentReferenceWithinCompany("pay_1", TENANT))
                .thenReturn(Optional.of(existing));

        WalletTransaction entry = service.completeRechargeForCustomer(CUSTOMER, "order_1", "pay_1", "sig_1");

        assertThat(entry).isSameAs(existing);
        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("1000.00");
        verify(transactionRepository, never()).save(any());
        verify(paymentGateway, never()).verifyPayment(any());
    }

    @Test
    @DisplayName("an uncaptured payment credits nothing")
    void uncaptured() {
        gatewayReports("500.00", false);

        assertThatThrownBy(() -> service.completeRechargeForCustomer(CUSTOMER, "order_1", "pay_1", "sig_1"))
                .isInstanceOf(BusinessRuleException.class);

        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("1000.00");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("a payment for a different order is refused")
    void orderMismatch() {
        when(paymentGateway.fetchPayment("pay_1")).thenReturn(new PaymentGatewayPort.GatewayPayment(
                "RAZORPAY", "pay_1", "order_OTHER", new BigDecimal("500.00"), "INR", true));

        assertThatThrownBy(() -> service.completeRechargeForCustomer(CUSTOMER, "order_1", "pay_1", "sig_1"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("does not belong");
        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("the payment id, order id and signature are all required")
    void fieldsRequired() {
        assertThatThrownBy(() -> service.completeRechargeForCustomer(CUSTOMER, "order_1", " ", "sig_1"))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.completeRechargeForCustomer(CUSTOMER, null, "pay_1", "sig_1"))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.completeRechargeForCustomer(CUSTOMER, "order_1", "pay_1", ""))
                .isInstanceOf(BusinessRuleException.class);
    }

    // --------------------------------------------------------- webhook and reconciliation

    @Test
    @DisplayName("the webhook settles a customer's captured payment")
    void webhookSettles() {
        gatewayReports("500.00", true);

        WalletTransaction entry = service.settleFromWebhookForCustomer(TENANT, CUSTOMER, "order_1", "pay_1");

        assertThat(entry.getBalanceAfter()).isEqualByComparingTo("1500.00");
        verify(rechargeOrders).markSuccess(eq(TENANT), eq("order_1"), eq("pay_1"), any());
        verify(paymentGateway, never()).verifyPayment(any());
    }

    private RechargeOrder pendingCustomerOrder(Duration age) {
        RechargeOrder o = RechargeOrder.builder().customerId(CUSTOMER).transactionNo("TXN1")
                .gatewayOrderId("order_1").status(PaymentStatus.PENDING).build();
        o.setId(UUID.randomUUID());
        o.setCompanyId(TENANT);
        ReflectionTestUtils.setField(o, "createdAt", Instant.now().minus(age));
        when(rechargeOrderRepository.findById(o.getId())).thenReturn(Optional.of(o));
        return o;
    }

    @Test
    @DisplayName("reconciliation credits a customer's captured payment the browser never confirmed")
    void reconcileCredits() {
        RechargeOrder o = pendingCustomerOrder(Duration.ofMinutes(5));
        when(paymentGateway.fetchOrderPayments("order_1")).thenReturn(
                List.of(new PaymentGatewayPort.OrderPayment("pay_1", "captured", true, null)));
        gatewayReports("500.00", true);

        service.reconcileRecharge(o.getId());

        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("1500.00");
        verify(rechargeOrders).markSuccess(eq(TENANT), eq("order_1"), eq("pay_1"), any());
        verify(walletRepository, never()).lockByBranchIdWithinCompany(any(), any());
    }

    @Test
    @DisplayName("only company-wide staff may check a customer's recharge attempt")
    void branchStaffCannotCheckCustomerRecharge() {
        RechargeOrder o = pendingCustomerOrder(Duration.ofMinutes(5));
        signedInAs(UUID.randomUUID(), Roles.BRANCH_MANAGER);
        when(branchDirectory.branchOfUser(any(), any())).thenReturn(Optional.of(UUID.randomUUID()));

        assertThatThrownBy(() -> service.checkRechargeAttempt(o.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ----------------------------------------------------------------- manual credit

    @Test
    @DisplayName("an admin's manual credit is a recharge row first, then a ledger entry, for the customer")
    void manualCredit() {
        RechargeOrder recharge = RechargeOrder.builder().status(PaymentStatus.SUCCESS).build();
        recharge.setId(UUID.randomUUID());
        when(rechargeOrders.recordManualForCustomer(any(), any(), any(), any(), any(), any(), any())).thenReturn(recharge);

        WalletTransaction entry = service.creditForCustomer(new CustomerCreditCommand(CUSTOMER, new BigDecimal("250.00"), null, null,
                null, "bank transfer received"));

        assertThat(entry.getSubTransactionType()).isEqualTo(SubTransactionType.MCR);
        assertThat(entry.getReferenceType()).isEqualTo(ReferenceType.MANUAL);
        assertThat(entry.getBalanceAfter()).isEqualByComparingTo("1250.00");
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(rechargeOrders, transactionRepository);
        order.verify(rechargeOrders).recordManualForCustomer(eq(TENANT), any(), eq(CUSTOMER), any(), any(), any(),
                eq("bank transfer received"));
        order.verify(transactionRepository).save(any());
        verify(rechargeOrders).linkLedger(eq(recharge.getId()), any());
        verify(rechargeOrders, never()).recordManual(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a manual credit event carries no branch")
    void manualCreditEvent() {
        service.creditForCustomer(new CustomerCreditCommand(CUSTOMER, new BigDecimal("10"), null, null, null, null));

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOfSatisfying(WalletEvent.WalletCredited.class,
                e -> assertThat(e.branchId()).isNull());
    }

    @Test
    @DisplayName("a non-recharge credit reason never touches the recharge table")
    void adjustmentIsNotARecharge() {
        service.creditForCustomer(new CustomerCreditCommand(CUSTOMER, new BigDecimal("10"), SubTransactionType.ADJ, null, null, "fix"));

        verify(rechargeOrders, never()).recordManualForCustomer(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a reason that cannot appear on a credit is refused and leaves nothing behind")
    void debitReasonRefused() {
        assertThatThrownBy(() -> service.creditForCustomer(new CustomerCreditCommand(CUSTOMER, new BigDecimal("10"),
                SubTransactionType.MDB, null, null, null))).isInstanceOf(BusinessRuleException.class);

        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("1000.00");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("crediting a customer that is not in the company changes nothing")
    void foreignCustomer() {
        when(customerWallets.getForCustomer(CUSTOMER)).thenThrow(new ResourceNotFoundException("Customer", CUSTOMER));

        assertThatThrownBy(() -> service.creditForCustomer(new CustomerCreditCommand(CUSTOMER, new BigDecimal("10"), null, null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(transactionRepository, never()).save(any());
        verify(rechargeOrders, never()).recordManualForCustomer(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a suspended customer wallet refuses a manual credit")
    void suspendedRefusesCredit() {
        wallet.setStatus(WalletStatus.SUSPENDED);

        assertThatThrownBy(() -> service.creditForCustomer(new CustomerCreditCommand(CUSTOMER, new BigDecimal("10"), null, null, null, null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("suspended");
        verify(transactionRepository, never()).save(any());
    }
}
