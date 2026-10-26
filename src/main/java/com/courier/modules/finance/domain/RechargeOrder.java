package com.courier.modules.finance.domain;

import com.courier.shared.domain.CompanyOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One online recharge attempt. Written <em>before</em> the gateway order exists (so the
 * attempt always has our own {@link #transactionNo}, which is also the gateway receipt),
 * then moved to {@code SUCCESS}/{@code FAILED} by the browser confirmation, the gateway
 * webhook or {@code RechargeReconciliationJob}. Never a ledger row — see V102. {@code
 * FAILED} is not terminal: a payment captured afterwards still settles the order.
 */
@Entity
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(
        name = "wallet_recharge_orders",
        indexes = {
                @Index(name = "idx_recharge_orders_gateway_order", columnList = "gateway_order_id"),
                @Index(name = "idx_recharge_orders_status_created", columnList = "status, created_at")
        })
// Repeated deliberately: Hibernate does not inherit @Filter from a @MappedSuperclass.
@Filter(name = CompanyOwnedEntity.COMPANY_FILTER, condition = "company_id = :companyId")
@SQLRestriction("deleted = false")
public class RechargeOrder extends CompanyOwnedEntity {

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "wallet_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID walletId;

    /** The branch the recharge is for. Null when it is a customer's (exactly one owner, see V137). */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "branch_id", columnDefinition = "BINARY(16)", updatable = false)
    private UUID branchId;

    /** The customer the recharge is for. Null when it is a branch's. */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "customer_id", columnDefinition = "BINARY(16)", updatable = false)
    private UUID customerId;

    @Column(name = "transaction_no", nullable = false, updatable = false, length = 40)
    private String transactionNo;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = Wallet.MONEY_SCALE)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "mode", nullable = false, updatable = false, length = 10)
    private RechargeMode mode;

    @Column(name = "gateway", nullable = false, updatable = false, length = 30)
    private String gateway;

    @Setter
    @Column(name = "remarks", length = 500)
    private String remarks;

    @Setter
    @Column(name = "gateway_order_id", length = 100)
    private String gatewayOrderId;

    @Setter
    @Column(name = "gateway_payment_id", length = 100)
    private String gatewayPaymentId;

    @Setter
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Setter
    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Setter
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "ledger_transaction_id", columnDefinition = "BINARY(16)")
    private UUID ledgerTransactionId;

    @Setter
    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    @Setter
    @Column(name = "settled_at")
    private Instant settledAt;

    /** Razorpay settlement (payout batch) this payment was paid out in; null until it is. */
    @Setter
    @Column(name = "bank_settlement_id", length = 100)
    private String bankSettlementId;

    /** Bank reference (UTR) of that payout — what to match against the bank statement. */
    @Setter
    @Column(name = "bank_settlement_utr", length = 100)
    private String bankSettlementUtr;

    @Setter
    @Column(name = "bank_settled_at")
    private Instant bankSettledAt;

    @Setter
    @Column(name = "settlement_last_checked_at")
    private Instant settlementLastCheckedAt;

    public boolean isBankSettled() {
        return bankSettlementId != null;
    }

    public boolean isPending() {
        return status == PaymentStatus.PENDING;
    }
}
