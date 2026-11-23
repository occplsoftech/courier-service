package com.courier.modules.company.application.command;

import com.courier.modules.company.domain.DimensionUnit;
import com.courier.modules.company.domain.ThemePreference;
import com.courier.modules.company.domain.WeightUnit;

import java.math.BigDecimal;

/**
 * Input to the settings use cases. Mirrors the request DTO so {@code application} does not
 * depend on {@code api}. Every field is nullable and <b>null means "leave unchanged"</b> —
 * both the full replace and the section patches merge only the values that were supplied.
 */
public record CompanySettingsCommand(
        String companyName, String displayName, String supportEmail, String supportMobile,
        String website, String language, String timezone, String currency,
        String country, String state, String city,

        String awbPrefix, Long awbRunningNumber, String bookingPrefix, String manifestPrefix,
        String defaultServiceType, String defaultPackageType, WeightUnit weightUnit,
        DimensionUnit dimensionUnit, Boolean autoGenerateBarcode,
        Boolean allowDuplicateReferenceNumber, Boolean manualStatusOverrideEnabled,
        Boolean autoAssignTrackingNumber,
        BigDecimal defaultChargeableWeightKg, BigDecimal defaultAppointmentDeliveryCharge,
        BigDecimal toPayCharge,

        BigDecimal gstPercentage, String invoicePrefix, BigDecimal creditLimit,
        Boolean walletEnabled, Boolean codEnabled, Boolean onlinePaymentEnabled,
        Boolean autoInvoiceGeneration, String roundOffRule,
        BigDecimal netAmountMaxDecreasePercent, BigDecimal netAmountMaxIncreasePercent,

        Boolean slaBreachTicketEnabled, Integer slaBookingToLoadingSheetHours,
        Integer slaLoadingSheetToThcHours, Integer slaThcToInscanHours,
        Integer slaInscanToDrsHours, Integer slaDrsToDeliveryHours,

        BigDecimal ewayBillMandatoryValue,

        Boolean smsEnabled, Boolean emailEnabled, Boolean whatsappEnabled,
        Boolean pushNotificationEnabled,

        String passwordPolicy, Integer sessionTimeoutMinutes, Integer maxLoginAttempts,
        Integer lockDurationMinutes, Integer otpExpiryMinutes,

        String companyLogo, String favicon, String primaryColor, String secondaryColor,
        ThemePreference theme,

        String invoiceTemplate, String labelTemplate, String manifestTemplate,
        String receiptTemplate,

        Long expectedVersion,

        BigDecimal forwardMinProfitPercent
) {
}
