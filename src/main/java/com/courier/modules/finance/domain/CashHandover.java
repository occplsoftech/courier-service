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
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Cash a branch user handed over (V191). Recorded by whoever received it — a branch manager, the
 * branch's ACCOUNTS desk or a company admin — and never edited afterwards: a mistake is
 * {@linkplain #voidWith voided} with a reason and recorded again. Moves nothing in the branch
 * wallet; it only settles the user's outstanding cash on the User Performance Report.
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
                @Index(name = "idx_cash_handover_user_date", columnList = "company_id, user_id, handover_date")
        })
// Repeated deliberately: Hibernate does not inherit @Filter from a @MappedSuperclass.
@Filter(name = CompanyOwnedEntity.COMPANY_FILTER, condition = "company_id = :companyId")
@SQLRestriction("deleted = false")
public class CashHandover extends CompanyOwnedEntity {

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "branch_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID branchId;

    /** The user who handed the cash over. */
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

    /** The business day (IST) the cash changed hands. */
    @Column(name = "handover_date", nullable = false, updatable = false)
    private LocalDate handoverDate;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "received_by", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID receivedBy;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Builder.Default
    @Column(name = "voided", nullable = false)
    private boolean voided = false;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "voided_by", columnDefinition = "BINARY(16)")
    private UUID voidedBy;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "void_reason", length = 500)
    private String voidReason;

    /** Cancels a handover recorded by mistake. Once only; the amount counts as outstanding again. */
    public void voidWith(UUID by, Instant at, String reason) {
        if (voided) {
            throw new com.courier.shared.exception.BusinessRuleException("This handover is already voided.");
        }
        if (reason == null || reason.isBlank()) {
            throw new com.courier.shared.exception.BusinessRuleException("A reason is required to void a handover.");
        }
        this.voided = true;
        this.voidedBy = by;
        this.voidedAt = at;
        this.voidReason = reason.strip();
    }
}
