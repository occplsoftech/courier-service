package com.courier.modules.pickup.domain;

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
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A request to collect a shipment from an address, worked by a Pickup-Delivery Partner. */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "pickup_requests")
@Filter(name = CompanyOwnedEntity.COMPANY_FILTER, condition = "company_id = :companyId")
@SQLRestriction("deleted = false")
public class PickupRequest extends CompanyOwnedEntity {

    @Column(name = "pickup_no", nullable = false, length = 30, updatable = false)
    private String pickupNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private PickupStatus status = PickupStatus.REQUESTED;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "customer_id", columnDefinition = "BINARY(16)")
    private UUID customerId;

    @Column(name = "contact_name", nullable = false, length = 150)
    private String contactName;

    @Column(name = "contact_mobile", nullable = false, length = 20)
    private String contactMobile;

    @Column(name = "address_line", nullable = false, length = 255)
    private String addressLine;

    @Column(name = "city", length = 100)
    private String city;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "district_id", columnDefinition = "BINARY(16)")
    private UUID districtId;

    @Column(name = "pincode", nullable = false, length = 10)
    private String pincode;

    @Column(name = "expected_parcels", nullable = false)
    @Builder.Default
    private int expectedParcels = 1;

    @Column(name = "expected_weight_kg", precision = 12, scale = 3)
    private BigDecimal expectedWeightKg;

    @Column(name = "preferred_date")
    private LocalDate preferredDate;

    @Column(name = "preferred_slot", length = 40)
    private String preferredSlot;

    @Column(name = "remarks", length = 500)
    private String remarks;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "pdp_branch_id", columnDefinition = "BINARY(16)")
    private UUID pdpBranchId;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "picked_up_at")
    private Instant pickedUpAt;

    @Column(name = "actual_parcels")
    private Integer actualParcels;

    @Column(name = "actual_weight_kg", precision = 12, scale = 3)
    private BigDecimal actualWeightKg;

    @Column(name = "reject_reason", length = 255)
    private String rejectReason;

    @Column(name = "fail_reason", length = 255)
    private String failReason;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "shipment_id", columnDefinition = "BINARY(16)")
    private UUID shipmentId;

    @Column(name = "shipment_no", length = 50)
    private String shipmentNo;

    /** The branch this pickup belongs to (the one its pincode is mapped to); it assigns the pickup. Null when unmapped. */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "booking_branch_id", columnDefinition = "BINARY(16)")
    private UUID bookingBranchId;

    /** Moves to {@code next} or fails with the two states named. */
    public void moveTo(PickupStatus next) {
        if (!status.canMoveTo(next)) {
            throw new BusinessRuleException(
                    "A pickup that is %s cannot become %s.".formatted(status, next));
        }
        this.status = next;
    }

    public void applyInvariants() {
        if (contactName == null || contactName.isBlank()) {
            throw new BusinessRuleException("Contact name is required.");
        }
        if (contactMobile == null || !contactMobile.matches("^[+]?[0-9 \\-]{7,20}$")) {
            throw new BusinessRuleException("A valid contact mobile is required.");
        }
        if (addressLine == null || addressLine.isBlank()) {
            throw new BusinessRuleException("Pickup address is required.");
        }
        if (pincode == null || !pincode.matches("^[0-9]{6}$")) {
            throw new BusinessRuleException("Pickup pincode must be 6 digits.");
        }
        if (expectedParcels < 1) {
            throw new BusinessRuleException("Expected parcels must be at least 1.");
        }
        if (expectedWeightKg != null && expectedWeightKg.signum() < 0) {
            throw new BusinessRuleException("Expected weight cannot be negative.");
        }
    }
}
