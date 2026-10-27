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
import org.hibernate.annotations.SQLRestriction;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A company-wide tariff zone (V138): a named group of states, districts, cities and pincodes that shipments are priced
 * by, such as PUNE, METRO or KERALA. Unlike {@code customerpricing.PricingZone} it belongs to no customer: it is the
 * company's own geography for its rate cards. The code is unique among the company's live zones (checked in the
 * service, so a soft-deleted zone does not reserve its code).
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "tariff_zones")
// Repeated deliberately: Hibernate does not inherit @Filter from a @MappedSuperclass.
@Filter(name = CompanyOwnedEntity.COMPANY_FILTER, condition = "company_id = :companyId")
@SQLRestriction("deleted = false")
public class TariffZone extends CompanyOwnedEntity {

    private static final Pattern CODE = Pattern.compile("^[A-Z0-9_-]{2,30}$");

    @Column(name = "code", nullable = false, length = 30)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "zone_usage", nullable = false, length = 10)
    @Builder.Default
    private ZoneUsage zoneUsage = ZoneUsage.TO;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private int displayOrder = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    @Builder.Default
    private TariffZoneStatus status = TariffZoneStatus.ACTIVE;

    public boolean isActive() {
        return status == TariffZoneStatus.ACTIVE;
    }

    public void activate() {
        this.status = TariffZoneStatus.ACTIVE;
    }

    public void deactivate() {
        this.status = TariffZoneStatus.INACTIVE;
    }

    /** Trims and upper-cases the code ("ex pune" becomes "EX_PUNE"), then checks every field. */
    public void applyInvariants() {
        this.code = code == null ? null : code.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "_");
        this.name = name == null ? null : name.trim();
        this.description = description == null || description.isBlank() ? null : description.trim();
        if (code == null || !CODE.matcher(code).matches()) {
            throw new BusinessRuleException("A zone code is 2 to 30 letters, digits, '-' or '_' (for example PUNE or NORTH_EAST).");
        }
        if (name == null || name.isBlank()) {
            throw new BusinessRuleException("A zone needs a name.");
        }
        if (zoneUsage == null) {
            throw new BusinessRuleException("Say whether the zone is used as a from zone, a to zone, or both.");
        }
        if (displayOrder < 0) {
            throw new BusinessRuleException("The display order cannot be negative.");
        }
    }
}
