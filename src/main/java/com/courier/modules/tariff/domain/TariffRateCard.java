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
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A company rate card (V139): the prices for shipments that start in one FROM zone, valid between two dates. Its weight
 * slabs ({@link TariffRateSlab}) price each to zone × service type × package type; its charge rules
 * ({@link TariffChargeRule}) add fuel, hamali, docket and the like; GST and rounding are set here. Only one ACTIVE card
 * per from zone may cover a given date (checked in the service).
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "tariff_rate_cards")
@Filter(name = CompanyOwnedEntity.COMPANY_FILTER, condition = "company_id = :companyId")
@SQLRestriction("deleted = false")
public class TariffRateCard extends CompanyOwnedEntity {

    private static final Pattern CODE = Pattern.compile("^[A-Z0-9_-]{2,30}$");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    @Column(name = "code", nullable = false, length = 30)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "from_zone_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID fromZoneId;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    /** Inclusive; null means open-ended. */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "gst_percent", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal gstPercent = new BigDecimal("18.00");

    /** L × W × H (cm) divided by this gives volumetric kg. Null: this card prices on actual weight only. */
    @Column(name = "volumetric_divisor")
    private Integer volumetricDivisor;

    @Enumerated(EnumType.STRING)
    @Column(name = "total_rounding", nullable = false, length = 15)
    @Builder.Default
    private TotalRounding totalRounding = TotalRounding.NEAREST_RUPEE;

    @Column(name = "description", length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    @Builder.Default
    private TariffStatus status = TariffStatus.INACTIVE;

    public boolean isActive() {
        return status == TariffStatus.ACTIVE;
    }

    public void activate() {
        this.status = TariffStatus.ACTIVE;
    }

    public void deactivate() {
        this.status = TariffStatus.INACTIVE;
    }

    public boolean covers(LocalDate date) {
        return !date.isBefore(effectiveFrom) && (effectiveTo == null || !date.isAfter(effectiveTo));
    }

    /** True when both cards are valid on at least one common day. */
    public boolean overlaps(TariffRateCard other) {
        boolean thisEndsBefore = effectiveTo != null && effectiveTo.isBefore(other.effectiveFrom);
        boolean otherEndsBefore = other.effectiveTo != null && other.effectiveTo.isBefore(effectiveFrom);
        return !thisEndsBefore && !otherEndsBefore;
    }

    public void applyInvariants() {
        this.code = code == null ? null : code.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "_");
        this.name = name == null ? null : name.trim();
        this.description = description == null || description.isBlank() ? null : description.trim();
        if (code == null || !CODE.matcher(code).matches()) {
            throw new BusinessRuleException("A rate card code is 2 to 30 letters, digits, '-' or '_' (for example PUNE_CASH_2025).");
        }
        if (name == null || name.isBlank()) {
            throw new BusinessRuleException("A rate card needs a name.");
        }
        if (fromZoneId == null) {
            throw new BusinessRuleException("Choose the from zone this rate card prices.");
        }
        if (effectiveFrom == null) {
            throw new BusinessRuleException("Enter the date the rate card starts.");
        }
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw new BusinessRuleException("The end date cannot be before the start date.");
        }
        if (gstPercent == null || gstPercent.signum() < 0 || gstPercent.compareTo(HUNDRED) > 0) {
            throw new BusinessRuleException("GST is a percentage from 0 to 100.");
        }
        if (volumetricDivisor != null && volumetricDivisor <= 0) {
            throw new BusinessRuleException("The volumetric divisor must be above 0 (commonly 5000), or left empty.");
        }
        if (totalRounding == null) {
            this.totalRounding = TotalRounding.NEAREST_RUPEE;
        }
    }
}
