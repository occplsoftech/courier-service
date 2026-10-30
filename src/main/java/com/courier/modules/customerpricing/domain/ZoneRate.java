package com.courier.modules.customerpricing.domain;

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
import java.util.UUID;

/**
 * The rate card of one pricing zone: header charges here, per-kg weight slabs in
 * {@link ZoneRateSlab}. At most one live rate per zone (service-enforced).
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "customer_zone_rates")
@Filter(name = CompanyOwnedEntity.COMPANY_FILTER, condition = "company_id = :companyId")
@SQLRestriction("deleted = false")
public class ZoneRate extends CompanyOwnedEntity {

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "zone_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID zoneId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "customer_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID customerId;

    /** The service type (AIR, SURFACE...) this card prices; null is the card for every service. */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "service_type_id", columnDefinition = "BINARY(16)", updatable = false)
    private UUID serviceTypeId;

    @Column(name = "min_chargeable_weight", nullable = false, precision = 12, scale = 3)
    @Builder.Default
    private BigDecimal minChargeableWeight = BigDecimal.ZERO;

    @Column(name = "docket_charge", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal docketCharge = BigDecimal.ZERO;

    @Column(name = "mathadi_charge", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal mathadiCharge = BigDecimal.ZERO;

    @Column(name = "pack_charge", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal packCharge = BigDecimal.ZERO;

    @Column(name = "oda_charge", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal odaCharge = BigDecimal.ZERO;

    @Column(name = "fuel_surcharge_percent", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal fuelSurchargePercent = BigDecimal.ZERO;

    @Column(name = "fov_percent", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal fovPercent = BigDecimal.ZERO;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ZoneStatus status = ZoneStatus.ACTIVE;

    public boolean isActive() {
        return status == ZoneStatus.ACTIVE;
    }

    public void activate() {
        this.status = ZoneStatus.ACTIVE;
    }

    public void deactivate() {
        this.status = ZoneStatus.INACTIVE;
    }

    public boolean coversDate(LocalDate date) {
        return (effectiveFrom == null || !date.isBefore(effectiveFrom))
                && (effectiveTo == null || !date.isAfter(effectiveTo));
    }

    public void applyInvariants() {
        requireNonNegative(minChargeableWeight, "Minimum chargeable weight");
        requireNonNegative(docketCharge, "Docket charge");
        requireNonNegative(mathadiCharge, "Mathadi charge");
        requireNonNegative(packCharge, "Pack charge");
        requireNonNegative(odaCharge, "ODA charge");
        requirePercent(fuelSurchargePercent, "Fuel surcharge %");
        requirePercent(fovPercent, "FOV %");
        if (effectiveFrom != null && effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw new BusinessRuleException("Effective-to date cannot be before the effective-from date.");
        }
    }

    private static void requireNonNegative(BigDecimal value, String label) {
        if (value == null || value.signum() < 0) {
            throw new BusinessRuleException(label + " must be zero or more.");
        }
    }

    private static void requirePercent(BigDecimal value, String label) {
        if (value == null || value.signum() < 0 || value.compareTo(new BigDecimal("100")) > 0) {
            throw new BusinessRuleException(label + " must be between 0 and 100.");
        }
    }
}
