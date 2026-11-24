package com.courier.modules.company.api.dto;

import com.courier.modules.company.domain.DimensionUnit;
import com.courier.modules.company.domain.ThemePreference;
import com.courier.modules.company.domain.WeightUnit;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A company's full settings. Nulls are serialised so a client can tell "not set" from
 * "field missing"; grouped in the JSON by section via nested records for a settings UI.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "CompanySettingsResponse", description = "Company settings, all sections")
public record CompanySettingsResponse(
        UUID id,
        UUID companyId,
        General general,
        Shipment shipment,
        Finance finance,
        Sla sla,
        EwayBill ewayBill,
        Notification notification,
        Security security,
        Branding branding,
        Document document,
        UUID updatedBy,
        Instant updatedDate,
        Long version
) {

    @Schema(name = "GeneralSettings")
    public record General(String companyName, String displayName, String supportEmail,
                          String supportMobile, String website, String language,
                          String timezone, String currency, String country, String state,
                          String city) {
    }

    @Schema(name = "ShipmentSettings")
    public record Shipment(String awbPrefix, Long awbRunningNumber, String bookingPrefix,
                           String manifestPrefix, String defaultServiceType,
                           String defaultPackageType, WeightUnit weightUnit,
                           DimensionUnit dimensionUnit, boolean autoGenerateBarcode,
                           boolean allowDuplicateReferenceNumber, boolean manualStatusOverrideEnabled,
                           boolean autoAssignTrackingNumber,
                           BigDecimal defaultChargeableWeightKg,
                           BigDecimal defaultAppointmentDeliveryCharge,
                           BigDecimal toPayCharge,
                           BigDecimal forwardMinProfitPercent) {
    }

    @Schema(name = "FinanceSettings")
    public record Finance(BigDecimal gstPercentage, String invoicePrefix, BigDecimal creditLimit,
                          boolean walletEnabled, boolean codEnabled, boolean onlinePaymentEnabled,
                          boolean autoInvoiceGeneration, String roundOffRule,
                          BigDecimal netAmountMaxDecreasePercent, BigDecimal netAmountMaxIncreasePercent,
                          String upiId, String upiPayeeName) {
    }

    @Schema(name = "SlaSettings", description = "Shipment lifecycle SLA hours; breach auto-raises a ticket")
    public record Sla(boolean slaBreachTicketEnabled, int slaBookingToLoadingSheetHours,
                      int slaLoadingSheetToThcHours, int slaThcToInscanHours,
                      int slaInscanToDrsHours, int slaDrsToDeliveryHours) {
    }

    @Schema(name = "EwayBillSettings", description = "E-Way Bill Management's configurable mandatory threshold")
    public record EwayBill(BigDecimal ewayBillMandatoryValue) {
    }

    @Schema(name = "NotificationSettings")
    public record Notification(boolean smsEnabled, boolean emailEnabled, boolean whatsappEnabled,
                               boolean pushNotificationEnabled) {
    }

    @Schema(name = "SecuritySettings")
    public record Security(String passwordPolicy, Integer sessionTimeoutMinutes,
                           Integer maxLoginAttempts, Integer lockDurationMinutes,
                           Integer otpExpiryMinutes) {
    }

    @Schema(name = "BrandingSettings")
    public record Branding(String companyLogo, String favicon, String primaryColor,
                           String secondaryColor, ThemePreference theme) {
    }

    @Schema(name = "DocumentSettings")
    public record Document(String invoiceTemplate, String labelTemplate,
                           String manifestTemplate, String receiptTemplate) {
    }
}
