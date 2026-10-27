package com.courier.modules.tariff.domain;

import com.courier.shared.domain.CompanyOwnedEntity;
import com.courier.shared.exception.BusinessRuleException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * One weight band of a rate card's lane (to zone × service type × package type), from the sheet's rows such as
 * "PUNE → CENTRAL, BY AIR, Document, 0.501–1 kg, 200, Fixed" and "1.001–99 kg, 200, Per Kg". A lane's slabs are saved
 * together and checked together ({@link #validateLane}).
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "tariff_rate_slabs")
@Filter(name = CompanyOwnedEntity.COMPANY_FILTER, condition = "company_id = :companyId")
@SQLRestriction("deleted = false")
public class TariffRateSlab extends CompanyOwnedEntity {

    /** Largest gap allowed between one slab's upper weight and the next one's lower weight (the sheet's 0.25 → 0.251). */
    static final BigDecimal MAX_GAP_KG = new BigDecimal("0.001");

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "rate_card_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID rateCardId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "to_zone_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID toZoneId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "service_type_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID serviceTypeId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "package_type_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID packageTypeId;

    @Column(name = "weight_from_kg", nullable = false, precision = 10, scale = 3)
    private BigDecimal weightFromKg;

    /** Inclusive upper weight. */
    @Column(name = "weight_to_kg", nullable = false, precision = 10, scale = 3)
    private BigDecimal weightToKg;

    @Enumerated(EnumType.STRING)
    @Column(name = "rate_type", nullable = false, length = 15)
    private SlabRateType rateType;

    @Column(name = "rate", nullable = false, precision = 12, scale = 2)
    private BigDecimal rate;

    /** PER_EXTRA_KG only: the weight each {@code rate} buys, 1 kg unless set otherwise. Null for FIXED. */
    @Column(name = "step_kg", precision = 6, scale = 3)
    private BigDecimal stepKg;

    public boolean isFixed() {
        return rateType == SlabRateType.FIXED;
    }

    public void applyInvariants() {
        if (rateType == null) {
            throw new BusinessRuleException("Each slab is FIXED or PER_EXTRA_KG.");
        }
        if (weightFromKg == null || weightToKg == null || weightFromKg.signum() < 0) {
            throw new BusinessRuleException("Each slab needs a from and to weight (kg), from 0 upwards.");
        }
        if (weightToKg.compareTo(weightFromKg) <= 0) {
            throw new BusinessRuleException("A slab's to weight must be above its from weight (%s–%s kg)."
                    .formatted(weightFromKg.stripTrailingZeros().toPlainString(), weightToKg.stripTrailingZeros().toPlainString()));
        }
        if (rate == null || rate.signum() < 0) {
            throw new BusinessRuleException("A slab's rate cannot be empty or negative.");
        }
        if (isFixed()) {
            this.stepKg = null;
        } else if (stepKg == null) {
            this.stepKg = BigDecimal.ONE;
        } else if (stepKg.signum() <= 0) {
            throw new BusinessRuleException("The extra weight step must be above 0 kg.");
        }
    }

    /**
     * Checks one lane's slabs together: each slab valid, sorted by weight, no overlap, no gap above 1 g, and every
     * PER_EXTRA_KG slab has a FIXED slab before it to start from. Returns them sorted.
     */
    public static List<TariffRateSlab> validateLane(List<TariffRateSlab> slabs) {
        if (slabs == null || slabs.isEmpty()) {
            throw new BusinessRuleException("A lane needs at least one weight slab.");
        }
        slabs.forEach(TariffRateSlab::applyInvariants);
        List<TariffRateSlab> sorted = slabs.stream().sorted(Comparator.comparing(TariffRateSlab::getWeightFromKg)).toList();
        if (!sorted.get(0).isFixed()) {
            throw new BusinessRuleException("The lightest slab must be FIXED: a per-extra-kg slab adds to the fixed slab before it.");
        }
        for (int i = 1; i < sorted.size(); i++) {
            TariffRateSlab prev = sorted.get(i - 1);
            TariffRateSlab next = sorted.get(i);
            if (next.weightFromKg.compareTo(prev.weightToKg) <= 0) {
                throw new BusinessRuleException("Slabs %s and %s overlap.".formatted(prev.band(), next.band()));
            }
            if (next.weightFromKg.subtract(prev.weightToKg).compareTo(MAX_GAP_KG) > 0) {
                throw new BusinessRuleException("There is a gap between slabs %s and %s; weights in it would have no price."
                        .formatted(prev.band(), next.band()));
            }
        }
        return sorted;
    }

    /** "0.501–1 kg". */
    public String band() {
        return weightFromKg.stripTrailingZeros().toPlainString() + "–" + weightToKg.stripTrailingZeros().toPlainString() + " kg";
    }
}
