package com.courier.modules.tariff.application;

import com.courier.modules.company.domain.Company;
import com.courier.modules.company.domain.CompanyRepository;
import com.courier.shared.security.AuthenticatedUser;
import com.courier.shared.security.SecurityUtils;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicRateServiceTest {

    private final UUID companyId = UUID.randomUUID();
    private final TariffQuoteService quotes = mock(TariffQuoteService.class);
    private final CompanyRepository companies = mock(CompanyRepository.class);
    private final Company company = mock(Company.class);
    private final PublicRateService service = new PublicRateService(quotes, companies);
    private final TariffQuoteService.QuoteInput input = new TariffQuoteService.QuoteInput("411001", "400001", null, null,
            BigDecimal.ONE, null, null, null, null, BigDecimal.TEN, false, null);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "companyCode", "OCCPL");
        when(company.isActive()).thenReturn(true);
        when(company.getCompanyId()).thenReturn(companyId);
        when(companies.findByCompanyCode("OCCPL")).thenReturn(Optional.of(company));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        CompanyContext.clear();
    }

    @Test
    void quotesTheConfiguredCompanyWithoutRolesAndLeavesNothingBound() {
        when(quotes.quoteAll(any())).thenAnswer(i -> {
            assertThat(CompanyContext.getCompanyId()).contains(companyId);
            AuthenticatedUser actor = SecurityUtils.requireCurrentUser();
            assertThat(actor.authorities()).isEmpty();
            return new TariffQuoteService.Options(List.of(mock(TariffQuoteService.Quote.class)), List.of());
        });
        assertThat(service.rates(input).quotes()).hasSize(1);
        assertThat(CompanyContext.isSet()).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void hidesTheTariffSetupAndUnbindsOnFailure() {
        when(quotes.quoteAll(any())).thenThrow(new BusinessRuleException("No active rate card for from zone PUNE"));
        assertThatThrownBy(() -> service.rates(input)).hasMessage(PublicRateService.NO_RATE);
        assertThat(CompanyContext.isSet()).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void divisorsForTheConfiguredCompanyWithoutRoles() {
        when(quotes.volumetricDivisors()).thenAnswer(i -> {
            assertThat(CompanyContext.getCompanyId()).contains(companyId);
            assertThat(SecurityUtils.requireCurrentUser().authorities()).isEmpty();
            return List.of(new TariffQuoteService.ServiceDivisor("SURFACE", "Surface", 4500));
        });
        assertThat(service.divisors()).extracting(TariffQuoteService.ServiceDivisor::divisor).containsExactly(4500);
        assertThat(CompanyContext.isSet()).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void noOptionsMeansNoRate() {
        when(quotes.quoteAll(any())).thenReturn(new TariffQuoteService.Options(List.of(), List.of()));
        assertThatThrownBy(() -> service.rates(input)).hasMessage(PublicRateService.NO_RATE);
    }

    @Test
    void offWhenNoCompanyIsConfiguredOrItIsInactive() {
        when(company.isActive()).thenReturn(false);
        assertThatThrownBy(() -> service.rates(input)).isInstanceOf(BusinessRuleException.class);
        ReflectionTestUtils.setField(service, "companyCode", "");
        assertThatThrownBy(() -> service.rates(input)).isInstanceOf(BusinessRuleException.class);
        verify(quotes, never()).quoteAll(any());
    }
}
