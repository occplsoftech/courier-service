package com.courier.modules.shipment.application;

import com.courier.modules.finance.application.WalletService;
import com.courier.modules.finance.application.payment.PaymentGatewayPort;
import com.courier.modules.finance.infrastructure.CompanyPaymentGatewayResolver;
import com.courier.modules.shipment.domain.Shipment;
import com.courier.modules.shipment.domain.ShipmentPayment;
import com.courier.modules.shipment.domain.ShipmentPaymentRepository;
import com.courier.modules.shipment.domain.ShipmentRepository;
import com.courier.modules.shipment.domain.ShipmentStatus;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShipmentPaymentServiceTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID SHIPMENT = UUID.randomUUID();
    private static final UUID BRANCH = UUID.randomUUID();

    private final ShipmentPaymentRepository repository = mock(ShipmentPaymentRepository.class);
    private final ShipmentRepository shipmentRepository = mock(ShipmentRepository.class);
    private final CompanyPaymentGatewayResolver resolver = mock(CompanyPaymentGatewayResolver.class);
    private final PaymentGatewayPort gateway = mock(PaymentGatewayPort.class);
    private final WalletService walletService = mock(WalletService.class);
    private final AuditService auditService = mock(AuditService.class);
    private ShipmentPaymentService service;

    @BeforeEach
    void setUp() {
        service = new ShipmentPaymentService(repository, shipmentRepository, resolver, walletService, auditService);
        CompanyContext.setCompanyId(COMPANY);
        when(resolver.resolve(COMPANY)).thenReturn(gateway);
        when(gateway.name()).thenReturn("RAZORPAY");
        when(repository.save(any(ShipmentPayment.class))).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        CompanyContext.clear();
    }

    private ShipmentPayment pending(String qrId, Instant closeBy) {
        ShipmentPayment p = new ShipmentPayment();
        p.setId(UUID.randomUUID());
        p.setCompanyId(COMPANY);
        p.setShipmentId(SHIPMENT);
        p.setShipmentNumber("TEST_BR-000009");
        p.setBookingBranchId(BRANCH);
        p.setMethod(ShipmentPayment.Method.RAZORPAY);
        p.setStatus(ShipmentPayment.Status.PENDING);
        p.setAmount(new BigDecimal("780.00"));
        p.setGatewayQrId(qrId);
        p.setQrImageUrl(qrId == null ? null : "https://rzp.io/i/" + qrId);
        p.setQrCloseBy(closeBy);
        return p;
    }

    private static PaymentGatewayPort.GatewayPayment payment(String id, String amount, boolean captured) {
        return new PaymentGatewayPort.GatewayPayment("RAZORPAY", id, null, new BigDecimal(amount), "INR", captured);
    }

    @Test
    @DisplayName("a pending Razorpay payment turns PAID once Razorpay reports a captured payment of the full amount")
    void paidWhenCaptured() {
        ShipmentPayment p = pending("qr_1", Instant.now().plusSeconds(600));
        when(repository.findByShipmentWithinCompany(SHIPMENT, COMPANY)).thenReturn(Optional.of(p));
        when(gateway.fetchQrPayments("qr_1")).thenReturn(List.of(payment("pay_1", "780.00", true)));

        ShipmentPayment result = service.status(SHIPMENT).orElseThrow();

        assertThat(result.getStatus()).isEqualTo(ShipmentPayment.Status.PAID);
        assertThat(result.getGatewayPaymentId()).isEqualTo("pay_1");
        assertThat(result.getPaidAmount()).isEqualByComparingTo("780.00");
    }

    @Test
    @DisplayName("an uncaptured or short payment leaves it pending")
    void staysPendingWhenNotEnough() {
        ShipmentPayment p = pending("qr_1", Instant.now().plusSeconds(600));
        when(repository.findByShipmentWithinCompany(SHIPMENT, COMPANY)).thenReturn(Optional.of(p));
        when(gateway.fetchQrPayments("qr_1")).thenReturn(List.of(payment("pay_1", "780.00", false),
                payment("pay_2", "100.00", true)));

        assertThat(service.status(SHIPMENT).orElseThrow().getStatus()).isEqualTo(ShipmentPayment.Status.PENDING);
    }

    @Test
    @DisplayName("the open QR is shown again; an expired one is closed and a new QR for the amount is opened")
    void opensNewQrWhenExpired() {
        ShipmentPayment open = pending("qr_open", Instant.now().plusSeconds(900));
        when(repository.findByShipmentWithinCompany(SHIPMENT, COMPANY)).thenReturn(Optional.of(open));
        Shipment shipment = mock(Shipment.class);
        when(shipment.getStatus()).thenReturn(ShipmentStatus.BOOKED);
        when(shipmentRepository.findById(SHIPMENT)).thenReturn(Optional.of(shipment));

        assertThat(service.openQr(SHIPMENT).getGatewayQrId()).isEqualTo("qr_open");
        verify(gateway, never()).createQr(any(), anyString(), anyString(), any(), anyMap());

        ShipmentPayment expired = pending("qr_old", Instant.now().minusSeconds(5));
        when(repository.findByShipmentWithinCompany(SHIPMENT, COMPANY)).thenReturn(Optional.of(expired));
        Instant closes = Instant.now().plusSeconds(1800);
        when(gateway.createQr(eq(new BigDecimal("780.00")), anyString(), anyString(), any(), anyMap()))
                .thenReturn(new PaymentGatewayPort.GatewayQr("RAZORPAY", "qr_new", "https://rzp.io/i/new", 78000, closes));

        ShipmentPayment renewed = service.openQr(SHIPMENT);

        verify(gateway).closeQr("qr_old");
        assertThat(renewed.getGatewayQrId()).isEqualTo("qr_new");
        assertThat(renewed.getQrCloseBy()).isEqualTo(closes);
    }

    @Test
    @DisplayName("a cash payment has no QR")
    void noQrForCash() {
        ShipmentPayment cash = pending(null, null);
        cash.setMethod(ShipmentPayment.Method.CASH);
        when(repository.findByShipmentWithinCompany(SHIPMENT, COMPANY)).thenReturn(Optional.of(cash));

        assertThatThrownBy(() -> service.openQr(SHIPMENT)).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("cash");
    }

    @Test
    @DisplayName("the webhook settles by asking Razorpay, not by trusting the webhook body")
    void webhookRechecks() {
        CompanyContext.clear();
        ShipmentPayment p = pending("qr_1", Instant.now().plusSeconds(600));
        when(repository.findFirstByGatewayQrId("qr_1")).thenReturn(Optional.of(p));
        when(gateway.fetchQrPayments("qr_1")).thenReturn(List.of(payment("pay_9", "780.00", true)));

        service.settleFromWebhook("qr_1");

        assertThat(p.getStatus()).isEqualTo(ShipmentPayment.Status.PAID);
        verify(gateway).fetchQrPayments("qr_1");
    }

    @Test
    @DisplayName("Razorpay booking refused when the company has no Razorpay keys")
    void refusedWithoutRazorpay() {
        when(gateway.name()).thenReturn("NONE");
        assertThatThrownBy(() -> service.requireRazorpayReady(COMPANY)).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Razorpay is not set up");
    }
}
