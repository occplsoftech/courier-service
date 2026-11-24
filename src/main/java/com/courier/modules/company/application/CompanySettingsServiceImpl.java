package com.courier.modules.company.application;

import com.courier.modules.company.application.command.CompanySettingsCommand;
import com.courier.modules.company.domain.Company;
import com.courier.modules.company.domain.CompanyRepository;
import com.courier.modules.company.domain.CompanySettings;
import com.courier.modules.company.domain.CompanySettingsRepository;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.audit.domain.AuditAction;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import com.courier.shared.security.Roles;
import com.courier.shared.security.SecurityUtils;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.config.RedisConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Company settings use cases.
 *
 * <p>Per-method {@code @PreAuthorize}: any authenticated company user may read; only
 * {@code COMPANY_ADMIN} may write. The settings are consumed widely (shipment, finance,
 * notification …), so a read gate narrower than "authenticated" would break those callers.
 *
 * <p><b>Get-or-create.</b> A company always has settings, so {@link #get()} creates the
 * row on first access, seeded from the company's own identity. Every write goes through
 * {@link #loadOrCreate()} too, so the first PUT/PATCH on a brand-new company works without
 * a prior GET.
 *
 * <p><b>Merge, never blank.</b> Both the full replace and the section patches apply only
 * the fields the caller supplied (non-null in the command); an omitted field is left as
 * it was. That is why the same all-optional command drives every write.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompanySettingsServiceImpl implements CompanySettingsService {

    private static final String ENTITY = "CompanySettings";
    private static final String WRITERS = "hasRole('" + Roles.COMPANY_ADMIN + "')";
    // Everyone in the company reads settings; the values drive many modules' behaviour.
    private static final String READERS = "isAuthenticated()";
    // get() is on the Pricing Engine's hot path (freight round-off + GST%, read up to a
    // few times per /pricing/calculate call) — cached under the bucket RedisConfig
    // already provisions for exactly this. Keyed by company, not by user: the same row
    // for every caller in a company. 10-minute TTL from RedisConfig is the safety net if
    // an eviction path is ever missed; every write method below evicts explicitly so that
    // isn't normally relied on.
    private static final String CACHE_KEY = "T(com.courier.shared.company.CompanyContext).requireCompanyId()";

    private final CompanySettingsRepository repository;
    private final CompanyRepository companyRepository;
    private final AuditService auditService;

    @Override
    @Transactional
    @Cacheable(cacheNames = RedisConfig.CACHE_COMPANY_CONFIG, key = CACHE_KEY)
    @PreAuthorize(READERS)
    public CompanySettings get() {
        return loadOrCreate();
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = RedisConfig.CACHE_COMPANY_CONFIG, key = CACHE_KEY)
    @PreAuthorize(WRITERS)
    public CompanySettings replace(CompanySettingsCommand command) {
        CompanySettings settings = loadOrCreate();
        requireCurrentVersion(settings, command.expectedVersion());

        applyGeneral(settings, command);
        applyShipment(settings, command);
        applyFinance(settings, command);
        applySla(settings, command);
        applyEwayBill(settings, command);
        applyNotification(settings, command);
        applySecurity(settings, command);
        applyBranding(settings, command);
        applyDocument(settings, command);

        return save(settings, "all");
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = RedisConfig.CACHE_COMPANY_CONFIG, key = CACHE_KEY)
    @PreAuthorize(WRITERS)
    public CompanySettings patchGeneral(CompanySettingsCommand command) {
        return patch("general", command, s -> applyGeneral(s, command));
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = RedisConfig.CACHE_COMPANY_CONFIG, key = CACHE_KEY)
    @PreAuthorize(WRITERS)
    public CompanySettings patchShipment(CompanySettingsCommand command) {
        return patch("shipment", command, s -> applyShipment(s, command));
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = RedisConfig.CACHE_COMPANY_CONFIG, key = CACHE_KEY)
    @PreAuthorize(WRITERS)
    public CompanySettings patchFinance(CompanySettingsCommand command) {
        return patch("finance", command, s -> applyFinance(s, command));
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = RedisConfig.CACHE_COMPANY_CONFIG, key = CACHE_KEY)
    @PreAuthorize(WRITERS)
    public CompanySettings patchSla(CompanySettingsCommand command) {
        return patch("sla", command, s -> applySla(s, command));
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = RedisConfig.CACHE_COMPANY_CONFIG, key = CACHE_KEY)
    @PreAuthorize(WRITERS)
    public CompanySettings patchEwayBill(CompanySettingsCommand command) {
        return patch("ewayBill", command, s -> applyEwayBill(s, command));
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = RedisConfig.CACHE_COMPANY_CONFIG, key = CACHE_KEY)
    @PreAuthorize(WRITERS)
    public CompanySettings patchSecurity(CompanySettingsCommand command) {
        return patch("security", command, s -> applySecurity(s, command));
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = RedisConfig.CACHE_COMPANY_CONFIG, key = CACHE_KEY)
    @PreAuthorize(WRITERS)
    public CompanySettings patchNotification(CompanySettingsCommand command) {
        return patch("notification", command, s -> applyNotification(s, command));
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = RedisConfig.CACHE_COMPANY_CONFIG, key = CACHE_KEY)
    @PreAuthorize(WRITERS)
    public CompanySettings patchBranding(CompanySettingsCommand command) {
        return patch("branding", command, s -> applyBranding(s, command));
    }

    // -------------------------------------------------------------------- helpers

    private CompanySettings patch(String section, CompanySettingsCommand command,
                                  Consumer<CompanySettings> apply) {
        CompanySettings settings = loadOrCreate();
        apply.accept(settings);
        return save(settings, section);
    }

    private CompanySettings save(CompanySettings settings, String section) {
        CompanySettings saved = repository.save(settings);
        log.info("Company settings [{}] updated for company {} by {}",
                section, saved.getCompanyId(), currentActor());
        auditService.record(AuditAction.COMPANY_SETTINGS_UPDATED, ENTITY, saved.getId(),
                Map.of("section", section, "companyId", saved.getCompanyId().toString()));
        return saved;
    }

    /**
     * The caller's settings, created and seeded from the company on first access. A
     * {@code SUPER_ADMIN} with no bound company has no settings of their own and is
     * refused — settings are inherently company-scoped.
     */
    private CompanySettings loadOrCreate() {
        UUID companyId = CompanyContext.getCompanyId().orElseThrow(() -> new BusinessRuleException(
                "No company is bound to this request. Settings belong to a company, so this "
                        + "must be performed by a user of that company."));

        return repository.findByCompanyId(companyId).orElseGet(() -> seed(companyId));
    }

    private CompanySettings seed(UUID companyId) {
        Company company = companyRepository.findByCompanyId(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Company", companyId));

        CompanySettings settings = CompanySettings.defaultsFor(new CompanySettings.UUIDHolder(
                companyId, company.getCompanyName(), company.getDisplayName(), company.getEmail(),
                company.getMobile(), company.getWebsite(), company.getLanguage(),
                company.getTimezone(), company.getCurrency(), company.getCountry(),
                company.getState(), company.getCity(), awbPrefixSeed(company)));

        CompanySettings saved = repository.save(settings);
        log.info("Initialised company settings for company {}", companyId);
        auditService.record(AuditAction.COMPANY_SETTINGS_INITIALIZED, ENTITY, saved.getId(),
                Map.of("companyId", companyId.toString()));
        return saved;
    }

    /** First six alphanumerics of the company code, matching the AWB prefix seed elsewhere. */
    private String awbPrefixSeed(Company company) {
        String cleaned = company.getCompanyCode().replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        return cleaned.length() <= 6 ? cleaned : cleaned.substring(0, 6);
    }

    private void requireCurrentVersion(CompanySettings settings, Long expectedVersion) {
        if (expectedVersion == null) {
            return;
        }
        if (!Objects.equals(settings.getVersion(), expectedVersion)) {
            throw new ObjectOptimisticLockingFailureException(CompanySettings.class, settings.getId());
        }
    }

    // --- section appliers: each sets a field only when the command supplied it -----

    private void applyGeneral(CompanySettings s, CompanySettingsCommand c) {
        set(c.companyName(), s::setCompanyName);
        set(c.displayName(), s::setDisplayName);
        set(c.supportEmail(), v -> s.setSupportEmail(v.toLowerCase()));
        set(c.supportMobile(), s::setSupportMobile);
        set(c.website(), s::setWebsite);
        set(c.language(), s::setLanguage);
        set(c.timezone(), s::setTimezone);
        set(c.currency(), v -> s.setCurrency(v.toUpperCase()));
        set(c.country(), s::setCountry);
        set(c.state(), s::setState);
        set(c.city(), s::setCity);
    }

    private void applyShipment(CompanySettings s, CompanySettingsCommand c) {
        set(c.awbPrefix(), v -> s.setAwbPrefix(v.toUpperCase()));
        set(c.awbRunningNumber(), s::setAwbRunningNumber);
        set(c.bookingPrefix(), v -> s.setBookingPrefix(v.toUpperCase()));
        set(c.manifestPrefix(), v -> s.setManifestPrefix(v.toUpperCase()));
        set(c.defaultServiceType(), s::setDefaultServiceType);
        set(c.defaultPackageType(), s::setDefaultPackageType);
        set(c.weightUnit(), s::setWeightUnit);
        set(c.dimensionUnit(), s::setDimensionUnit);
        set(c.autoGenerateBarcode(), s::setAutoGenerateBarcode);
        set(c.allowDuplicateReferenceNumber(), s::setAllowDuplicateReferenceNumber);
        set(c.manualStatusOverrideEnabled(), s::setManualStatusOverrideEnabled);
        set(c.autoAssignTrackingNumber(), s::setAutoAssignTrackingNumber);
        set(c.defaultChargeableWeightKg(), s::setDefaultChargeableWeightKg);
        set(c.defaultAppointmentDeliveryCharge(), s::setDefaultAppointmentDeliveryCharge);
        set(c.toPayCharge(), s::setToPayCharge);
        set(c.forwardMinProfitPercent(), s::setForwardMinProfitPercent);
    }

    private void applyFinance(CompanySettings s, CompanySettingsCommand c) {
        set(c.gstPercentage(), s::setGstPercentage);
        set(c.invoicePrefix(), v -> s.setInvoicePrefix(v.toUpperCase()));
        set(c.creditLimit(), s::setCreditLimit);
        set(c.walletEnabled(), s::setWalletEnabled);
        set(c.codEnabled(), s::setCodEnabled);
        set(c.onlinePaymentEnabled(), s::setOnlinePaymentEnabled);
        set(c.autoInvoiceGeneration(), s::setAutoInvoiceGeneration);
        set(c.roundOffRule(), v -> s.setRoundOffRule(v.toUpperCase()));
        set(c.netAmountMaxDecreasePercent(), s::setNetAmountMaxDecreasePercent);
        set(c.netAmountMaxIncreasePercent(), s::setNetAmountMaxIncreasePercent);
    }

    private void applySla(CompanySettings s, CompanySettingsCommand c) {
        set(c.slaBreachTicketEnabled(), s::setSlaBreachTicketEnabled);
        set(c.slaBookingToLoadingSheetHours(), s::setSlaBookingToLoadingSheetHours);
        set(c.slaLoadingSheetToThcHours(), s::setSlaLoadingSheetToThcHours);
        set(c.slaThcToInscanHours(), s::setSlaThcToInscanHours);
        set(c.slaInscanToDrsHours(), s::setSlaInscanToDrsHours);
        set(c.slaDrsToDeliveryHours(), s::setSlaDrsToDeliveryHours);
    }

    private void applyEwayBill(CompanySettings s, CompanySettingsCommand c) {
        set(c.ewayBillMandatoryValue(), s::setEwayBillMandatoryValue);
    }

    private void applyNotification(CompanySettings s, CompanySettingsCommand c) {
        set(c.smsEnabled(), s::setSmsEnabled);
        set(c.emailEnabled(), s::setEmailEnabled);
        set(c.whatsappEnabled(), s::setWhatsappEnabled);
        set(c.pushNotificationEnabled(), s::setPushNotificationEnabled);
    }

    private void applySecurity(CompanySettings s, CompanySettingsCommand c) {
        set(c.passwordPolicy(), s::setPasswordPolicy);
        set(c.sessionTimeoutMinutes(), s::setSessionTimeoutMinutes);
        set(c.maxLoginAttempts(), s::setMaxLoginAttempts);
        set(c.lockDurationMinutes(), s::setLockDurationMinutes);
        set(c.otpExpiryMinutes(), s::setOtpExpiryMinutes);
    }

    private void applyBranding(CompanySettings s, CompanySettingsCommand c) {
        set(c.companyLogo(), s::setCompanyLogo);
        set(c.favicon(), s::setFavicon);
        set(c.primaryColor(), s::setPrimaryColor);
        set(c.secondaryColor(), s::setSecondaryColor);
        set(c.theme(), s::setTheme);
    }

    private void applyDocument(CompanySettings s, CompanySettingsCommand c) {
        set(c.invoiceTemplate(), s::setInvoiceTemplate);
        set(c.labelTemplate(), s::setLabelTemplate);
        set(c.manifestTemplate(), s::setManifestTemplate);
        set(c.receiptTemplate(), s::setReceiptTemplate);
    }

    /** Applies a value only when it was supplied — the merge-not-blank rule, in one place. */
    private static <T> void set(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }

    private String currentActor() {
        return SecurityUtils.getCurrentUser()
                .map(user -> user.email() == null ? user.userId().toString() : user.email())
                .orElse("system");
    }
}
