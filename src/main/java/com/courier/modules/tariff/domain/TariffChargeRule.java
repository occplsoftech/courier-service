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
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A charge a rate card adds on top of freight: fuel, hamali, docket, COD, FOV, ODA… The to zone, service type and package
 * type are optional filters. For each charge code only the most specific matching rule is used, so "FUEL 12% for all"
 * plus "FUEL 15% for PREMIUM" charges premium shipments 15% and the rest 12%.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "tariff_charge_rules")
@Filter(name = CompanyOwnedEntity.COMPANY_FILTER, condition = "company_id = :companyId")
@SQLRestriction("deleted = false")
public class TariffChargeRule extends CompanyOwnedEntity {

    private static final Pattern CODE = Pattern.compile("^[A-Z0-9_-]{2,30}$");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "rate_card_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID rateCardId;

    @Column(name = "charge_code", nullable = false, length = 30)
    private String chargeCode;

    @Column(name = "charge_name", nullable = false, length = 100)
    private String chargeName;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "to_zone_id", columnDefinition = "BINARY(16)")
    private UUID toZoneId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "service_type_id", columnDefinition = "BINARY(16)")
    private UUID serviceTypeId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "package_type_id", columnDefinition = "BINARY(16)")
    private UUID packageTypeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "applies_when", nullable = false, length = 15)
    @Builder.Default
    private ChargeAppliesWhen appliesWhen = ChargeAppliesWhen.ALWAYS;

    @Enumerated(EnumType.STRING)
    @Column(name = "calc_method", nullable = false, length = 20)
    private ChargeCalcMethod calcMethod;

    @Column(name = "charge_value", nullable = false, precision = 12, scale = 3)
    private BigDecimal chargeValue;

    @Column(name = "min_amount", precision = 12, scale = 2)
    private BigDecimal minAmount;

    @Column(name = "max_amount", precision = 12, scale = 2)
    private BigDecimal maxAmount;

    /** Whether GST is charged on this charge. */
    @Column(name = "taxable", nullable = false)
    @Builder.Default
    private boolean taxable = true;

    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private int displayOrder = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    @Builder.Default
    private TariffStatus status = TariffStatus.ACTIVE;

    public boolean isActive() {
        return status == TariffStatus.ACTIVE;
    }

    /** Whether this rule's filters accept the lane. */
    public boolean matches(UUID toZone, UUID serviceType, UUID packageType) {
        return (toZoneId == null || toZoneId.equals(toZone))
                && (serviceTypeId == null || serviceTypeId.equals(serviceType))
                && (packageTypeId == null || packageTypeId.equals(packageType));
    }

    /** Higher is more specific. A to zone outweighs a service type, which outweighs a package type. */
    public int specificity() {
        return (toZoneId != null ? 4 : 0) + (serviceTypeId != null ? 2 : 0) + (packageTypeId != null ? 1 : 0);
    }

    /** Same code and the same filters: two such rules would compete for the same shipments. */
    public boolean sameTarget(TariffChargeRule other) {
        return chargeCode.equals(other.chargeCode) && Objects.equals(toZoneId, other.toZoneId)
                && Objects.equals(serviceTypeId, other.serviceTypeId) && Objects.equals(packageTypeId, other.packageTypeId)
                && appliesWhen == other.appliesWhen;
    }

    public void applyInvariants() {
        this.chargeCode = chargeCode == null ? null : chargeCode.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "_");
        this.chargeName = chargeName == null ? null : chargeName.trim();
        if (chargeCode == null || !CODE.matcher(chargeCode).matches()) {
            throw new BusinessRuleException("A charge code is 2 to 30 letters, digits, '-' or '_' (for example FUEL or HAMALI).");
        }
        if (chargeName == null || chargeName.isBlank()) {
            throw new BusinessRuleException("A charge needs a name, as it is printed on the docket.");
        }
        if (calcMethod == null) {
            throw new BusinessRuleException("Choose how the charge is worked out.");
        }
        if (appliesWhen == null) {
            this.appliesWhen = ChargeAppliesWhen.ALWAYS;
        }
        if (chargeValue == null || chargeValue.signum() < 0) {
            throw new BusinessRuleException("The charge value cannot be empty or negative.");
        }
        boolean percent = calcMethod == ChargeCalcMethod.PERCENT_OF_FREIGHT || calcMethod == ChargeCalcMethod.PERCENT_OF_VALUE;
        if (percent && chargeValue.compareTo(HUNDRED) > 0) {
            throw new BusinessRuleException("A percentage charge cannot be above 100%.");
        }
        if ((minAmount != null && minAmount.signum() < 0) || (maxAmount != null && maxAmount.signum() < 0)) {
            throw new BusinessRuleException("The minimum and maximum cannot be negative.");
        }
        if (minAmount != null && maxAmount != null && maxAmount.compareTo(minAmount) < 0) {
            throw new BusinessRuleException("The maximum cannot be below the minimum.");
        }
        if (displayOrder < 0) {
            throw new BusinessRuleException("The display order cannot be negative.");
        }
    }
}
