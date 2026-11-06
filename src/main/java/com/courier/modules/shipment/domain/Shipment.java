package com.courier.modules.shipment.domain;

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
import java.time.LocalDate;
import java.util.UUID;

/**
 * The aggregate root of Shipment Booking: one row per booked shipment.
 *
 * <p>Sender/receiver are plain text on this row — name, address and contact number typed
 * at booking time, not a reference into the Customer module (no {@code Customer}/{@code
 * CustomerAddress} lookup at all here; the LR is printed from whatever the booking desk
 * typed). {@code pickupPincode}/{@code deliveryPincode} are typed alongside them and feed
 * the Pricing Engine directly — there is no address record left to resolve a pincode id
 * from.
 *
 * <p>{@code actualWeight}/{@code volumetricWeight}/{@code chargeableWeight} are computed
 * once at booking time from the item grid, using the Pricing Engine's own reusable
 * {@link com.courier.modules.pricing.domain.WeightCalculator}/{@link
 * com.courier.modules.pricing.domain.VolumetricCalculator}/{@link
 * com.courier.modules.pricing.domain.ChargeableWeightCalculator} — this module never
 * reimplements that arithmetic, only calls it once per item and sums the result. See
 * {@code ShipmentServiceImpl} and {@code MEMORY/modules/shipment-booking.md}.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(
        name = "shipments",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_shipments_company_number",
                        columnNames = {"company_id", "shipment_number"}),
                @UniqueConstraint(name = "uk_shipments_company_tracking",
                        columnNames = {"company_id", "tracking_number"})
        },
        indexes = {
                @Index(name = "idx_shipments_tracking", columnList = "tracking_number"),
                @Index(name = "idx_shipments_status", columnList = "company_id, status, created_at"),
                @Index(name = "idx_shipments_booking_branch",
                        columnList = "company_id, booking_branch_id, status"),
                @Index(name = "idx_shipments_delivery_branch",
                        columnList = "company_id, delivery_branch_id, status")
        })
// Repeated deliberately: Hibernate does not inherit @Filter from a @MappedSuperclass.
@Filter(name = CompanyOwnedEntity.COMPANY_FILTER, condition = "company_id = :companyId")
@SQLRestriction("deleted = false")
public class Shipment extends CompanyOwnedEntity {

    @Column(name = "shipment_number", nullable = false, updatable = false, length = 30)
    private String shipmentNumber;

    @Column(name = "tracking_number", nullable = false, updatable = false, length = 30)
    private String trackingNumber;

    @Column(name = "booking_date", nullable = false)
    private LocalDate bookingDate;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "booking_branch_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID bookingBranchId;

    /**
     * Always null at booking — the operator only picks a destination pincode/area, never
     * a Delivery Branch, and {@code ShipmentServiceImpl.create}/{@code update} resolve one
     * off {@code branch_pincode_mapping} only to price the booking, never to persist it
     * here. Filled in for real, once, the first time a Load Sheet (Manifest) is created
     * for this shipment's {@code toCity} — see {@code ShipmentServiceImpl.attachToManifest}.
     */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "delivery_branch_id", columnDefinition = "BINARY(16)")
    private UUID deliveryBranchId;

    /** The booking branch's own city — plain text, same modelling as {@code Branch.city},
     *  shown at booking instead of a branch picker. */
    @Column(name = "from_city", length = 120)
    private String fromCity;

    /** The destination pincode/area's resolved city — plain text, shown at booking
     *  instead of a Delivery Branch picker. */
    @Column(name = "to_city", length = 120)
    private String toCity;

    /**
     * The manifest this shipment is currently scanned onto — set by Shipment Movement's
     * manifest creation, cleared by nothing (a delivered/returned/cancelled shipment
     * keeps its last manifest for history). No physical FK: manifest is a different
     * module, validated in {@code ShipmentMovementServiceImpl}.
     */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "manifest_id", columnDefinition = "BINARY(16)")
    private UUID manifestId;

    /**
     * Where this shipment physically is right now — the booking branch at booking time,
     * updated by later movement steps. No physical FK: branch is a different module.
     */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "current_location_id", columnDefinition = "BINARY(16)")
    private UUID currentLocationId;

    /**
     * Where this shipment is headed next — the crossing branch when one was picked at
     * booking time, otherwise null until a Load Sheet assigns a real delivery branch (set
     * to the same value as {@code deliveryBranchId} from that point on). No physical FK.
     */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "next_location_id", columnDefinition = "BINARY(16)")
    private UUID nextLocationId;

    @Column(name = "pickup_pincode", nullable = false, length = 10)
    private String pickupPincode;

    @Column(name = "delivery_pincode", nullable = false, length = 10)
    private String deliveryPincode;

    /** The specific Area of {@code deliveryPincode} the operator picked at booking, when
     *  that pincode has more than one — null for older shipments booked before this
     *  column existed. Lets the printed receipt show the area actually chosen instead of
     *  the pincode's single fixed "primary" area. */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "destination_area_id", columnDefinition = "BINARY(16)")
    private UUID destinationAreaId;

    /** The Business Customer this booking was made for, when it was booked through
     *  "Business Customer booking" — freight is priced from that customer's pricing-zone
     *  rate card when one covers the destination, and re-derived from it on edit. Null for
     *  every ordinary booking and for shipments booked before this column existed. */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "business_customer_id", columnDefinition = "BINARY(16)")
    private UUID businessCustomerId;

    @Column(name = "sender_name", nullable = false, length = 150)
    private String senderName;

    @Column(name = "sender_address", nullable = false, length = 500)
    private String senderAddress;

    @Column(name = "sender_contact", nullable = false, length = 20)
    private String senderContact;

    @Column(name = "receiver_name", nullable = false, length = 150)
    private String receiverName;

    @Column(name = "receiver_address", nullable = false, length = 500)
    private String receiverAddress;

    @Column(name = "receiver_contact", nullable = false, length = 20)
    private String receiverContact;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "service_type_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID serviceTypeId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "package_type_id", columnDefinition = "BINARY(16)")
    private UUID packageTypeId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "payment_mode_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID paymentModeId;

    /** Set when a company admin flipped this still-BOOKED shipment between PAID and TO_PAY:
     *  the mode it had before the (latest) change. Null means "never changed". */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "payment_mode_changed_from_id", columnDefinition = "BINARY(16)")
    private UUID paymentModeChangedFromId;

    @Column(name = "payment_mode_changed_at")
    private Instant paymentModeChangedAt;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "payment_mode_changed_by", columnDefinition = "BINARY(16)")
    private UUID paymentModeChangedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "shipment_type", nullable = false, length = 20)
    @Builder.Default
    private ShipmentType shipmentType = ShipmentType.NON_DOCUMENT;

    @Column(name = "expected_delivery_date")
    private LocalDate expectedDeliveryDate;

    @Column(name = "actual_weight", nullable = false, precision = 12, scale = 3)
    private BigDecimal actualWeight;

    @Column(name = "volumetric_weight", nullable = false, precision = 12, scale = 3)
    @Builder.Default
    private BigDecimal volumetricWeight = BigDecimal.ZERO;

    @Column(name = "chargeable_weight", nullable = false, precision = 12, scale = 3)
    private BigDecimal chargeableWeight;

    @Column(name = "declared_value", precision = 19, scale = 4)
    private BigDecimal declaredValue;

    /** Entered at booking time — the number {@code EwayBillService.isRequired} checks
     *  against the company's own configurable threshold. Distinct from {@link #declaredValue}
     *  (an insurance-adjacent figure the Pricing Engine reads); this one drives only the
     *  E-Way Bill mandatory/optional decision. See {@code com.courier.modules.ewaybill}. */
    @Column(name = "invoice_value", precision = 19, scale = 4)
    private BigDecimal invoiceValue;

    /** Frozen at booking time from {@link #invoiceValue} vs. the threshold in effect that
     *  moment — never recomputed against a later threshold change, so an already-booked
     *  shipment's own requirement never silently drifts. */
    @Column(name = "eway_bill_required", nullable = false)
    @Builder.Default
    private boolean ewayBillRequired = false;

    @Column(name = "number_of_packages", nullable = false)
    @Builder.Default
    private Integer numberOfPackages = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ShipmentStatus status = ShipmentStatus.BOOKED;

    /** Set once this shipment's POD verification reaches {@code PASS} — by AI auto-pass, a
     *  manual reviewer's approve, or a company-direct upload (always auto-approved). Delivery
     *  commission (DRS charge, weight commission, TO_PAY/COD booking commission) waits for
     *  this, not just for {@code DELIVERED} — see {@code ShipmentServiceImpl
     *  .creditDeliveryCommissionsIfEligible} and {@code PodVerificationServiceImpl}. */
    @Column(name = "pod_approved", nullable = false)
    @Builder.Default
    private boolean podApproved = false;

    /** Guards {@link #podApproved}-gated commission crediting against firing twice — POD
     *  approval and delivery can happen in either order, and both call sites check this. */
    @Column(name = "commission_credited", nullable = false)
    @Builder.Default
    private boolean commissionCredited = false;

    @Column(name = "remarks", length = 500)
    private String remarks;

    // ---- forwarded to a courier partner from the hub (V155) ----------------------------------------------------------------

    /** The courier partner (DELHIVERY, SHIPROCKET...) the shipment was handed to; null when it was never forwarded. */
    @Column(name = "forward_provider", length = 40)
    private String forwardProvider;

    @Column(name = "forward_service", length = 150)
    private String forwardService;

    /** SUBMITTING while the courier is being asked, UNKNOWN when we cannot tell, BOOKED once it has the parcel. */
    @Column(name = "forward_state", length = 12)
    private String forwardState;

    /** The courier's own tracking number (the customer keeps the OCCPL one). */
    @Column(name = "forward_awb", length = 80)
    private String forwardAwb;

    @Column(name = "forward_order_ref", length = 120)
    private String forwardOrderRef;

    @Column(name = "forward_label_url", length = 500)
    private String forwardLabelUrl;

    /** What the courier charges us (our cost; the customer's price is unchanged). */
    @Column(name = "forward_cost", precision = 12, scale = 2)
    private BigDecimal forwardCost;

    @Column(name = "forward_note", length = 255)
    private String forwardNote;

    @Column(name = "forwarded_at")
    private java.time.Instant forwardedAt;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.BINARY)
    @Column(name = "forwarded_by", columnDefinition = "BINARY(16)")
    private UUID forwardedBy;

    // ---- failed delivery attempts (V157) --------------------------------------------------------------------------------------

    @Builder.Default
    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts = 0;

    @Column(name = "undelivered_reason", length = 40)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private UndeliveredReason undeliveredReason;

    @Column(name = "undelivered_note", length = 255)
    private String undeliveredNote;

    @Column(name = "undelivered_at")
    private java.time.Instant undeliveredAt;

    /** An attempt failed and nobody has acted yet (sent it out again, delivered or returned it). */
    @Builder.Default
    @Column(name = "undelivered_open", nullable = false)
    private boolean undeliveredOpen = false;

    /** After this many failed attempts a shipment is due for return (staff decide; nothing returns by itself). */
    public static final int RETURN_DUE_AFTER_ATTEMPTS = 3;

    public boolean isReturnDue() {
        return failedAttempts >= RETURN_DUE_AFTER_ATTEMPTS;
    }

    /** True once the courier has the parcel. */
    public boolean isForwarded() {
        return "BOOKED".equals(forwardState);
    }

    /**
     * Why this shipment cannot be forwarded to a courier right now, or null when it can: only while it is at the hub or branch, and
     * only once (a forward that is in progress, unconfirmed or done blocks another).
     */
    public String forwardBlocker() {
        if (forwardState != null) {
            return switch (forwardState) {
                case "BOOKED" -> "This shipment is already forwarded to " + forwardProvider
                        + (forwardAwb == null ? "" : " (AWB " + forwardAwb + ")") + ".";
                case "SUBMITTING" -> "This shipment is being forwarded to " + forwardProvider + " right now.";
                default -> "A forward to " + forwardProvider + " could not be confirmed. Check with the courier before forwarding again.";
            };
        }
        if (status != ShipmentStatus.BOOKED && status != ShipmentStatus.READY_FOR_MANIFEST && status != ShipmentStatus.IN_SCAN) {
            return "A shipment can be forwarded only while it is at your hub or branch (booked, ready for manifest or in scan); this one is "
                    + status.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + ".";
        }
        return null;
    }

    /** Optional at booking time — when true, {@link #appointmentDate}/{@link
     *  #appointmentTimeSlot} must be set and the operator may charge an Appointment
     *  Delivery Charge ({@code ShipmentCharge.appointmentDeliveryCharge}, deliberately
     *  GST-free — see that field). */
    @Column(name = "appointment_delivery", nullable = false)
    @Builder.Default
    private boolean appointmentDelivery = false;

    @Column(name = "appointment_date")
    private LocalDate appointmentDate;

    /** Free text, e.g. {@code "1:00-2:00"} — not a structured time range. */
    @Column(name = "appointment_time_slot", length = 20)
    private String appointmentTimeSlot;

    /** Optional at booking time — when true, insurance is charged at 2% of freight
     *  instead of the Pricing Engine's own rate-driven insurance figure. See
     *  {@code ShipmentServiceImpl.copyCharge}. */
    @Column(name = "insurance_applicable", nullable = false)
    @Builder.Default
    private boolean insuranceApplicable = false;

    /** {@code DOOR} (default) may carry a manual, GST-free {@code
     *  ShipmentCharge.doorDeliveryCharge}; {@code OFFICE} never charges extra —
     *  enforced server-side in {@code ShipmentServiceImpl.copyCharge}, not just UX. */
    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_type", nullable = false, length = 10)
    @Builder.Default
    private DeliveryType deliveryType = DeliveryType.DOOR;

    /** {@link FlowType#STANDARD} for every shipment before {@code V104}; {@link FlowType#MSRTC}
     *  shipments carry a delivery branch from booking and only join MSRTC manifests. */
    @Enumerated(EnumType.STRING)
    @Column(name = "flow_type", nullable = false, length = 20, updatable = false)
    @Builder.Default
    private FlowType flowType = FlowType.STANDARD;

    // ---------------------------------------------------------------- behaviour

    public boolean isEditable() {
        return status == ShipmentStatus.BOOKED;
    }

    /**
     * @throws BusinessRuleException the requested transition is not legal from the current
     *         status — {@code ShipmentStatus.canTransitionTo} is the single source of truth
     */
    public void transitionTo(ShipmentStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new BusinessRuleException(
                    "Shipment %s cannot move from %s to %s.".formatted(shipmentNumber, status, next));
        }
        this.status = next;
    }

    /**
     * Writes {@code next} with no adjacency check at all — {@code ShipmentServiceImpl
     * .overrideStatus}'s raw fallback for a manual-override jump that isn't a legal
     * {@link ShipmentStatus#canTransitionTo} edge (or has no real service method covering it).
     * Refuses only a terminal current status (DELIVERED/RETURNED/CANCELLED) — those are
     * final by design, not a state a company setting should be able to reopen.
     */
    public void forceStatus(ShipmentStatus next) {
        if (status.isTerminal()) {
            throw new BusinessRuleException(
                    "Shipment %s is %s, a final status — it cannot be overridden any further."
                            .formatted(shipmentNumber, status));
        }
        if (next == null) {
            throw new BusinessRuleException("A target status is required.");
        }
        this.status = next;
    }

    public void applyInvariants() {
        this.remarks = blankToNull(remarks);
        if (bookingBranchId == null) {
            throw new BusinessRuleException("A shipment needs a booking branch.");
        }
        this.senderName = blankToNull(senderName);
        this.senderAddress = blankToNull(senderAddress);
        this.senderContact = blankToNull(senderContact);
        this.receiverName = blankToNull(receiverName);
        this.receiverAddress = blankToNull(receiverAddress);
        this.receiverContact = blankToNull(receiverContact);
        if (senderName == null || senderAddress == null || senderContact == null) {
            throw new BusinessRuleException("A shipment needs sender name, address and contact number.");
        }
        if (receiverName == null || receiverAddress == null || receiverContact == null) {
            throw new BusinessRuleException("A shipment needs receiver name, address and contact number.");
        }
        if (pickupPincode == null || pickupPincode.isBlank() || deliveryPincode == null || deliveryPincode.isBlank()) {
            throw new BusinessRuleException("A shipment needs both a pickup and a delivery pincode.");
        }
        if (actualWeight == null || actualWeight.signum() <= 0) {
            throw new BusinessRuleException("Actual weight must be greater than zero.");
        }
        if (numberOfPackages == null || numberOfPackages < 1) {
            this.numberOfPackages = 1;
        }
        if (declaredValue == null || declaredValue.signum() <= 0) {
            throw new BusinessRuleException("A shipment needs a declared value greater than zero.");
        }
        // The frontend derives both as the same sum of every item's own declared value —
        // on direct request, they can never legitimately differ. Only enforced when
        // invoiceValue is actually supplied (still optional in its own right; it only
        // drives the E-Way Bill mandatory-threshold decision).
        if (invoiceValue != null && invoiceValue.compareTo(declaredValue) != 0) {
            throw new BusinessRuleException("Invoice value must equal declared value.");
        }
        if (shipmentType == null) {
            this.shipmentType = ShipmentType.NON_DOCUMENT;
        }
        if (deliveryType == null) {
            this.deliveryType = DeliveryType.DOOR;
        }
        if (appointmentDelivery) {
            if (appointmentDate == null || blankToNull(appointmentTimeSlot) == null) {
                throw new BusinessRuleException(
                        "Appointment Delivery needs both a date and a time slot.");
            }
            this.appointmentTimeSlot = appointmentTimeSlot.trim();
        } else {
            this.appointmentDate = null;
            this.appointmentTimeSlot = null;
        }
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
