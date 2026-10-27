package com.courier.modules.tariff.domain;

import com.courier.shared.domain.CompanyOwnedEntity;
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

import java.util.UUID;

/**
 * One state, district, city or pincode that belongs to a tariff zone. The ids above the scope are filled in too (a
 * CITY location also carries its district and state), so a list can be shown and filtered without walking the
 * geography again. {@link #placeKey()} is what makes two locations "the same place".
 *
 * <p>Geography ids point at the global masters (another module's tables): validated in the service, no physical FK,
 * the same treatment {@code customer_zone_locations} gives them.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "tariff_zone_locations")
@Filter(name = CompanyOwnedEntity.COMPANY_FILTER, condition = "company_id = :companyId")
@SQLRestriction("deleted = false")
public class TariffZoneLocation extends CompanyOwnedEntity {

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "zone_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID zoneId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 10, updatable = false)
    private LocationScope scope;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "state_id", columnDefinition = "BINARY(16)", updatable = false)
    private UUID stateId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "district_id", columnDefinition = "BINARY(16)", updatable = false)
    private UUID districtId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "city_id", columnDefinition = "BINARY(16)", updatable = false)
    private UUID cityId;

    @Column(name = "pincode", length = 6, updatable = false)
    private String pincode;

    /** The value this location is keyed on at its own scope: the state id for STATE, the pincode for PINCODE, and so on. */
    public String placeKey() {
        return switch (scope) {
            case STATE -> String.valueOf(stateId);
            case DISTRICT -> String.valueOf(districtId);
            case CITY -> String.valueOf(cityId);
            case PINCODE -> pincode;
        };
    }
}
