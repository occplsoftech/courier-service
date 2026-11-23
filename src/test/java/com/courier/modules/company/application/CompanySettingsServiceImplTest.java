package com.courier.modules.company.application;

import com.courier.modules.company.application.command.CompanySettingsCommand;
import com.courier.modules.company.domain.Company;
import com.courier.modules.company.domain.CompanyRepository;
import com.courier.modules.company.domain.CompanySettings;
import com.courier.modules.company.domain.CompanySettingsRepository;
import com.courier.modules.company.domain.ThemePreference;
import com.courier.modules.company.domain.WeightUnit;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.audit.domain.AuditAction;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import com.courier.shared.company.CompanyContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Company settings rules, with repositories and the audit trail mocked. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CompanySettingsServiceImplTest {

    private static final UUID TENANT = UUID.randomUUID();

    @Mock private CompanySettingsRepository repository;
    @Mock private CompanyRepository companyRepository;
    @Mock private AuditService auditService;

    private CompanySettingsServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CompanySettingsServiceImpl(repository, companyRepository, auditService);
        CompanyContext.setCompanyId(TENANT);
        when(repository.save(any(CompanySettings.class))).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        CompanyContext.clear();
    }

    private Company company() {
        Company company = Company.builder()
                .companyCode("LEGACY_CO").companyName("Legacy Co").email("ops@legacy.test")
                .currency("INR").timezone("Asia/Kolkata").language("en").build();
        company.setCompanyId(TENANT);
        return company;
    }

    private CompanySettings existing() {
        CompanySettings s = CompanySettings.builder().companyName("Legacy Co").build();
        s.setCompanyId(TENANT);
        s.setVersion(3L);
        return s;
    }

    private static CompanySettingsCommand empty() {
        return new CompanySettingsCommand(
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                null,
                null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null,
                null, null);
    }

    // ------------------------------------------------------------------- get / seed

    @Test
    @DisplayName("get creates and seeds the row from the company on first access")
    void getSeedsFromCompany() {
        when(repository.findByCompanyId(TENANT)).thenReturn(Optional.empty());
        when(companyRepository.findByCompanyId(TENANT)).thenReturn(Optional.of(company()));

        CompanySettings settings = service.get();

        assertThat(settings.getCompanyName()).isEqualTo("Legacy Co");
        assertThat(settings.getSupportEmail()).isEqualTo("ops@legacy.test");
        assertThat(settings.getCurrency()).isEqualTo("INR");
        // Field defaults fill the rest.
        assertThat(settings.getGstPercentage()).isEqualByComparingTo("18.00");
        assertThat(settings.getTheme()).isEqualTo(ThemePreference.LIGHT);
        assertThat(settings.getAwbPrefix()).isEqualTo("LEGACY");
        verify(auditService).record(eq(AuditAction.COMPANY_SETTINGS_INITIALIZED), any(), any(), any());
    }

    @Test
    @DisplayName("get returns the existing row without reseeding")
    void getReturnsExisting() {
        when(repository.findByCompanyId(TENANT)).thenReturn(Optional.of(existing()));

        service.get();

        verify(companyRepository, never()).findByCompanyId(any());
        verify(auditService, never()).record(eq(AuditAction.COMPANY_SETTINGS_INITIALIZED),
                any(), any(), any());
    }

    @Test
    @DisplayName("with no company bound, settings are refused")
    void refusedWithoutCompany() {
        CompanyContext.clear();
        assertThatThrownBy(() -> service.get())
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("No company is bound");
    }

    @Test
    @DisplayName("seeding a settings row for a company with no company is a 404")
    void seedMissingCompany() {
        when(repository.findByCompanyId(TENANT)).thenReturn(Optional.empty());
        when(companyRepository.findByCompanyId(TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get()).isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------------- patch

    @Test
    @DisplayName("a section patch applies only supplied fields and leaves the rest unchanged")
    void patchMergesSection() {
        CompanySettings settings = existing();
        settings.setGstPercentage(new BigDecimal("18.00"));
        settings.setAwbPrefix("LEGACY");
        when(repository.findByCompanyId(TENANT)).thenReturn(Optional.of(settings));

        CompanySettingsCommand cmd = new CompanySettingsCommand(
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                new BigDecimal("12.50"), "inv", null, Boolean.TRUE, null, null, null, "nearest_ten", null, null,
                null, null, null, null, null, null,
                null,
                null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null,
                null, null);

        CompanySettings saved = service.patchFinance(cmd);

        assertThat(saved.getGstPercentage()).isEqualByComparingTo("12.50");
        assertThat(saved.getInvoicePrefix()).isEqualTo("INV");  // uppercased
        assertThat(saved.isWalletEnabled()).isTrue();
        assertThat(saved.getRoundOffRule()).isEqualTo("NEAREST_TEN");  // uppercased
        // Untouched by a finance patch.
        assertThat(saved.getAwbPrefix()).isEqualTo("LEGACY");
        verify(auditService).record(eq(AuditAction.COMPANY_SETTINGS_UPDATED), any(), any(), any());
    }

    @Test
    @DisplayName("a patch does not blank fields it did not mention")
    void patchDoesNotBlank() {
        CompanySettings settings = existing();
        settings.setWeightUnit(WeightUnit.KG);
        settings.setBookingPrefix("BK");
        when(repository.findByCompanyId(TENANT)).thenReturn(Optional.of(settings));

        // Shipment patch changing only the weight unit and the default chargeable weight.
        CompanySettingsCommand cmd = new CompanySettingsCommand(
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, WeightUnit.POUND, null, null, null, null, null,
                new BigDecimal("30.000"), null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                null,
                null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null,
                null, null);

        CompanySettings saved = service.patchShipment(cmd);

        assertThat(saved.getWeightUnit()).isEqualTo(WeightUnit.POUND);
        assertThat(saved.getBookingPrefix()).isEqualTo("BK");  // not nulled
        assertThat(saved.getDefaultChargeableWeightKg()).isEqualByComparingTo("30.000");
    }

    // ---------------------------------------------------------------------- replace

    @Test
    @DisplayName("replace spans all sections and normalises")
    void replaceAllSections() {
        CompanySettings settings = existing();
        when(repository.findByCompanyId(TENANT)).thenReturn(Optional.of(settings));

        CompanySettingsCommand cmd = new CompanySettingsCommand(
                null, null, "HELP@Legacy.test", null, null, null, null, "inr", null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                null,
                Boolean.TRUE, null, null, null,
                null, 45, null, null, null,
                null, null, null, null, ThemePreference.DARK,
                null, null, null, null,
                3L, null);

        CompanySettings saved = service.replace(cmd);

        assertThat(saved.getSupportEmail()).isEqualTo("help@legacy.test");  // lowercased
        assertThat(saved.getCurrency()).isEqualTo("INR");                    // uppercased
        assertThat(saved.isSmsEnabled()).isTrue();
        assertThat(saved.getSessionTimeoutMinutes()).isEqualTo(45);
        assertThat(saved.getTheme()).isEqualTo(ThemePreference.DARK);
    }

    @Test
    @DisplayName("replace rejects a stale version")
    void replaceStaleVersion() {
        CompanySettings settings = existing();  // version 3
        when(repository.findByCompanyId(TENANT)).thenReturn(Optional.of(settings));

        CompanySettingsCommand cmd = new CompanySettingsCommand(
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                null,
                null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null,
                1L, null);

        assertThatThrownBy(() -> service.replace(cmd))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("replace with no version does not enforce optimistic locking")
    void replaceWithoutVersion() {
        CompanySettings settings = existing();
        when(repository.findByCompanyId(TENANT)).thenReturn(Optional.of(settings));

        // expectedVersion null: applied without a version check.
        service.replace(empty());

        verify(repository).save(settings);
    }
}
