package com.courier.modules.company.api;

import com.courier.modules.company.api.dto.CompanySettingsRequest;
import com.courier.modules.company.api.dto.CompanySettingsResponse;
import com.courier.modules.company.application.command.CompanySettingsCommand;
import com.courier.modules.company.domain.CompanySettings;
import org.springframework.stereotype.Component;

/** Wire contract ↔ application/domain types for company settings. */
@Component
public class CompanySettingsMapper {

    public CompanySettingsCommand toCommand(CompanySettingsRequest r) {
        return new CompanySettingsCommand(
                r.companyName(), r.displayName(), r.supportEmail(), r.supportMobile(), r.website(),
                r.language(), r.timezone(), r.currency(), r.country(), r.state(), r.city(),
                r.awbPrefix(), r.awbRunningNumber(), r.bookingPrefix(), r.manifestPrefix(),
                r.defaultServiceType(), r.defaultPackageType(), r.weightUnit(), r.dimensionUnit(),
                r.autoGenerateBarcode(), r.allowDuplicateReferenceNumber(),
                r.manualStatusOverrideEnabled(), r.autoAssignTrackingNumber(),
                r.defaultChargeableWeightKg(), r.defaultAppointmentDeliveryCharge(), r.toPayCharge(),
                r.gstPercentage(), r.invoicePrefix(), r.creditLimit(), r.walletEnabled(),
                r.codEnabled(), r.onlinePaymentEnabled(), r.autoInvoiceGeneration(), r.roundOffRule(),
                r.netAmountMaxDecreasePercent(), r.netAmountMaxIncreasePercent(),
                r.slaBreachTicketEnabled(), r.slaBookingToLoadingSheetHours(),
                r.slaLoadingSheetToThcHours(), r.slaThcToInscanHours(),
                r.slaInscanToDrsHours(), r.slaDrsToDeliveryHours(),
                r.ewayBillMandatoryValue(),
                r.smsEnabled(), r.emailEnabled(), r.whatsappEnabled(), r.pushNotificationEnabled(),
                r.passwordPolicy(), r.sessionTimeoutMinutes(), r.maxLoginAttempts(),
                r.lockDurationMinutes(), r.otpExpiryMinutes(),
                r.companyLogo(), r.favicon(), r.primaryColor(), r.secondaryColor(), r.theme(),
                r.invoiceTemplate(), r.labelTemplate(), r.manifestTemplate(), r.receiptTemplate(),
                r.version(), r.forwardMinProfitPercent());
    }

    public CompanySettingsResponse toResponse(CompanySettings s) {
        return new CompanySettingsResponse(
                s.getId(),
                s.getCompanyId(),
                new CompanySettingsResponse.General(
                        s.getCompanyName(), s.getDisplayName(), s.getSupportEmail(),
                        s.getSupportMobile(), s.getWebsite(), s.getLanguage(), s.getTimezone(),
                        s.getCurrency(), s.getCountry(), s.getState(), s.getCity()),
                new CompanySettingsResponse.Shipment(
                        s.getAwbPrefix(), s.getAwbRunningNumber(), s.getBookingPrefix(),
                        s.getManifestPrefix(), s.getDefaultServiceType(), s.getDefaultPackageType(),
                        s.getWeightUnit(), s.getDimensionUnit(), s.isAutoGenerateBarcode(),
                        s.isAllowDuplicateReferenceNumber(), s.isManualStatusOverrideEnabled(),
                        s.isAutoAssignTrackingNumber(),
                        s.getDefaultChargeableWeightKg(), s.getDefaultAppointmentDeliveryCharge(),
                        s.getToPayCharge(), s.getForwardMinProfitPercent()),
                new CompanySettingsResponse.Finance(
                        s.getGstPercentage(), s.getInvoicePrefix(), s.getCreditLimit(),
                        s.isWalletEnabled(), s.isCodEnabled(), s.isOnlinePaymentEnabled(),
                        s.isAutoInvoiceGeneration(), s.getRoundOffRule(),
                        s.getNetAmountMaxDecreasePercent(), s.getNetAmountMaxIncreasePercent()),
                new CompanySettingsResponse.Sla(
                        s.isSlaBreachTicketEnabled(), s.getSlaBookingToLoadingSheetHours(),
                        s.getSlaLoadingSheetToThcHours(), s.getSlaThcToInscanHours(),
                        s.getSlaInscanToDrsHours(), s.getSlaDrsToDeliveryHours()),
                new CompanySettingsResponse.EwayBill(s.getEwayBillMandatoryValue()),
                new CompanySettingsResponse.Notification(
                        s.isSmsEnabled(), s.isEmailEnabled(), s.isWhatsappEnabled(),
                        s.isPushNotificationEnabled()),
                new CompanySettingsResponse.Security(
                        s.getPasswordPolicy(), s.getSessionTimeoutMinutes(), s.getMaxLoginAttempts(),
                        s.getLockDurationMinutes(), s.getOtpExpiryMinutes()),
                new CompanySettingsResponse.Branding(
                        s.getCompanyLogo(), s.getFavicon(), s.getPrimaryColor(),
                        s.getSecondaryColor(), s.getTheme()),
                new CompanySettingsResponse.Document(
                        s.getInvoiceTemplate(), s.getLabelTemplate(), s.getManifestTemplate(),
                        s.getReceiptTemplate()),
                s.getUpdatedBy(),
                s.getUpdatedAt(),
                s.getVersion());
    }
}
