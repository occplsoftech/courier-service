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
 * A branch's ask for money, distinct from a recharge: recharge is the branch paying the
 * platform (Razorpay) directly; a top-up request is the branch asking its own company
 * admin to fund the wallet, with no payment attached to the request itself. Nothing here
 * moves a balance — {@link #status} tracks the ask, and approval calls {@code
 * WalletService.credit(CreditCommand)} exactly like any other manual credit, so the
 * balance/ledger pair is written through the module's one existing money path, not a
 * second one.
 *
 * <p>{@code PENDING} -&gt; {@code APPROVED} or {@code REJECTED}, terminal either way.
 */
@Entity
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(
        name = "wallet_topup_requests",
        indexes = {
                @Index(name = "idx_topup_req_branch_status", columnList = "company_id, branch_id, status"),
                @Index(name = "idx_topup_req_company_status", columnList = "company_id, status, created_at")
        })
// Repeated deliberately: Hibernate does not inherit @Filter from a @MappedSuperclass.
@Filter(name = CompanyOwnedEntity.COMPANY_FILTER, condition = "company_id = :companyId")
@SQLRestriction("deleted = false")
public class WalletTopupRequest extends CompanyOwnedEntity {

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "wallet_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID walletId;

    /** The branch asking. Null when the ask is a customer's (exactly one owner, see V137). */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "branch_id", columnDefinition = "BINARY(16)", updatable = false)
    private UUID branchId;

    /** The customer asking. Null when the ask is a branch's. */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "customer_id", columnDefinition = "BINARY(16)", updatable = false)
    private UUID customerId;

    @Column(name = "requested_amount", nullable = false, updatable = false, precision = 19,
            scale = Wallet.MONEY_SCALE)
    private BigDecimal requestedAmount;

    @Column(name = "remarks", updatable = false, length = 500)
    private String remarks;

    /** Proof of payment/cash handover the branch attaches to the ask. Mandatory on every
     *  new request (enforced in {@code CreateTopupRequestRequest}/the service layer, not
     *  the column itself — nullable here only so requests raised before this feature
     *  existed stay valid). */
    @Column(name = "proof_image_url", updatable = false, length = 500)
    private String proofImageUrl;

    @Setter
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 20)
    private TopupRequestStatus status;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "requested_by", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID requestedBy;

    @Setter
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "decided_by", columnDefinition = "BINARY(16)")
    private UUID decidedBy;

    @Setter
    @Column(name = "decided_at")
    private Instant decidedAt;

    @Setter
    @Column(name = "decision_remarks", length = 500)
    private String decisionRemarks;

    /** The ledger entry approval created — null until {@code APPROVED}. */
    @Setter
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "transaction_id", columnDefinition = "BINARY(16)")
    private UUID transactionId;

    public boolean isPending() {
        return status == TopupRequestStatus.PENDING;
    }
}
