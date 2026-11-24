package com.courier.modules.shipment.application;

import com.courier.modules.finance.application.WalletService;
import com.courier.modules.finance.application.payment.PaymentGatewayPort;
import com.courier.modules.finance.infrastructure.CompanyPaymentGatewayResolver;
import com.courier.modules.shipment.domain.Shipment;
import com.courier.modules.shipment.domain.ShipmentPayment;
import com.courier.modules.shipment.domain.ShipmentPaymentRepository;
import com.courier.modules.shipment.domain.ShipmentRepository;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.audit.domain.AuditAction;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * How the customer pays a Paid booking at the counter: Razorpay QR, cash, or the company's UPI QR.
 *
 * <p>Razorpay: the shipment is booked with the payment PENDING and the branch wallet untouched. {@link #openQr}
 * opens a single-use Razorpay UPI QR for exactly the shipment's final amount; the booking screen shows it and asks
 * {@link #status} every few seconds, which asks Razorpay for the QR's payments, so the payment turns PAID even when
 * the webhook ({@link #settleFromWebhook}, event {@code qr_code.credited}) is not configured. Only captured payments
 * reported by Razorpay itself count, and only when they add up to the amount.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShipmentPaymentService {

    /** How long a QR can be paid; a new one is opened after that. */
    static final Duration QR_LIFETIME = Duration.ofMinutes(30);
    private static final String ENTITY = "ShipmentPayment";

    private final ShipmentPaymentRepository repository;
    private final ShipmentRepository shipmentRepository;
    private final CompanyPaymentGatewayResolver gatewayResolver;
    private final WalletService walletService;
    private final AuditService auditService;

    /** Refuses a Razorpay booking before anything is created when the company has no Razorpay keys. */
    public void requireRazorpayReady(UUID companyId) {
        if (!"RAZORPAY".equals(gatewayResolver.resolve(companyId).name())) {
            throw new BusinessRuleException("Razorpay is not set up for this company. Ask the company admin to add "
                    + "the Razorpay keys in Settings, or take the payment in cash or by QR.");
        }
    }

    /** Called inside the booking transaction for a Paid booking. */
    @Transactional(propagation = Propagation.MANDATORY)
    public ShipmentPayment recordAtBooking(Shipment shipment, ShipmentPayment.Method method, String reference,
                                           BigDecimal amount) {
        ShipmentPayment p = new ShipmentPayment();
        p.setShipmentId(shipment.getId());
        p.setShipmentNumber(shipment.getShipmentNumber());
        p.setBookingBranchId(shipment.getBookingBranchId());
        p.setMethod(method);
        p.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
        if (method == ShipmentPayment.Method.RAZORPAY) {
            p.setStatus(ShipmentPayment.Status.PENDING);
        } else {
            p.setStatus(ShipmentPayment.Status.PAID);
            p.setPaidAmount(p.getAmount());
            p.setPaidAt(Instant.now());
            String ref = reference == null ? null : reference.trim();
            p.setReference(ref == null || ref.isEmpty() ? null : ref.length() > 100 ? ref.substring(0, 100) : ref);
        }
        return repository.save(p);
    }

    @Transactional(readOnly = true)
    public boolean paidByRazorpay(UUID shipmentId, UUID companyId) {
        return repository.findByShipmentWithinCompany(shipmentId, companyId)
                .map(p -> p.getMethod() == ShipmentPayment.Method.RAZORPAY).orElse(false);
    }

    /** The shipment's payment, refreshed from Razorpay while pending. Empty for a booking with no record. */
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public Optional<ShipmentPayment> status(UUID shipmentId) {
        UUID companyId = CompanyContext.requireCompanyId();
        Optional<ShipmentPayment> found = repository.findByShipmentWithinCompany(shipmentId, companyId);
        found.ifPresent(p -> {
            requireBranchAccess(p);
            refresh(p, companyId);
        });
        return found;
    }

    /**
     * The QR to show: the open one while it can still be paid, else a new single-use QR for the shipment's amount.
     */
    @Transactional
    @PreAuthorize("hasAuthority('SHIPMENT_CREATE')")
    public ShipmentPayment openQr(UUID shipmentId) {
        UUID companyId = CompanyContext.requireCompanyId();
        ShipmentPayment p = repository.findByShipmentWithinCompany(shipmentId, companyId)
                .orElseThrow(() -> new BusinessRuleException("This shipment was not booked for a Razorpay payment."));
        requireBranchAccess(p);
        if (p.getMethod() != ShipmentPayment.Method.RAZORPAY) {
            throw new BusinessRuleException("Shipment %s was paid by %s.".formatted(p.getShipmentNumber(),
                    p.getMethod() == ShipmentPayment.Method.CASH ? "cash" : "UPI QR"));
        }
        refresh(p, companyId);
        if (p.isPaid()) {
            return p;
        }
        Shipment shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment", shipmentId));
        if (shipment.getStatus() != null && "CANCELLED".equals(shipment.getStatus().name())) {
            throw new BusinessRuleException("Shipment %s is cancelled.".formatted(p.getShipmentNumber()));
        }
        Instant now = Instant.now();
        if (p.getGatewayQrId() != null && p.getQrCloseBy() != null && p.getQrCloseBy().isAfter(now.plusSeconds(60))) {
            return p;
        }
        PaymentGatewayPort gateway = gatewayResolver.resolve(companyId);
        if (p.getGatewayQrId() != null) {
            gateway.closeQr(p.getGatewayQrId());
        }
        PaymentGatewayPort.GatewayQr qr = gateway.createQr(p.getAmount(), "Shipment " + p.getShipmentNumber(),
                "Courier freight for shipment " + p.getShipmentNumber(), now.plus(QR_LIFETIME),
                Map.of("company", companyId.toString(), "shipmentPayment", p.getId().toString(),
                        "shipmentNumber", p.getShipmentNumber()));
        p.setGatewayQrId(qr.qrId());
        p.setQrImageUrl(qr.imageUrl());
        p.setQrCloseBy(qr.closeBy());
        log.info("Razorpay QR {} opened for shipment {} ({})", qr.qrId(), p.getShipmentNumber(), p.getAmount());
        return repository.save(p);
    }

    /** {@code qr_code.credited}: re-checks the QR's payments with Razorpay itself before marking PAID. */
    @org.springframework.context.event.EventListener
    @Transactional
    public void on(com.courier.modules.finance.application.payment.RazorpayQrCredited event) {
        settleFromWebhook(event.qrId());
    }

    @Transactional
    public void settleFromWebhook(String qrId) {
        repository.findFirstByGatewayQrId(qrId).ifPresentOrElse(
                p -> CompanyContext.runAs(p.getCompanyId(), () -> refresh(p, p.getCompanyId())),
                () -> log.debug("Razorpay QR {} is not a shipment payment; ignored", qrId));
    }

    /** Payments for the list: PENDING ones are those still to follow up. A branch user sees their branch only. */
    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public Page<ShipmentPayment> search(ShipmentPayment.Status status, UUID branchId, Pageable pageable) {
        UUID companyId = CompanyContext.requireCompanyId();
        UUID scoped = isAdmin() && branchId == null ? null : walletService.getForBranch(branchId).getBranchId();
        return repository.search(companyId, status, scoped, pageable);
    }

    /** When the shipment is cancelled while its Razorpay payment is pending, the QR is closed. */
    @Transactional
    public void onShipmentCancelled(UUID shipmentId, UUID companyId) {
        repository.findByShipmentWithinCompany(shipmentId, companyId)
                .filter(p -> !p.isPaid() && p.getGatewayQrId() != null)
                .ifPresent(p -> {
                    gatewayResolver.resolve(companyId).closeQr(p.getGatewayQrId());
                    p.setQrCloseBy(Instant.now());
                    repository.save(p);
                });
    }

    // ------------------------------------------------------------------ internals

    private void refresh(ShipmentPayment p, UUID companyId) {
        if (p.isPaid() || p.getMethod() != ShipmentPayment.Method.RAZORPAY || p.getGatewayQrId() == null) {
            return;
        }
        List<PaymentGatewayPort.GatewayPayment> payments;
        try {
            payments = gatewayResolver.resolve(companyId).fetchQrPayments(p.getGatewayQrId());
        } catch (BusinessRuleException e) {
            log.warn("Could not ask Razorpay about QR {} of shipment {}: {}", p.getGatewayQrId(),
                    p.getShipmentNumber(), e.getMessage());
            return;
        }
        List<PaymentGatewayPort.GatewayPayment> captured = payments.stream()
                .filter(PaymentGatewayPort.GatewayPayment::captured).toList();
        BigDecimal received = captured.stream().map(PaymentGatewayPort.GatewayPayment::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (captured.isEmpty() || received.compareTo(p.getAmount()) < 0) {
            return;
        }
        p.setStatus(ShipmentPayment.Status.PAID);
        p.setPaidAmount(received);
        p.setPaidAt(Instant.now());
        p.setGatewayPaymentId(captured.get(0).paymentId());
        p.setReference(captured.get(0).paymentId());
        repository.save(p);
        log.info("Shipment {} paid by Razorpay: {} ({})", p.getShipmentNumber(), received, p.getGatewayPaymentId());
        auditService.record(AuditAction.SHIPMENT_PAYMENT_RECEIVED, ENTITY, p.getId(),
                Map.of("shipmentNumber", p.getShipmentNumber(), "amount", received.toPlainString(),
                        "paymentId", p.getGatewayPaymentId()));
    }

    /** A branch user may only see their own branch's payments (the wallet lookup enforces that). */
    private void requireBranchAccess(ShipmentPayment p) {
        walletService.getForBranch(p.getBookingBranchId());
    }

    private static boolean isAdmin() {
        return com.courier.shared.security.SecurityUtils.getCurrentUser()
                .map(u -> u.hasRole(com.courier.shared.security.Roles.COMPANY_ADMIN) || u.isSuperAdmin())
                .orElse(false);
    }
}
