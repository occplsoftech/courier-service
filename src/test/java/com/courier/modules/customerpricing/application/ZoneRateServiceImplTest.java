package com.courier.modules.customerpricing.application;

import com.courier.modules.customer.application.CustomerService;
import com.courier.modules.customerpricing.application.ZoneRateService.QuoteCommand;
import com.courier.modules.customerpricing.application.ZoneRateService.ServiceQuote;
import com.courier.modules.customerpricing.domain.PricingZone;
import com.courier.modules.customerpricing.domain.PricingZoneRepository;
import com.courier.modules.customerpricing.domain.ZoneRate;
import com.courier.modules.customerpricing.domain.ZoneRateRepository;
import com.courier.modules.customerpricing.domain.ZoneRateSlab;
import com.courier.modules.customerpricing.domain.ZoneRateSlabRepository;
import com.courier.modules.master.domain.ServiceType;
import com.courier.modules.master.domain.ServiceTypeRepository;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ZoneRateServiceImplTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID DISTRICT = UUID.randomUUID();
    private static final UUID AIR = UUID.randomUUID();
    private static final UUID SURFACE = UUID.randomUUID();
    private static final UUID CARGO = UUID.randomUUID();

    @Mock private ZoneRateRepository rates;
    @Mock private ZoneRateSlabRepository slabs;
    @Mock private PricingZoneRepository zones;
    @Mock private PricingZoneService zoneService;
    @Mock private CustomerService customerService;
    @Mock private ServiceTypeRepository serviceTypes;
    @Mock private AuditService auditService;

    private ZoneRateServiceImpl service;
    private PricingZone zone;
    private ZoneRate air, surface, all;

    @BeforeEach
    void setUp() {
        CompanyContext.setCompanyId(COMPANY);
        service = new ZoneRateServiceImpl(rates, slabs, zones, zoneService, customerService, serviceTypes, auditService);
        zone = PricingZone.builder().customerId(CUSTOMER).zoneName("Pune").build();
        zone.setId(UUID.randomUUID());
        when(zoneService.resolve(eq(CUSTOMER), any(), any())).thenReturn(Optional.of(new PricingZoneView(zone, "Acme", List.of(), true)));
        air = card(AIR, "100");
        surface = card(SURFACE, "40");
        all = card(null, "70");
        type(AIR, "AIR", "Air");
        type(SURFACE, "SURFACE", "Surface");
    }

    @AfterEach
    void tearDown() {
        CompanyContext.clear();
    }

    private ZoneRate card(UUID serviceType, String ratePerKg) {
        ZoneRate r = ZoneRate.builder().zoneId(zone.getId()).customerId(CUSTOMER).serviceTypeId(serviceType).build();
        r.setId(UUID.randomUUID());
        ZoneRateSlab slab = ZoneRateSlab.builder().minWeight(BigDecimal.ZERO).maxWeight(null).ratePerKg(new BigDecimal(ratePerKg)).build();
        when(slabs.findByRate(COMPANY, r.getId())).thenReturn(List.of(slab));
        when(rates.findByZoneAndServiceType(COMPANY, zone.getId(), serviceType)).thenReturn(Optional.of(r));
        return r;
    }

    private void type(UUID id, String code, String name) {
        ServiceType t = new ServiceType();
        t.setId(id);
        t.setCode(code);
        t.setName(name);
        when(serviceTypes.findByIdWithinCompany(id, COMPANY)).thenReturn(Optional.of(t));
    }

    private QuoteCommand cmd(UUID serviceType) {
        return new QuoteCommand(CUSTOMER, DISTRICT, null, new BigDecimal("2"), new BigDecimal("1000"), false, LocalDate.now(), serviceType);
    }

    @Test
    @DisplayName("each service type is priced from its own card")
    void ownCardPerServiceType() {
        assertThat(service.calculate(cmd(AIR)).ratePerKg()).isEqualByComparingTo("100");
        assertThat(service.calculate(cmd(SURFACE)).ratePerKg()).isEqualByComparingTo("40");
    }

    @Test
    @DisplayName("a service type with no card of its own uses the all-services card")
    void fallsBackToTheAllServicesCard() {
        assertThat(service.calculate(cmd(CARGO)).ratePerKg()).isEqualByComparingTo("70");
        assertThat(service.calculate(cmd(null)).ratePerKg()).isEqualByComparingTo("70");
    }

    @Test
    @DisplayName("no card for the service type and no all-services card is refused, not priced from another type")
    void noCardNoPrice() {
        when(rates.findByZoneAndServiceType(COMPANY, zone.getId(), null)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.calculate(cmd(CARGO))).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("no active rate");
    }

    @Test
    @DisplayName("calculateEach prices every active card, skipping inactive ones")
    void priceEveryCard() {
        when(rates.findAllByZone(COMPANY, zone.getId())).thenReturn(List.of(air, surface));
        surface.deactivate();

        List<ServiceQuote> quotes = service.calculateEach(cmd(null));

        assertThat(quotes).hasSize(1);
        assertThat(quotes.get(0).serviceTypeCode()).isEqualTo("AIR");
        assertThat(quotes.get(0).quote().ratePerKg()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("calculateEach is empty when the customer has no zone for the destination")
    void noZone() {
        when(zoneService.resolve(eq(CUSTOMER), any(), any())).thenReturn(Optional.empty());
        assertThat(service.calculateEach(cmd(null))).isEmpty();
    }
}
