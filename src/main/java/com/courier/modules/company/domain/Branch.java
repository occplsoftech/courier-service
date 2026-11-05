package com.courier.modules.company.domain;

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
import java.time.LocalTime;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A physical booking / delivery office of a company.
 *
 * <p>Company-owned. {@code branchCode} is unique within the company (never globally —
 * two couriers may both have a "MAIN" branch). {@code branchName} is not — two branches
 * in the same company may share a display name. The code is uppercased and editable;
 * shipments, manifests and reports reference the branch by id, not by code, so
 * renumbering it does not orphan historical data.
 *
 * <p>{@code managerId} is a user of the same company — validated in the service, not by a
 * database FK. The FK to {@code users} is deferred for the same reason the
 * {@code users.company_id} FK is: the cross-references are still settling and the dev
 * database holds rows that predate the constraints. "One manager per branch" is the
 * single {@code managerId} column.
 *
 * <p>The {@code allow*} flags are the operative capability switches (booking, delivery,
 * pickup, manifest, cash collection, wallet); {@link BranchType} is descriptive intent.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(
        name = "branches",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_branches_company_code",
                        columnNames = {"company_id", "branch_code"})
        },
        indexes = {
                @Index(name = "idx_branches_company_status", columnList = "company_id, status"),
                @Index(name = "idx_branches_company_type", columnList = "company_id, branch_type"),
                @Index(name = "idx_branches_manager", columnList = "company_id, manager_id"),
                @Index(name = "idx_branches_pincode", columnList = "company_id, postal_code")
        })
// Repeated deliberately: Hibernate does not inherit @Filter from a @MappedSuperclass.
@Filter(name = CompanyOwnedEntity.COMPANY_FILTER, condition = "company_id = :companyId")
@SQLRestriction("deleted = false")
public class Branch extends CompanyOwnedEntity {

    private static final Pattern GSTIN_PATTERN =
            Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");
    private static final Pattern PAN_PATTERN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");

    @Column(name = "branch_code", nullable = false, length = 50)
    private String branchCode;

    @Column(name = "branch_name", nullable = false, length = 150)
    private String branchName;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "branch_type", nullable = false, length = 30)
    @Builder.Default
    private BranchType branchType = BranchType.BRANCH;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private BranchStatus status = BranchStatus.ACTIVE;

    // --- contact ------------------------------------------------------------

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "mobile", length = 20)
    private String mobile;

    @Column(name = "alternate_mobile", length = 20)
    private String alternateMobile;

    /** The user who manages this branch. At most one; validated to be a company user. */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "manager_id", columnDefinition = "BINARY(16)")
    private UUID managerId;

    // --- address ------------------------------------------------------------

    @Column(name = "address_line1", length = 255)
    private String addressLine1;

    @Column(name = "address_line2", length = 255)
    private String addressLine2;

    @Column(name = "country", length = 100)
    private String country;

    @Column(name = "state", length = 100)
    private String state;

    @Column(name = "city", length = 100)
    private String city;

    @Column(name = "district", length = 100)
    private String district;

    @Column(name = "taluka", length = 100)
    private String taluka;

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    /** WGS84. {@code DECIMAL(9,6)} — ~0.1 m precision, enough for a rooftop. */
    @Column(name = "latitude", precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 9, scale = 6)
    private BigDecimal longitude;

    // --- hours --------------------------------------------------------------

    @Column(name = "opening_time")
    private LocalTime openingTime;

    @Column(name = "closing_time")
    private LocalTime closingTime;

    /**
     * Days the branch is open, as an uppercase CSV of three-letter codes,
     * e.g. {@code MON,TUE,WED,THU,FRI,SAT}. A string rather than a child table because a
     * branch has exactly one schedule and it is read whole; the format is validated on
     * write.
     */
    @Column(name = "working_days", length = 40)
    private String workingDays;

    // --- capability flags ---------------------------------------------------

    @Column(name = "allow_booking", nullable = false)
    @Builder.Default
    private boolean allowBooking = true;

    @Column(name = "allow_delivery", nullable = false)
    @Builder.Default
    private boolean allowDelivery = true;

    @Column(name = "allow_pickup", nullable = false)
    @Builder.Default
    private boolean allowPickup = true;

    @Column(name = "allow_manifest", nullable = false)
    @Builder.Default
    private boolean allowManifest = true;

    @Column(name = "allow_cash_collection", nullable = false)
    @Builder.Default
    private boolean allowCashCollection = true;

    @Column(name = "allow_wallet", nullable = false)
    @Builder.Default
    private boolean allowWallet = false;

    /** When true, a PREPAID booking's branch commission is credited to this branch's
     *  wallet the moment the booking debit settles; when false it is still computed and
     *  stored on the shipment charge, just not auto-credited. Defaults to true. */
    @Column(name = "instant_commission", nullable = false)
    @Builder.Default
    private boolean instantCommission = true;

    /** The HUB-type branch this branch's Loading Sheets go to by default; null = none. */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "assigned_hub_id", columnDefinition = "BINARY(16)")
    private UUID assignedHubId;

    /** The pickup location name registered at Delhivery / Shiprocket for this branch; null until set (V156). */
    @Column(name = "courier_pickup_location", length = 120)
    private String courierPickupLocation;

    @Column(name = "remarks", length = 500)
    private String remarks;

    /** GSTIN of this branch's registration. Optional — branch-level, not company-level;
     *  a company may run branches under different state GSTINs. */
    @Column(name = "gst_number", length = 15)
    private String gstNumber;

    /** PAN of this branch's registration. Optional. */
    @Column(name = "pan_number", length = 10)
    private String panNumber;

    // --- charge percentages --------------------------------------------------

    /** GST percentage applied at this branch. Defaults to 18, editable on update. */
    @Column(name = "gst_percentage", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal gstPercentage = new BigDecimal("18.00");

    /** Company's commission percentage on other charges. Defaults to 20, editable. */
    @Column(name = "commission_on_other_charges", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal commissionOnOtherCharges = new BigDecimal("20.00");

    /** Commission percentage on basic freight. Defaults to 10, editable. */
    @Column(name = "commission_on_basic_freight", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal commissionOnBasicFreight = new BigDecimal("10.00");

    /** Company service charge percentage. Defaults to 10, editable. */
    @Column(name = "company_service_charge_percentage", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal companyServiceChargePercentage = new BigDecimal("10.00");

    /** DRS charge per item quantity, debited from this branch's wallet on delivery
     *  ({@code drsCharge = drsChargePerQty * qty}). A fixed amount, not a percentage —
     *  unlike the four fields above. Defaults to 2.00, editable. */
    @Column(name = "drs_charge_per_qty", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal drsChargePerQty = new BigDecimal("2.00");

    /** Delivery commission per kg, credited to the delivery branch's wallet on delivery
     *  ({@code deliveryCommission = deliveryCommissionRatePerKg *
     *  max(shipment's chargeable weight, deliveryCommissionMinWeightKg)}). A rate, not a
     *  fixed amount — unlike {@link #drsChargePerQty}. Defaults to 1.50, editable. */
    @Column(name = "delivery_commission_rate_per_kg", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal deliveryCommissionRatePerKg = new BigDecimal("1.50");

    /** Minimum chargeable weight (kg) for {@link #deliveryCommissionRatePerKg} — a
     *  shipment lighter than this still earns commission as if it weighed this much.
     *  Defaults to 10.00, editable. */
    @Column(name = "delivery_commission_min_weight_kg", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal deliveryCommissionMinWeightKg = new BigDecimal("10.00");

    // ---------------------------------------------------------------- behaviour

    public static String normaliseCode(String code) {
        return code == null ? null : code.trim().toUpperCase().replace(' ', '_');
    }

    public boolean isActive() {
        return status == BranchStatus.ACTIVE;
    }

    public void activate() {
        this.status = BranchStatus.ACTIVE;
    }

    /** Withdraws the branch from operation. Existing shipments referencing it survive. */
    public void deactivate() {
        this.status = BranchStatus.INACTIVE;
    }

    public void assignManager(UUID managerId) {
        this.managerId = managerId;
    }

    public void assignHub(UUID hubId) {
        this.assignedHubId = hubId;
    }

    /** Normalises the matched/derived fields and validates hours and coordinates. */
    public void applyInvariants() {
        this.branchCode = normaliseCode(branchCode);
        this.branchName = branchName == null ? null : branchName.trim();
        this.email = email == null ? null : email.trim().toLowerCase();
        this.workingDays = normaliseWorkingDays(workingDays);
        this.gstNumber = blankToNull(gstNumber == null ? null : gstNumber.trim().toUpperCase());
        this.panNumber = blankToNull(panNumber == null ? null : panNumber.trim().toUpperCase());

        if (branchType == null) {
            throw new BusinessRuleException("A branch must have a type.");
        }
        if (gstNumber != null && !GSTIN_PATTERN.matcher(gstNumber).matches()) {
            throw new BusinessRuleException("GST number must be a valid 15-character GSTIN.");
        }
        if (panNumber != null && !PAN_PATTERN.matcher(panNumber).matches()) {
            throw new BusinessRuleException("PAN number must be a valid 10-character PAN.");
        }
        if (status == null) {
            this.status = BranchStatus.ACTIVE;
        }
        if (openingTime != null && closingTime != null && !closingTime.isAfter(openingTime)) {
            throw new BusinessRuleException("Closing time must be after opening time.");
        }
        requireInRange(latitude, "Latitude", -90, 90);
        requireInRange(longitude, "Longitude", -180, 180);
        requireInRange(gstPercentage, "GST percentage", 0, 100);
        requireInRange(commissionOnOtherCharges, "Commission on other charges", 0, 100);
        requireInRange(commissionOnBasicFreight, "Commission on basic freight", 0, 100);
        requireInRange(companyServiceChargePercentage, "Company service charge percentage", 0, 100);
        if (drsChargePerQty != null && drsChargePerQty.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessRuleException("DRS charge per qty cannot be negative.");
        }
        if (deliveryCommissionRatePerKg != null && deliveryCommissionRatePerKg.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessRuleException("Delivery commission rate per kg cannot be negative.");
        }
        if (deliveryCommissionMinWeightKg != null && deliveryCommissionMinWeightKg.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessRuleException("Delivery commission minimum weight cannot be negative.");
        }
    }

    private static String normaliseWorkingDays(String csv) {
        if (csv == null || csv.isBlank()) {
            return null;
        }
        String[] parts = csv.toUpperCase().split(",");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            String day = part.trim();
            if (day.isEmpty()) {
                continue;
            }
            if (!day.matches("MON|TUE|WED|THU|FRI|SAT|SUN")) {
                throw new BusinessRuleException(
                        "Invalid working day '%s'. Use MON..SUN, comma-separated.".formatted(day));
            }
            if (out.indexOf(day) < 0) {
                if (!out.isEmpty()) {
                    out.append(',');
                }
                out.append(day);
            }
        }
        return out.isEmpty() ? null : out.toString();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static void requireInRange(BigDecimal value, String label, int min, int max) {
        if (value == null) {
            return;
        }
        if (value.compareTo(BigDecimal.valueOf(min)) < 0 || value.compareTo(BigDecimal.valueOf(max)) > 0) {
            throw new BusinessRuleException(
                    "%s must be between %d and %d.".formatted(label, min, max));
        }
    }
}
