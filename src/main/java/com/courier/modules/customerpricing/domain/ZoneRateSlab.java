package com.courier.modules.customerpricing.domain;

import com.courier.shared.domain.CompanyOwnedEntity;
import com.courier.shared.exception.BusinessRuleException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import java.util.UUID;

/**
 * One per-kg weight slab of a {@link ZoneRate}, half-open {@code [minWeight, maxWeight)};
 * a {@code null} {@code maxWeight} has no upper bound (only the last slab may).
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "customer_zone_rate_slabs")
@Filter(name = CompanyOwnedEntity.COMPANY_FILTER, condition = "company_id = :companyId")
@SQLRestriction("deleted = false")
public class ZoneRateSlab extends CompanyOwnedEntity {

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "rate_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID rateId;

    @Column(name = "min_weight", nullable = false, precision = 12, scale = 3)
    private BigDecimal minWeight;

    @Column(name = "max_weight", precision = 12, scale = 3)
    private BigDecimal maxWeight;

    @Column(name = "rate_per_kg", nullable = false, precision = 12, scale = 2)
    private BigDecimal ratePerKg;

    /**
     * A slab covers the weights above its minimum up to and including its maximum, so with slabs 0-5 and 5-10 a 5 kg parcel is in the
     * first and 5.3 kg is in the second. A slab that starts at 0 also covers 0 itself. No maximum means no upper limit.
     */
    public boolean covers(BigDecimal weight) {
        boolean aboveMin = weight.compareTo(minWeight) > 0 || (minWeight.signum() == 0 && weight.signum() >= 0);
        return aboveMin && (maxWeight == null || weight.compareTo(maxWeight) <= 0);
    }

    public void applyInvariants() {
        if (minWeight == null || minWeight.signum() < 0) {
            throw new BusinessRuleException("Slab minimum weight must be zero or more.");
        }
        if (maxWeight != null && maxWeight.compareTo(minWeight) <= 0) {
            throw new BusinessRuleException("Slab maximum weight must be greater than its minimum.");
        }
        if (ratePerKg == null || ratePerKg.signum() < 0) {
            throw new BusinessRuleException("Slab rate per kg must be zero or more.");
        }
    }
}
