package com.courier.modules.master.domain;

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

import java.time.Instant;
import java.util.UUID;

/**
 * How often a company's bookings asked for a destination OCCPL does not serve: one row per pincode and area (area key empty when no
 * area was named), counted from staff booking and from the customer portal. "Turned on" once an admin switched it on from the report.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "unserviceable_requests")
@Filter(name = CompanyOwnedEntity.COMPANY_FILTER, condition = "company_id = :companyId")
@SQLRestriction("deleted = false")
public class UnserviceableRequest extends CompanyOwnedEntity {

    /** Why the latest request could not be served. */
    public enum Reason { PINCODE_OFF, AREA_OFF, NOT_ON_FILE }

    public enum Source { STAFF, PORTAL }

    @Column(name = "pincode_code", nullable = false, length = 6, updatable = false) private String pincodeCode;
    /** The area id as text, or '' when no area was named. */
    @Column(name = "area_key", nullable = false, length = 36, updatable = false) private String areaKey;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "pincode_id", columnDefinition = "BINARY(16)") private UUID pincodeId;
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "area_id", columnDefinition = "BINARY(16)") private UUID areaId;
    @Column(name = "area_name", length = 150) private String areaName;
    @Column(name = "city_name", length = 150) private String cityName;

    @Column(name = "reason", nullable = false, length = 30) @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Reason reason;
    @Column(name = "staff_count", nullable = false) private int staffCount;
    @Column(name = "portal_count", nullable = false) private int portalCount;
    @Column(name = "first_requested_at", nullable = false) private Instant firstRequestedAt;
    @Column(name = "last_requested_at", nullable = false) private Instant lastRequestedAt;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "last_branch_id", columnDefinition = "BINARY(16)") private UUID lastBranchId;
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "last_user_id", columnDefinition = "BINARY(16)") private UUID lastUserId;

    @Column(name = "turned_on_at") private Instant turnedOnAt;
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "turned_on_by", columnDefinition = "BINARY(16)") private UUID turnedOnBy;

    public int total() {
        return staffCount + portalCount;
    }
}
