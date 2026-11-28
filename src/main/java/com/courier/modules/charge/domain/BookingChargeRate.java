package com.courier.modules.charge.domain;

import com.courier.shared.domain.CompanyOwnedEntity;
import com.courier.shared.exception.BusinessRuleException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * One Charges Master row (V192): the company's own value for a charge ({@code customerId} null), or a business customer's
 * override of it. A customer without an override pays the company's value.
 */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "booking_charge_rates")
// Repeated deliberately: Hibernate does not inherit @Filter from a @MappedSuperclass.
@Filter(name = CompanyOwnedEntity.COMPANY_FILTER, condition = "company_id = :companyId")
@SQLRestriction("deleted = false")
public class BookingChargeRate extends CompanyOwnedEntity {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "customer_id", columnDefinition = "BINARY(16)", updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "charge_code", nullable = false, updatable = false, length = 20)
    private BookingChargeCode code;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "calc_method", nullable = false, length = 20)
    private BookingChargeCalc calcMethod;

    @Column(name = "charge_value", nullable = false, precision = 12, scale = 3)
    private BigDecimal value;

    @Column(name = "min_amount", precision = 12, scale = 2)
    private BigDecimal minAmount;

    @Column(name = "active", nullable = false)
    private boolean active;

    public static BookingChargeRate of(UUID customerId, BookingChargeCode code) {
        BookingChargeRate r = new BookingChargeRate();
        r.customerId = customerId;
        r.code = code;
        return r;
    }

    /** Sets the calculation; COD is always % of the COD amount, and only COD may be. */
    public void set(BookingChargeCalc calc, BigDecimal value, BigDecimal minAmount, boolean active) {
        check(code, calc, value, minAmount);
        this.calcMethod = calc;
        this.value = value;
        this.minAmount = minAmount == null || minAmount.signum() == 0 ? null : minAmount;
        this.active = active;
    }

    public static void check(BookingChargeCode code, BookingChargeCalc calc, BigDecimal value, BigDecimal minAmount) {
        if (calc == null) {
            throw new BusinessRuleException("Choose how %s is calculated.".formatted(code.label()));
        }
        if ((code == BookingChargeCode.COD) != (calc == BookingChargeCalc.PERCENT_OF_COD)) {
            throw new BusinessRuleException(code == BookingChargeCode.COD
                    ? "COD is a % of the COD amount, with a minimum."
                    : "Only COD is a % of the COD amount.");
        }
        if (value == null || value.signum() < 0) {
            throw new BusinessRuleException("%s needs a value of 0 or more.".formatted(code.label()));
        }
        boolean percent = calc == BookingChargeCalc.PERCENT_OF_FREIGHT || calc == BookingChargeCalc.PERCENT_OF_COD;
        if (percent && value.compareTo(HUNDRED) > 0) {
            throw new BusinessRuleException("%s cannot be more than 100%%.".formatted(code.label()));
        }
        if (minAmount != null && minAmount.signum() < 0) {
            throw new BusinessRuleException("The minimum cannot be negative.");
        }
    }

    /**
     * The amount for one booking: the calculation, then the minimum. {@code freight} for % of freight, {@code chargeableKg}
     * (rounded up to whole kg) for per kg, {@code pieces} for per piece, {@code codAmount} for COD — no COD amount, no COD
     * charge. Rounded to the paisa.
     */
    public static BigDecimal amount(BookingChargeCalc calc, BigDecimal value, BigDecimal minAmount, BigDecimal freight,
                                    BigDecimal chargeableKg, int pieces, BigDecimal codAmount) {
        BigDecimal v = value == null ? BigDecimal.ZERO : value;
        BigDecimal raw = switch (calc) {
            case FIXED -> v;
            case PER_KG -> v.multiply(nz(chargeableKg).setScale(0, RoundingMode.CEILING));
            case PER_PIECE -> v.multiply(BigDecimal.valueOf(Math.max(pieces, 1)));
            case PERCENT_OF_FREIGHT -> nz(freight).multiply(v).divide(HUNDRED, 4, RoundingMode.HALF_UP);
            case PERCENT_OF_COD -> {
                if (codAmount == null || codAmount.signum() <= 0) {
                    yield null;
                }
                yield codAmount.multiply(v).divide(HUNDRED, 4, RoundingMode.HALF_UP);
            }
        };
        if (raw == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        if (minAmount != null && raw.compareTo(minAmount) < 0) {
            raw = minAmount;
        }
        return raw.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal b) {
        return b == null ? BigDecimal.ZERO : b;
    }
}
