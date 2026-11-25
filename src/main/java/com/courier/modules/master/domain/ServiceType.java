package com.courier.modules.master.domain;

import com.courier.shared.domain.CompanyOwnedEntity;
import com.courier.shared.exception.BusinessRuleException;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalTime;

/**
 * The service level sold — STANDARD, EXPRESS, SAME_DAY, ECONOMY.
 *
 * <p>{@code deliveryDays} is the promise the customer is quoted, and {@code cutoffTime}
 * is the last moment a booking still makes today's count. Zero days means same day, which
 * is why the field is validated {@code >= 0} rather than {@code > 0} — a SAME_DAY row
 * with a one-day promise would quietly mis-quote every shipment on it.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(
        name = "master_service_types",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_master_service_types_code",
                        columnNames = {"company_id", "code"}),
                @UniqueConstraint(name = "uk_master_service_types_name",
                        columnNames = {"company_id", "name"})
        },
        indexes = @Index(name = "idx_master_service_types_status",
                columnList = "company_id, status"))
@Filter(name = CompanyOwnedEntity.COMPANY_FILTER, condition = "company_id = :companyId")
@SQLRestriction("deleted = false")
public class ServiceType extends MasterDataEntity {

    /** Promised transit in days. 0 is same day. Null means "not promised". */
    @Column(name = "delivery_days")
    private Integer deliveryDays;

    @Column(name = "is_express", nullable = false)
    private boolean express = false;

    @Column(name = "cutoff_time")
    private LocalTime cutoffTime;

    /** Ranking when more than one service can carry a shipment. Higher wins. */
    @Column(name = "priority", nullable = false)
    private Integer priority = 0;

    /** cm3-per-kg divisor of L x W x H for this service's volumetric weight (Air is
     *  commonly 5000, Surface 4000). Null falls back to the deployment default. */
    @Column(name = "volumetric_divisor")
    private Integer volumetricDivisor;

    /**
     * The network parcels on this service travel by (V171): SURFACE or AIR, null meaning SURFACE.
     * Booking checks the delivery pincode's coverage for that network. Documents always use the
     * document network, whatever the service.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "network", length = 10)
    private ServiceNetwork network;

    public ServiceNetwork networkOrSurface() {
        return network == ServiceNetwork.AIR ? ServiceNetwork.AIR : ServiceNetwork.SURFACE;
    }

    @Override
    protected void applySpecificInvariants() {
        if (deliveryDays != null && deliveryDays < 0) {
            throw new BusinessRuleException("Delivery days cannot be negative.");
        }
        if (volumetricDivisor != null && volumetricDivisor <= 0) {
            throw new BusinessRuleException("The volumetric divisor must be above 0 (commonly 5000), or left empty.");
        }
        if (priority == null) {
            this.priority = 0;
        }
    }
}
