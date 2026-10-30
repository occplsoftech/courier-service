package com.courier.modules.customerportal.rates.domain;

import com.courier.shared.domain.CompanyOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/** One courier a business customer may use in the customer portal. */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "customer_courier_access")
@Filter(name = CompanyOwnedEntity.COMPANY_FILTER, condition = "company_id = :companyId")
@SQLRestriction("deleted = false")
public class CustomerCourierAccess extends CompanyOwnedEntity {

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "customer_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "provider_code", nullable = false, length = 40, updatable = false)
    private String providerCode;

    public CustomerCourierAccess(UUID customerId, String providerCode) {
        this.customerId = customerId;
        this.providerCode = providerCode;
    }
}
