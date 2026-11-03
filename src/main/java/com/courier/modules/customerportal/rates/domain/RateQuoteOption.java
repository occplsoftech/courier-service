package com.courier.modules.customerportal.rates.domain;

import com.courier.shared.domain.CompanyOwnedEntity;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One provider service in a comparison, priced and frozen (V128) — or why the provider had none. */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "rate_quote_options")
@Filter(name = CompanyOwnedEntity.COMPANY_FILTER, condition = "company_id = :companyId")
@SQLRestriction("deleted = false")
public class RateQuoteOption extends CompanyOwnedEntity {

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "quote_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID quoteId;

    @Column(name = "provider_code", nullable = false, length = 40, updatable = false) private String providerCode;
    @Column(name = "service_code", length = 80, updatable = false) private String serviceCode;
    @Column(name = "service_name", length = 150, updatable = false) private String serviceName;
    @Column(name = "transport_mode", length = 20, updatable = false) private String transportMode;
    @Column(name = "availability", nullable = false, length = 20, updatable = false) private String availability;
    @Column(name = "unavailable_reason", length = 255, updatable = false) private String unavailableReason;
    @Column(name = "eta_min_days", updatable = false) private Integer etaMinDays;
    @Column(name = "eta_max_days", updatable = false) private Integer etaMaxDays;
    @Column(name = "chargeable_weight_kg", precision = 10, scale = 3, updatable = false) private BigDecimal chargeableWeightKg;
    @Column(name = "base_freight", precision = 14, scale = 2, updatable = false) private BigDecimal baseFreight;
    @Column(name = "other_charges", precision = 14, scale = 2, updatable = false) private BigDecimal otherCharges;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "markup_rule_id", columnDefinition = "BINARY(16)", updatable = false)
    private UUID markupRuleId;

    @Column(name = "markup_percent", precision = 7, scale = 4, updatable = false) private BigDecimal markupPercent;
    @Column(name = "markup_base", precision = 14, scale = 2, updatable = false) private BigDecimal markupBase;
    @Column(name = "markup_amount", precision = 14, scale = 2, updatable = false) private BigDecimal markupAmount;
    @Column(name = "tax_amount", precision = 14, scale = 2, updatable = false) private BigDecimal taxAmount;
    @Column(name = "total_amount", precision = 14, scale = 2, updatable = false) private BigDecimal totalAmount;
    @Column(name = "currency", nullable = false, length = 3, updatable = false) private String currency;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "breakdown_json", columnDefinition = "JSON", updatable = false)
    private List<Map<String, Object>> breakdown;

    @Column(name = "provider_quote_ref", length = 120, updatable = false) private String providerQuoteRef;
    /** A short note shown next to the price, e.g. "Minimum chargeable weight 20 kg". */
    @Column(name = "note", length = 255, updatable = false) private String note;
    @Column(name = "valid_until", updatable = false) private Instant validUntil;

    public boolean isAvailable() {
        return "AVAILABLE".equals(availability);
    }
}
