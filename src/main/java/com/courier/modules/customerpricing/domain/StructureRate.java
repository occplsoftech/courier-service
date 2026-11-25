package com.courier.modules.customerpricing.domain;

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

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One rate of a business customer's rate structure (V177): for a service type, from an origin region (null = any)
 * to a destination group, per chargeable kg with a minimum chargeable weight.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "customer_structure_rates")
@Filter(name = CompanyOwnedEntity.COMPANY_FILTER, condition = "company_id = :companyId")
@SQLRestriction("deleted = false")
public class StructureRate extends CompanyOwnedEntity {

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "customer_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID customerId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "service_type_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID serviceTypeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin_region", length = 20)
    private OriginRegion originRegion;

    @Enumerated(EnumType.STRING)
    @Column(name = "destination_group", nullable = false, length = 20)
    private DestinationGroup destinationGroup;

    @Column(name = "rate_per_kg", nullable = false, precision = 12, scale = 2)
    private BigDecimal ratePerKg;

    @Column(name = "min_chargeable_kg", nullable = false, precision = 10, scale = 3)
    @Builder.Default
    private BigDecimal minChargeableKg = BigDecimal.ZERO;
}
