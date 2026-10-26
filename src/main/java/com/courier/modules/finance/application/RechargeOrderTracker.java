package com.courier.modules.finance.application;

import com.courier.modules.finance.domain.PaymentStatus;
import com.courier.modules.finance.domain.RechargeMode;
import com.courier.modules.finance.domain.RechargeOrder;
import com.courier.modules.finance.domain.RechargeOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Keeps the {@link RechargeOrder} row of an online recharge in step with what the gateway
 * says. The "open" writes ({@link #begin}, {@link #attachGatewayOrder}, {@link #markFailed})
 * run in their own transaction so the attempt is on record even when the gateway call that
 * follows throws and rolls the caller back; {@link #markSuccess} joins the caller's
 * transaction so the status flips together with the wallet credit.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RechargeOrderTracker {

    private static final int REASON_MAX = 255;

    private final RechargeOrderRepository repository;

    /** Step 1 — before the gateway is called. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RechargeOrder begin(UUID companyId, UUID walletId, UUID branchId, BigDecimal amount,
                               String currency, String gateway, String transactionNo) {
        RechargeOrder order = RechargeOrder.builder()
                .walletId(walletId).branchId(branchId).transactionNo(transactionNo)
                .amount(amount).currency(currency).mode(RechargeMode.ONLINE).gateway(gateway)
                .status(PaymentStatus.PENDING).build();
        order.setCompanyId(companyId);
        return repository.save(order);
    }

    /** Step 1 for a customer's recharge — same as {@link #begin} with the customer as owner. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RechargeOrder beginForCustomer(UUID companyId, UUID walletId, UUID customerId, BigDecimal amount,
                                          String currency, String gateway, String transactionNo) {
        RechargeOrder order = RechargeOrder.builder()
                .walletId(walletId).customerId(customerId).transactionNo(transactionNo)
                .amount(amount).currency(currency).mode(RechargeMode.ONLINE).gateway(gateway)
                .status(PaymentStatus.PENDING).build();
        order.setCompanyId(companyId);
        return repository.save(order);
    }

    /** A manual recharge of a customer's wallet: born {@code SUCCESS}, like {@link #recordManual}. */
    @Transactional
    public RechargeOrder recordManualForCustomer(UUID companyId, UUID walletId, UUID customerId,
                                                 BigDecimal amount, String currency, String transactionNo,
                                                 String remarks) {
        RechargeOrder order = RechargeOrder.builder()
                .walletId(walletId).customerId(customerId).transactionNo(transactionNo)
                .amount(amount).currency(currency).mode(RechargeMode.MANUAL).gateway("MANUAL")
                .status(PaymentStatus.SUCCESS).remarks(remarks).settledAt(Instant.now()).build();
        order.setCompanyId(companyId);
        return repository.save(order);
    }

    /** A manual recharge: no gateway, so the row is born {@code SUCCESS}. Joins the caller's
     *  transaction — the recharge row and the ledger entry it leads to commit together. */
    @Transactional
    public RechargeOrder recordManual(UUID companyId, UUID walletId, UUID branchId, BigDecimal amount,
                                      String currency, String transactionNo, String remarks) {
        RechargeOrder order = RechargeOrder.builder()
                .walletId(walletId).branchId(branchId).transactionNo(transactionNo)
                .amount(amount).currency(currency).mode(RechargeMode.MANUAL).gateway("MANUAL")
                .status(PaymentStatus.SUCCESS).remarks(remarks).settledAt(Instant.now()).build();
        order.setCompanyId(companyId);
        return repository.save(order);
    }

    /** Points a recharge at the ledger entry it created. */
    @Transactional
    public void linkLedger(UUID id, UUID ledgerTransactionId) {
        repository.findById(id).ifPresent(o -> {
            o.setLedgerTransactionId(ledgerTransactionId);
            repository.save(o);
        });
    }

    /** Step 2 — the gateway accepted the order. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void attachGatewayOrder(UUID id, String gatewayOrderId) {
        repository.findById(id).ifPresent(o -> {
            o.setGatewayOrderId(gatewayOrderId);
            repository.save(o);
        });
    }

    /** The attempt failed before or at the gateway (own transaction — survives a rollback). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID id, String reason) {
        repository.findById(id).ifPresent(o -> fail(o, reason));
    }

    /** Joins the caller's transaction: flips to SUCCESS with the wallet credit. A FAILED order
     *  becomes SUCCESS when its payment turns out to have been captured after all. */
    @Transactional
    public void markSuccess(UUID companyId, String gatewayOrderId, String paymentId, UUID ledgerTransactionId) {
        Optional<RechargeOrder> found = repository.findByGatewayOrderIdWithinCompany(gatewayOrderId, companyId);
        if (found.isEmpty()) {
            log.info("No recharge order recorded for gateway order {} (pre-V102 attempt) — nothing to mark", gatewayOrderId);
            return;
        }
        RechargeOrder o = found.get();
        o.setStatus(PaymentStatus.SUCCESS);
        o.setGatewayPaymentId(paymentId);
        o.setLedgerTransactionId(ledgerTransactionId);
        o.setFailureReason(null);
        o.setSettledAt(Instant.now());
        repository.save(o);
    }

    /** The gateway reported a failed payment on this order (webhook / scheduler). A SUCCESS
     *  row is never downgraded. */
    @Transactional
    public void markFailedByGatewayOrder(UUID companyId, String gatewayOrderId, String reason) {
        repository.findByGatewayOrderIdWithinCompany(gatewayOrderId, companyId).ifPresent(o -> fail(o, reason));
    }

    @Transactional
    public void touch(UUID id) {
        repository.findById(id).ifPresent(o -> {
            o.setLastCheckedAt(Instant.now());
            repository.save(o);
        });
    }

    private void fail(RechargeOrder o, String reason) {
        if (o.getStatus() == PaymentStatus.SUCCESS) {
            return;
        }
        o.setStatus(PaymentStatus.FAILED);
        o.setFailureReason(reason == null ? null
                : reason.length() > REASON_MAX ? reason.substring(0, REASON_MAX) : reason);
        o.setLastCheckedAt(Instant.now());
        repository.save(o);
    }
}
