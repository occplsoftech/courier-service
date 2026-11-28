package com.courier.modules.finance.domain;

import com.courier.shared.domain.CompanyOwnedEntity;
import com.courier.shared.exception.BusinessRuleException;
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
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Cash a branch user hands over (V191). The user submits it with a proof image; a branch manager, the branch's ACCOUNTS
 * desk or a company admin looks at the proof and {@linkplain #approve approves} or {@linkplain #reject rejects} it. Only
 * an approved handover settles the user's outstanding cash on the User Performance Report. What was submitted is never
 * edited. Moves nothing in the branch wallet.
 */
@Entity
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(
        name = "cash_handovers",
        indexes = {
                @Index(name = "idx_cash_handover_branch_date", columnList = "company_id, branch_id, handover_date"),
                @Index(name = "idx_cash_handover_branch_status", columnList = "company_id, branch_id, status"),
                @Index(name = "idx_cash_handover_user_date", columnList = "company_id, user_id, handover_date")
        })
// Repeated deliberately: Hibernate does not inherit @Filter from a @MappedSuperclass.
@Filter(name = CompanyOwnedEntity.COMPANY_FILTER, condition = "company_id = :companyId")
@SQLRestriction("deleted = false")
public class CashHandover extends CompanyOwnedEntity {

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "branch_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID branchId;

    /** The user handing the cash over; they submitted it. */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "user_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = Wallet.MONEY_SCALE)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "mode", nullable = false, updatable = false, length = 20)
    private CashHandoverMode mode;

    @Column(name = "reference", updatable = false, length = 100)
    private String reference;

    @Column(name = "remarks", updatable = false, length = 500)
    private String remarks;

    /** The business day (IST) the cash changed hands or was deposited. */
    @Column(name = "handover_date", nullable = false, updatable = false)
    private LocalDate handoverDate;

    @Column(name = "proof_image_url", nullable = false, updatable = false, length = 500)
    private String proofImageUrl;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 20)
    private CashHandoverStatus status = CashHandoverStatus.PENDING;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "decided_by", columnDefinition = "BINARY(16)")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_remarks", length = 500)
    private String decisionRemarks;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "voided_by", columnDefinition = "BINARY(16)")
    private UUID voidedBy;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "void_reason", length = 500)
    private String voidReason;

    public boolean isPending() {
        return status == CashHandoverStatus.PENDING;
    }

    /** Counts against the user's outstanding cash. */
    public boolean isApproved() {
        return status == CashHandoverStatus.APPROVED;
    }

    /** The receiver confirms the money arrived. Pending only. */
    public void approve(UUID by, Instant at, String note) {
        requirePending();
        this.status = CashHandoverStatus.APPROVED;
        this.decidedBy = by;
        this.decidedAt = at;
        this.decisionRemarks = note;
    }

    /** The receiver refuses it; a reason is required so the user knows what to fix. Pending only. */
    public void reject(UUID by, Instant at, String reason) {
        requirePending();
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("A reason is required to reject a handover.");
        }
        this.status = CashHandoverStatus.REJECTED;
        this.decidedBy = by;
        this.decidedAt = at;
        this.decisionRemarks = reason.strip();
    }

    /** Cancels an approved handover found to be wrong; the amount counts as outstanding again. */
    public void voidWith(UUID by, Instant at, String reason) {
        if (status != CashHandoverStatus.APPROVED) {
            throw new BusinessRuleException("Only an approved handover can be voided; a pending one is rejected instead.");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("A reason is required to void a handover.");
        }
        this.status = CashHandoverStatus.VOIDED;
        this.voidedBy = by;
        this.voidedAt = at;
        this.voidReason = reason.strip();
    }

    private void requirePending() {
        if (status != CashHandoverStatus.PENDING) {
            throw new BusinessRuleException("This handover has already been " + status.name().toLowerCase() + ".");
        }
    }
}
