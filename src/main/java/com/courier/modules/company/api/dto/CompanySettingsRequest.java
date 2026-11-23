package com.courier.modules.company.api.dto;

import com.courier.modules.company.domain.DimensionUnit;
import com.courier.modules.company.domain.ThemePreference;
import com.courier.modules.company.domain.WeightUnit;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Body for the settings endpoints.
 *
 * <p>Every field is optional. The full {@code PUT} applies all provided fields; each
 * {@code PATCH /section} reads only that section's fields and ignores the rest — the
 * single shared DTO is deliberate (the spec names exactly one request type). Bean
 * Validation covers shape; there are no cross-field rules.
 *
 * <p>{@code version} is used only by the full {@code PUT}, to reject a stale write with
 * {@code 409}. The section PATCHes are last-write-wins on their own narrow field set.
 */
@Schema(name = "CompanySettingsRequest", description = "Company settings (all fields optional)")
public record CompanySettingsRequest(

        // --- general / regional
        @Size(max = 150) String companyName,
        @Size(max = 100) String displayName,
        @Email @Size(max = 255) String supportEmail,
        @Pattern(regexp = "^$|^[+]?[0-9 \\-]{7,20}$", message = "must be a valid phone number")
        String supportMobile,
        @Size(max = 255)
        @Pattern(regexp = "^$|^https?://.+", message = "must start with http:// or https://")
        String website,
        @Size(max = 10) String language,
        @Size(max = 64) String timezone,
        @Pattern(regexp = "^$|^[A-Za-z]{3}$", message = "must be a 3-letter ISO-4217 code")
        String currency,
        @Size(max = 100) String country,
        @Size(max = 100) String state,
        @Size(max = 100) String city,

        // --- shipment
        @Size(max = 20) String awbPrefix,
        @PositiveOrZero Long awbRunningNumber,
        @Size(max = 20) String bookingPrefix,
        @Size(max = 20) String manifestPrefix,
        @Size(max = 40) String defaultServiceType,
        @Size(max = 40) String defaultPackageType,
        WeightUnit weightUnit,
        DimensionUnit dimensionUnit,
        Boolean autoGenerateBarcode,
        Boolean allowDuplicateReferenceNumber,
        @Schema(description = "Opt-in: lets a COMPANY_ADMIN/BRANCH_MANAGER manually override a "
                + "shipment's status, bypassing the normal THC/DRS/Deliver flow")
        Boolean manualStatusOverrideEnabled,
        Boolean autoAssignTrackingNumber,
        @DecimalMin(value = "0.001", message = "must be greater than zero")
        @Digits(integer = 7, fraction = 3) BigDecimal defaultChargeableWeightKg,
        @DecimalMin("0.0") @Digits(integer = 15, fraction = 4)
        @Schema(description = "Prefilled onto the Appointment Charge field when Appointment Delivery is checked at booking time")
        BigDecimal defaultAppointmentDeliveryCharge,
        @DecimalMin("0.0") @Digits(integer = 15, fraction = 4)
        @Schema(description = "Flat charge added (with GST) to every To-Pay booking; 0 disables it")
        BigDecimal toPayCharge,

        // --- finance
        @DecimalMin("0.0") @DecimalMax("100.0") @Digits(integer = 3, fraction = 2)
        @Schema(description = "GST percentage, 0–100") BigDecimal gstPercentage,
        @Size(max = 20) String invoicePrefix,
        @DecimalMin("0.0") @Digits(integer = 15, fraction = 4) BigDecimal creditLimit,
        Boolean walletEnabled,
        Boolean codEnabled,
        Boolean onlinePaymentEnabled,
        Boolean autoInvoiceGeneration,
        @Pattern(regexp = "^$|^(NONE|NEAREST_ONE|NEAREST_FIVE|NEAREST_TEN)$",
                flags = Pattern.Flag.CASE_INSENSITIVE,
                message = "must be one of NONE, NEAREST_ONE, NEAREST_FIVE, NEAREST_TEN")
        @Schema(description = "How Shipment Booking's final amount is rounded")
        String roundOffRule,
        @DecimalMin("0.0") @DecimalMax("100.0") @Digits(integer = 3, fraction = 2)
        @Schema(description = "Max percent Shipment Booking's editable Net Amount may be decreased below the computed amount")
        BigDecimal netAmountMaxDecreasePercent,
        @DecimalMin("0.0") @DecimalMax("999.99") @Digits(integer = 3, fraction = 2)
        @Schema(description = "Max percent Shipment Booking's editable Net Amount may be increased above the computed amount")
        BigDecimal netAmountMaxIncreasePercent,

        // --- SLA (shipment lifecycle) — hours a shipment may sit in one status
        // before an auto-ticket is raised; capped at 720h (30 days)
        Boolean slaBreachTicketEnabled,
        @Min(1) @Max(720) Integer slaBookingToLoadingSheetHours,
        @Min(1) @Max(720) Integer slaLoadingSheetToThcHours,
        @Min(1) @Max(720) Integer slaThcToInscanHours,
        @Min(1) @Max(720) Integer slaInscanToDrsHours,
        @Min(1) @Max(720) Integer slaDrsToDeliveryHours,

        // --- E-Way Bill
        @DecimalMin(value = "0.0", inclusive = false, message = "must be greater than zero")
        @Digits(integer = 15, fraction = 4)
        @Schema(description = "Invoice value above which an E-Way Bill is mandatory before AWB generation")
        BigDecimal ewayBillMandatoryValue,

        // --- notification
        Boolean smsEnabled,
        Boolean emailEnabled,
        Boolean whatsappEnabled,
        Boolean pushNotificationEnabled,

        // --- security (stored per company; not yet consumed by auth)
        @Size(max = 40) String passwordPolicy,
        @Min(1) @Max(1440) Integer sessionTimeoutMinutes,
        @Min(1) @Max(20) Integer maxLoginAttempts,
        @Min(1) @Max(1440) Integer lockDurationMinutes,
        @Min(1) @Max(60) Integer otpExpiryMinutes,

        // --- branding
        @Size(max = 500) String companyLogo,
        @Size(max = 500) String favicon,
        @Pattern(regexp = "^$|^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$",
                message = "must be a hex colour like #1976D2")
        String primaryColor,
        @Pattern(regexp = "^$|^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$",
                message = "must be a hex colour like #424242")
        String secondaryColor,
        ThemePreference theme,

        // --- document templates
        @Size(max = 100) String invoiceTemplate,
        @Size(max = 100) String labelTemplate,
        @Size(max = 100) String manifestTemplate,
        @Size(max = 100) String receiptTemplate,

        @PositiveOrZero
        @Schema(description = "Version last read; required for the full PUT, ignored by PATCH")
        Long version,

        @DecimalMin("0.0") @DecimalMax("100.0") @Digits(integer = 3, fraction = 2)
        @Schema(description = "Forwarding: offer only couriers whose cost leaves at least this % of the booked amount as "
                + "profit (10 = a ₹100 booking is offered couriers up to ₹90)")
        BigDecimal forwardMinProfitPercent
) {
}
