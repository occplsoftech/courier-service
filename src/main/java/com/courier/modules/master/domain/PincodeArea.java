package com.courier.modules.master.domain;

import com.courier.shared.domain.CompanyOwnedEntity;
import com.courier.shared.exception.BusinessRuleException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One Area a pincode's postal record names — a pincode routinely maps to several real
 * post offices/localities, and this is what keeps every one of them, not just the single
 * {@code master_pincodes.area_id} it primarily routes to.
 *
 * <p>ODA (Out-of-Delivery-Area) and its surcharge live here, per (pincode, area), because
 * that is genuinely how it varies in the real world — one locality sharing a pincode with
 * another can be ODA when the other is not.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(
        name = "master_pincode_areas",
        uniqueConstraints = @UniqueConstraint(name = "uk_pincode_area",
                columnNames = {"company_id", "pincode_id", "area_id"}),
        indexes = @Index(name = "idx_pincode_area_pincode", columnList = "company_id, pincode_id"))
@Filter(name = CompanyOwnedEntity.COMPANY_FILTER, condition = "company_id = :companyId")
@SQLRestriction("deleted = false")
public class PincodeArea extends CompanyOwnedEntity {

    /** The default an operator most likely means by "yes, this is ODA" until they say
     *  otherwise — applied only when {@link #odaApplicable} turns true with no amount
     *  already given. */
    public static final BigDecimal DEFAULT_ODA_AMOUNT = new BigDecimal("250.00");

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "pincode_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID pincodeId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "area_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID areaId;

    /** The one row matching this pincode's own {@code area_id} — kept in sync by
     *  {@code PincodeAreaService}, not derived. */
    @Column(name = "is_primary", nullable = false)
    private boolean primary = false;

    /** This area can be booked to (the pincode itself must be serviceable too). */
    @Column(name = "serviceable", nullable = false)
    private boolean serviceable = true;

    @Column(name = "oda_applicable", nullable = false)
    private boolean odaApplicable = false;

    @Column(name = "oda_amount", precision = 10, scale = 2)
    private BigDecimal odaAmount;

    public void applyInvariants() {
        if (pincodeId == null) {
            throw new BusinessRuleException("A pincode-area link needs a pincode.");
        }
        if (areaId == null) {
            throw new BusinessRuleException("A pincode-area link needs an area.");
        }
        if (!odaApplicable) {
            this.odaAmount = null;
        } else if (odaAmount == null) {
            this.odaAmount = DEFAULT_ODA_AMOUNT;
        } else if (odaAmount.signum() < 0) {
            throw new BusinessRuleException("ODA amount cannot be negative.");
        }
    }
}
