package com.courier.modules.manifest.domain;

import com.courier.modules.shipment.domain.FlowType;
import com.courier.shared.domain.CompanyOwnedEntity;
import com.courier.shared.exception.BusinessRuleException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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
import java.util.UUID;

/**
 * Groups shipments travelling one booking-branch -&gt; delivery-branch run. Built as the
 * minimal prerequisite Shipment Movement needs underneath it (see V19's migration
 * comment) — enough to create a manifest, attach shipments to it, and assign a vehicle
 * and driver at dispatch time. No physical FK to booking/delivery branch, vehicle or
 * driver (a company user) — every one of them belongs to a different module, validated
 * in the service layer, the same cross-module treatment every id on {@code Shipment}
 * already gets.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "manifests",
        uniqueConstraints = @UniqueConstraint(name = "uk_manifests_company_number",
                columnNames = {"company_id", "manifest_number"}),
        indexes = {
                @Index(name = "idx_manifests_company_status", columnList = "company_id, status, created_at"),
                @Index(name = "idx_manifests_booking_branch",
                        columnList = "company_id, booking_branch_id, status")
        })
@Filter(name = CompanyOwnedEntity.COMPANY_FILTER, condition = "company_id = :companyId")
@SQLRestriction("deleted = false")
public class Manifest extends CompanyOwnedEntity {

    @Column(name = "manifest_number", nullable = false, updatable = false, length = 30)
    private String manifestNumber;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "booking_branch_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID bookingBranchId;

    /** Null only for {@link DeliveryMode#DIRECT_COMPANY_DELIVERY} — that mode has no
     *  delivery branch at all, the company's own vehicle/driver carries the shipment the
     *  rest of the way. Required (validated in {@code ManifestServiceImpl.create}) for
     *  {@link DeliveryMode#BRANCH_DELIVERY}, same as ever. */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "delivery_branch_id", columnDefinition = "BINARY(16)", updatable = false)
    private UUID deliveryBranchId;

    /** Who is responsible for delivery — see {@link DeliveryMode}'s own doc. Every
     *  manifest before this field existed is {@code BRANCH_DELIVERY} (the only mode that
     *  ever existed), backfilled by {@code V80}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_mode", nullable = false, length = 30, updatable = false)
    @Builder.Default
    private DeliveryMode deliveryMode = DeliveryMode.BRANCH_DELIVERY;

    /** {@link FlowType#STANDARD} for every manifest before {@code V104}. An MSRTC manifest
     *  only carries MSRTC shipments and always goes branch to branch. */
    @Enumerated(EnumType.STRING)
    @Column(name = "flow_type", nullable = false, length = 20, updatable = false)
    @Builder.Default
    private FlowType flowType = FlowType.STANDARD;

    /** The destination city this Load Sheet was created for — drives which BOOKED
     *  shipments (no delivery branch resolved yet, see {@code Shipment.deliveryBranchId})
     *  are eligible to attach, by matching their own {@code toCity} rather than a branch
     *  that doesn't exist for them yet. Null for a manifest created the old way, where
     *  every shipment it groups already carries a real next-stop branch (e.g. a crossing
     *  hop) — see {@code ShipmentServiceImpl.attachToManifest}. */
    @Column(name = "destination_city", length = 120, updatable = false)
    private String destinationCity;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "vehicle_id", columnDefinition = "BINARY(16)")
    private UUID vehicleId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "driver_user_id", columnDefinition = "BINARY(16)")
    private UUID driverUserId;

    /** Outscan (Scan → Assign to Branch): the co-loader the parcels were handed to, its forwarding number, and bags. */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "co_loader_id", columnDefinition = "BINARY(16)")
    private UUID coLoaderId;

    @Column(name = "forwarding_number", length = 60)
    private String forwardingNumber;

    @Column(name = "bags_count")
    private Integer bagsCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ManifestStatus status = ManifestStatus.CREATED;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    /** Operator-entered departure time — distinct from {@code dispatchedAt} (the server
     *  clock at the moment THC was created); this is when the vehicle actually leaves,
     *  which the operator may back- or forward-date relative to that. Optional: falls
     *  back to {@code dispatchedAt} wherever it is displayed. */
    @Column(name = "departure_time")
    private Instant departureTime;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "remarks", length = 500)
    private String remarks;

    /** Trip expenses, all optional and operator-entered at dispatch time — every one of
     *  them prints on the THC alongside the vehicle/driver so the trip's cash outlay is
     *  on the same document as the load itself. */
    @Column(name = "fuel_cost", precision = 12, scale = 2)
    private BigDecimal fuelCost;

    @Column(name = "driver_advance", precision = 12, scale = 2)
    private BigDecimal driverAdvance;

    @Column(name = "toll_amount", precision = 12, scale = 2)
    private BigDecimal tollAmount;

    @Column(name = "other_amount", precision = 12, scale = 2)
    private BigDecimal otherAmount;

    /** BCrypt hash of the current dispatch OTP — never the raw code, matching this
     *  project's password/token storage convention. Null once verified-and-consumed by
     *  {@link #dispatch} or invalidated after {@link #OTP_MAX_ATTEMPTS} wrong guesses. */
    @Column(name = "dispatch_otp_hash", length = 100)
    private String dispatchOtpHash;

    /** The driver this OTP was issued for — {@link #dispatch} refuses to proceed unless
     *  the driver being dispatched is this exact id, so switching the driver selection
     *  after requesting an OTP can't reuse a code meant for someone else. */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "dispatch_otp_driver_id", columnDefinition = "BINARY(16)")
    private UUID dispatchOtpDriverId;

    @Column(name = "dispatch_otp_expires_at")
    private Instant dispatchOtpExpiresAt;

    @Column(name = "dispatch_otp_attempts", nullable = false)
    @Builder.Default
    private int dispatchOtpAttempts = 0;

    /** Set once the OTP has been correctly verified; {@link #dispatch} requires this to be
     *  non-null (and still within {@link #dispatchOtpExpiresAt}) for the same driver. */
    @Column(name = "dispatch_otp_verified_at")
    private Instant dispatchOtpVerifiedAt;

    private static final int OTP_MAX_ATTEMPTS = 5;

    public boolean isDispatched() {
        return status != ManifestStatus.CREATED;
    }

    /** Every shipment on it has been received off the road: the manifest is closed (shown as "Received"). */
    public void complete() {
        if (status == ManifestStatus.DISPATCHED) {
            this.status = ManifestStatus.COMPLETED;
        }
    }

    /**
     * @throws BusinessRuleException already dispatched — a manifest's vehicle/driver are
     *         fixed at the moment it leaves, the same "point of no return" DISPATCH
     *         carries everywhere else in this project
     */
    public void dispatch(UUID vehicleId, UUID driverUserId, Instant departureTime,
            BigDecimal fuelCost, BigDecimal driverAdvance, BigDecimal tollAmount, BigDecimal otherAmount) {
        if (isDispatched()) {
            throw new BusinessRuleException(
                    "Manifest %s has already been dispatched.".formatted(manifestNumber));
        }
        // Driver OTP verification is optional for now, on direct request — requestDispatchOtp/
        // verifyDispatchOtp still work end to end, but dispatch() no longer requires
        // requireDispatchOtpVerified() to have succeeded first. Re-enable by calling it here
        // again once OTP is made mandatory.
        this.vehicleId = vehicleId;
        this.driverUserId = driverUserId;
        this.status = ManifestStatus.DISPATCHED;
        this.dispatchedAt = Instant.now();
        this.departureTime = departureTime != null ? departureTime : this.dispatchedAt;
        this.fuelCost = fuelCost;
        this.driverAdvance = driverAdvance;
        this.tollAmount = tollAmount;
        this.otherAmount = otherAmount;
        // One-time use — a dispatched manifest never needs its OTP state again, and a
        // future re-dispatch attempt (refused above anyway) must not find a stale
        // "verified" flag lying around.
        this.dispatchOtpHash = null;
        this.dispatchOtpDriverId = null;
        this.dispatchOtpExpiresAt = null;
        this.dispatchOtpAttempts = 0;
        this.dispatchOtpVerifiedAt = null;
    }

    /**
     * @throws BusinessRuleException already dispatched — same point-of-no-return as
     *         {@link #dispatch}; requesting a fresh OTP for an already-gone manifest makes
     *         no sense
     */
    public void issueDispatchOtp(UUID driverUserId, String otpHash, Instant expiresAt) {
        if (isDispatched()) {
            throw new BusinessRuleException(
                    "Manifest %s has already been dispatched.".formatted(manifestNumber));
        }
        this.dispatchOtpDriverId = driverUserId;
        this.dispatchOtpHash = otpHash;
        this.dispatchOtpExpiresAt = expiresAt;
        this.dispatchOtpAttempts = 0;
        this.dispatchOtpVerifiedAt = null;
    }

    /**
     * The hash comparison itself happens in the service layer (it owns the
     * {@code PasswordEncoder}) — this only applies the resulting business rules: wrong
     * codes count against {@link #OTP_MAX_ATTEMPTS} before the code is invalidated
     * outright, and a right one only sticks for the driver it was issued to.
     *
     * @throws BusinessRuleException no OTP requested yet, requested for a different
     *         driver, expired, or the code didn't match
     */
    public void registerOtpVerificationAttempt(UUID driverUserId, boolean codeMatched) {
        if (dispatchOtpHash == null || dispatchOtpExpiresAt == null) {
            throw new BusinessRuleException("Request a driver OTP before verifying it.");
        }
        if (!driverUserId.equals(dispatchOtpDriverId)) {
            throw new BusinessRuleException("This OTP was issued for a different driver.");
        }
        if (Instant.now().isAfter(dispatchOtpExpiresAt)) {
            throw new BusinessRuleException("OTP has expired — request a new one.");
        }
        if (!codeMatched) {
            dispatchOtpAttempts++;
            if (dispatchOtpAttempts >= OTP_MAX_ATTEMPTS) {
                dispatchOtpHash = null;
                dispatchOtpExpiresAt = null;
            }
            throw new BusinessRuleException("Incorrect OTP.");
        }
        this.dispatchOtpVerifiedAt = Instant.now();
    }

    private void requireDispatchOtpVerified(UUID driverUserId) {
        if (dispatchOtpVerifiedAt == null || !driverUserId.equals(dispatchOtpDriverId)) {
            throw new BusinessRuleException("Verify the driver's OTP before dispatching this manifest.");
        }
        if (dispatchOtpExpiresAt == null || Instant.now().isAfter(dispatchOtpExpiresAt)) {
            throw new BusinessRuleException(
                    "Driver OTP verification has expired — verify again before dispatching.");
        }
    }
}
