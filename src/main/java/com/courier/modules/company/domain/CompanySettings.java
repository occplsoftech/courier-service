package com.courier.modules.company.domain;

import com.courier.shared.domain.CompanyOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;

/**
 * A company's configurable behaviour settings — one wide, typed row per company.
 *
 * <p><b>This is not the key/value {@code company_settings} table.</b> That one holds
 * plan-derived facts (feature flags, quota limits) that provisioning seeds and permission
 * gating reads, and it stays exactly as it is. This entity, table
 * {@code company_settings_config}, holds the eight sections a company admin tunes:
 * general/regional, shipment, finance, notification, security, branding, document. The
 * two are deliberately separate concerns — "what the plan grants" versus "how this
 * company wants the app to behave".
 *
 * <p>Exactly one row per company ({@code UNIQUE(company_id)}). <b>Soft delete does not
 * apply</b> — a company always has settings, so there is no {@code @SQLRestriction} and
 * the row is never deleted; it is created on first access and updated in place. The
 * {@code BaseEntity} audit columns still record who last changed it and when.
 *
 * <p>Consumed by Branch, Hub, Shipment, Wallet, Payment and Report modules as they land.
 * Until then these are stored faithfully but not yet read by those modules — in
 * particular the {@code security.*} values do not override auth's {@code AuthProperties}
 * yet.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(
        name = "company_settings_config",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_company_settings_config_company", columnNames = "company_id"))
// Repeated deliberately: Hibernate does not inherit @Filter from a @MappedSuperclass.
@Filter(name = CompanyOwnedEntity.COMPANY_FILTER, condition = "company_id = :companyId")
public class CompanySettings extends CompanyOwnedEntity {

    // === General / Regional =================================================

    @Column(name = "company_name", length = 150)
    private String companyName;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "support_email", length = 255)
    private String supportEmail;

    @Column(name = "support_mobile", length = 20)
    private String supportMobile;

    @Column(name = "website", length = 255)
    private String website;

    @Column(name = "language", length = 10)
    @Builder.Default
    private String language = "en";

    @Column(name = "timezone", length = 64)
    @Builder.Default
    private String timezone = "Asia/Kolkata";

    @Column(name = "currency", length = 3)
    @Builder.Default
    private String currency = "INR";

    @Column(name = "country", length = 100)
    private String country;

    @Column(name = "state", length = 100)
    private String state;

    @Column(name = "city", length = 100)
    private String city;

    // === Shipment ===========================================================

    @Column(name = "awb_prefix", length = 20)
    private String awbPrefix;

    /** Next AWB sequence value. The shipment module will advance it atomically. */
    @Column(name = "awb_running_number", nullable = false)
    @Builder.Default
    private Long awbRunningNumber = 1L;

    @Column(name = "booking_prefix", length = 20)
    private String bookingPrefix;

    @Column(name = "manifest_prefix", length = 20)
    private String manifestPrefix;

    @Column(name = "default_service_type", length = 40)
    @Builder.Default
    private String defaultServiceType = "STANDARD";

    @Column(name = "default_package_type", length = 40)
    @Builder.Default
    private String defaultPackageType = "PARCEL";

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "weight_unit", length = 10)
    @Builder.Default
    private WeightUnit weightUnit = WeightUnit.KG;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "dimension_unit", length = 10)
    @Builder.Default
    private DimensionUnit dimensionUnit = DimensionUnit.CM;

    @Column(name = "auto_generate_barcode", nullable = false)
    @Builder.Default
    private boolean autoGenerateBarcode = true;

    @Column(name = "allow_duplicate_reference_number", nullable = false)
    @Builder.Default
    private boolean allowDuplicateReferenceNumber = false;

    /** Opt-in per company. When true, a {@code COMPANY_ADMIN}/{@code BRANCH_MANAGER} can push
     *  a shipment straight to any status via {@code ShipmentService.overrideStatus}, bypassing
     *  the normal per-screen flow (THC dispatch / DRS / Deliver) — for correcting a shipment
     *  stuck in the wrong state, or force-tracking a THC's shipments by hand. Off by default:
     *  an arbitrary jump that isn't a legal {@code ShipmentStatus} edge skips the real service
     *  method (and its money/wallet side effects) entirely, so this is a deliberate trade-off
     *  a company opts into, not a safe-by-default correction tool. */
    @Column(name = "manual_status_override_enabled", nullable = false)
    @Builder.Default
    private boolean manualStatusOverrideEnabled = false;

    @Column(name = "auto_assign_tracking_number", nullable = false)
    @Builder.Default
    private boolean autoAssignTrackingNumber = true;

    /** Prefilled onto every new item row in the booking screen's item grid — a UX
     *  default, not a pricing floor; {@link com.courier.modules.pricing.domain
     *  .ChargeableWeightCalculator} still computes {@code MAX(actual, volumetric)}
     *  with no minimum. */
    @Column(name = "default_chargeable_weight_kg", precision = 10, scale = 3, nullable = false)
    @Builder.Default
    private BigDecimal defaultChargeableWeightKg = new BigDecimal("15.000");

    /** Prefilled onto the Appointment Charge field when Appointment Delivery is checked
     *  at booking time — a UX default the desk can still type over; not GST-applied,
     *  see {@code ShipmentServiceImpl}'s charge breakup doc comment. */
    @Column(name = "default_appointment_delivery_charge", precision = 19, scale = 4, nullable = false)
    @Builder.Default
    private BigDecimal defaultAppointmentDeliveryCharge = new BigDecimal("1000.0000");

    /** Flat charge the backend adds to every booking whose payment mode collects at delivery
     *  (To-Pay) — not a prefill, the booking form shows it read-only. Taxed with GST, folded
     *  into {@code ShipmentCharge.toPayCharge}. Zero turns it off for this company. */
    @Column(name = "to_pay_charge", precision = 19, scale = 4, nullable = false)
    @Builder.Default
    private BigDecimal toPayCharge = new BigDecimal("50.0000");

    /** Forwarding: only couriers costing at most (100 - this)% of the shipment's booked amount are offered. */
    @Column(name = "forward_min_profit_percent", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal forwardMinProfitPercent = new BigDecimal("10.00");

    // === Finance ============================================================

    /** Percentage, 0–100. {@code DECIMAL(5,2)} — money-adjacent, never a double. */
    @Column(name = "gst_percentage", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal gstPercentage = new BigDecimal("18.00");

    @Column(name = "invoice_prefix", length = 20)
    private String invoicePrefix;

    /** Default customer credit ceiling. Null means no default limit. {@code DECIMAL(19,4)}. */
    @Column(name = "credit_limit", precision = 19, scale = 4)
    private BigDecimal creditLimit;

    @Column(name = "wallet_enabled", nullable = false)
    @Builder.Default
    private boolean walletEnabled = false;

    @Column(name = "cod_enabled", nullable = false)
    @Builder.Default
    private boolean codEnabled = true;

    @Column(name = "online_payment_enabled", nullable = false)
    @Builder.Default
    private boolean onlinePaymentEnabled = false;

    @Column(name = "auto_invoice_generation", nullable = false)
    @Builder.Default
    private boolean autoInvoiceGeneration = false;

    /** Name of a {@code com.courier.modules.pricing.domain.RoundingRule} constant (e.g.
     *  {@code "NEAREST_FIVE"}). Plain string, not that enum, so this module stays free of
     *  a dependency on pricing (pricing already depends on company one-way, via
     *  {@code CompanySettingsService}) — the same treatment {@link #passwordPolicy} gets. */
    @Column(name = "round_off_rule", length = 20, nullable = false)
    @Builder.Default
    private String roundOffRule = "NEAREST_FIVE";

    /** Shipment Booking's editable Net Amount preview (frontend {@code ChargeSummary},
     *  display-only — see its own doc comment) may not be typed lower than this percentage
     *  below the engine-computed amount. {@code DECIMAL(5,2)}, percentage 0–100. */
    @Column(name = "net_amount_max_decrease_percent", precision = 5, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal netAmountMaxDecreasePercent = new BigDecimal("10.00");

    /** Same guard as {@link #netAmountMaxDecreasePercent}, for the upper bound — Net Amount
     *  may not be typed higher than this percentage above the engine-computed amount. */
    @Column(name = "net_amount_max_increase_percent", precision = 5, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal netAmountMaxIncreasePercent = new BigDecimal("50.00");

    // === SLA (shipment lifecycle) ===========================================
    // Hours a shipment may sit in one status before the sweep (ShipmentSlaSweepJob)
    // raises an auto-ticket. Each field is the outbound leg of the status named,
    // e.g. slaBookingToLoadingSheetHours is how long a BOOKED shipment may go
    // without a loading sheet (MANIFEST_CREATED).

    @Column(name = "sla_breach_ticket_enabled", nullable = false)
    @Builder.Default
    private boolean slaBreachTicketEnabled = true;

    @Column(name = "sla_booking_to_loading_sheet_hours", nullable = false)
    @Builder.Default
    private int slaBookingToLoadingSheetHours = 24;

    @Column(name = "sla_loading_sheet_to_thc_hours", nullable = false)
    @Builder.Default
    private int slaLoadingSheetToThcHours = 24;

    @Column(name = "sla_thc_to_inscan_hours", nullable = false)
    @Builder.Default
    private int slaThcToInscanHours = 48;

    @Column(name = "sla_inscan_to_drs_hours", nullable = false)
    @Builder.Default
    private int slaInscanToDrsHours = 12;

    @Column(name = "sla_drs_to_delivery_hours", nullable = false)
    @Builder.Default
    private int slaDrsToDeliveryHours = 12;

    // === E-Way Bill ==========================================================
    // Invoice value above which E-Way Bill Management (com.courier.modules.ewaybill)
    // requires one before AWB generation; at or under it, optional. Never hardcoded in
    // application code — this is the one source of truth for the threshold.

    @Column(name = "eway_bill_mandatory_value", precision = 19, scale = 4, nullable = false)
    @Builder.Default
    private BigDecimal ewayBillMandatoryValue = new BigDecimal("50000.0000");

    // === Notification =======================================================

    @Column(name = "sms_enabled", nullable = false)
    @Builder.Default
    private boolean smsEnabled = false;

    @Column(name = "email_enabled", nullable = false)
    @Builder.Default
    private boolean emailEnabled = true;

    @Column(name = "whatsapp_enabled", nullable = false)
    @Builder.Default
    private boolean whatsappEnabled = false;

    @Column(name = "push_notification_enabled", nullable = false)
    @Builder.Default
    private boolean pushNotificationEnabled = false;

    // === Security ===========================================================
    // Stored per company, but NOT yet consumed by auth — auth still uses AuthProperties.
    // When auth is made company-owned it will read these; until then they are advisory.

    @Column(name = "password_policy", length = 40)
    @Builder.Default
    private String passwordPolicy = "STANDARD";

    /** Minutes of inactivity before a session ends. */
    @Column(name = "session_timeout_minutes")
    @Builder.Default
    private Integer sessionTimeoutMinutes = 30;

    @Column(name = "max_login_attempts")
    @Builder.Default
    private Integer maxLoginAttempts = 5;

    /** Minutes an account stays locked after too many failed logins. */
    @Column(name = "lock_duration_minutes")
    @Builder.Default
    private Integer lockDurationMinutes = 15;

    /** Minutes an OTP remains valid. */
    @Column(name = "otp_expiry_minutes")
    @Builder.Default
    private Integer otpExpiryMinutes = 5;

    // === Branding ===========================================================

    @Column(name = "company_logo", length = 500)
    private String companyLogo;

    @Column(name = "favicon", length = 500)
    private String favicon;

    @Column(name = "primary_color", length = 9)
    @Builder.Default
    private String primaryColor = "#1976D2";

    @Column(name = "secondary_color", length = 9)
    @Builder.Default
    private String secondaryColor = "#424242";

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "theme", length = 10)
    @Builder.Default
    private ThemePreference theme = ThemePreference.LIGHT;

    // === Document templates =================================================

    @Column(name = "invoice_template", length = 100)
    private String invoiceTemplate;

    @Column(name = "label_template", length = 100)
    private String labelTemplate;

    @Column(name = "manifest_template", length = 100)
    private String manifestTemplate;

    @Column(name = "receipt_template", length = 100)
    private String receiptTemplate;

    // ---------------------------------------------------------------- behaviour

    /**
     * A fresh settings row for {@code companyId}, seeded from the company's own identity
     * fields where given, with the field defaults above for everything else.
     */
    public static CompanySettings defaultsFor(UUIDHolder company) {
        CompanySettings settings = CompanySettings.builder()
                .companyName(company.companyName())
                .displayName(company.displayName())
                .supportEmail(company.email())
                .supportMobile(company.mobile())
                .website(company.website())
                .language(orElse(company.language(), "en"))
                .timezone(orElse(company.timezone(), "Asia/Kolkata"))
                .currency(orElse(company.currency(), "INR"))
                .country(company.country())
                .state(company.state())
                .city(company.city())
                .awbPrefix(company.awbPrefixSeed())
                .bookingPrefix(company.awbPrefixSeed())
                .invoicePrefix(company.awbPrefixSeed())
                .build();
        settings.setCompanyId(company.companyId());
        return settings;
    }

    private static String orElse(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * The subset of a {@link com.courier.modules.company.domain.Company} this entity
     * seeds from. A record view rather than the entity itself, so the seeding logic does
     * not depend on the whole aggregate and is trivial to unit-test.
     */
    public record UUIDHolder(java.util.UUID companyId, String companyName, String displayName,
                             String email, String mobile, String website, String language,
                             String timezone, String currency, String country, String state,
                             String city, String awbPrefixSeed) {
    }
}
