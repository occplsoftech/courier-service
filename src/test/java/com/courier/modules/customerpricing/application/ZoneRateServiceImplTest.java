package com.courier.modules.customerpricing.application;

import com.courier.modules.customer.application.CustomerService;
import com.courier.modules.customerpricing.application.ZoneRateService.QuoteCommand;
import com.courier.modules.customerpricing.application.ZoneRateService.ServiceQuote;
import com.courier.modules.customerpricing.domain.PricingZone;
import com.courier.modules.customerpricing.domain.PricingZoneRepository;
import com.courier.modules.customerpricing.domain.SlabMode;
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
    @DisplayName("a fixed slab is one flat price: 1 kg and 10 kg both cost 100; the next slab is per kg again")
    void fixedSlabIsAFlatPrice() {
        ZoneRate cargo = ZoneRate.builder().zoneId(zone.getId()).customerId(CUSTOMER).serviceTypeId(CARGO).build();
        cargo.setId(UUID.randomUUID());
        List<ZoneRateSlab> two = List.of(
                ZoneRateSlab.builder().minWeight(BigDecimal.ZERO).maxWeight(BigDecimal.TEN).ratePerKg(new BigDecimal("100")).fixedRate(true).build(),
                ZoneRateSlab.builder().minWeight(BigDecimal.TEN).maxWeight(null).ratePerKg(new BigDecimal("9")).build());
        when(slabs.findByRate(COMPANY, cargo.getId())).thenReturn(two);
        when(rates.findByZoneAndServiceType(COMPANY, zone.getId(), CARGO)).thenReturn(Optional.of(cargo));

        ZoneRateService.Quote one = service.calculate(new QuoteCommand(CUSTOMER, DISTRICT, null, BigDecimal.ONE, BigDecimal.ZERO, false, LocalDate.now(), CARGO));
        ZoneRateService.Quote ten = service.calculate(new QuoteCommand(CUSTOMER, DISTRICT, null, BigDecimal.TEN, BigDecimal.ZERO, false, LocalDate.now(), CARGO));
        ZoneRateService.Quote twelve = service.calculate(new QuoteCommand(CUSTOMER, DISTRICT, null, new BigDecimal("12"), BigDecimal.ZERO, false, LocalDate.now(), CARGO));

        assertThat(one.freight()).isEqualByComparingTo("100");
        assertThat(one.fixedRate()).isTrue();
        assertThat(ten.freight()).isEqualByComparingTo("100");
        assertThat(ten.ratePerKg()).isEqualByComparingTo("10.00");
        assertThat(twelve.freight()).isEqualByComparingTo("108");
        assertThat(twelve.fixedRate()).isFalse();
    }

    @Test
    @DisplayName("a minimum chargeable weight of 20 kg prices a 5 kg parcel as 20 kg")
    void minimumChargeableWeight() {
        ZoneRate cargo = ZoneRate.builder().zoneId(zone.getId()).customerId(CUSTOMER).serviceTypeId(CARGO).minChargeableWeight(new BigDecimal("20")).build();
        cargo.setId(UUID.randomUUID());
        when(slabs.findByRate(COMPANY, cargo.getId())).thenReturn(List.of(
                ZoneRateSlab.builder().minWeight(BigDecimal.ZERO).maxWeight(null).ratePerKg(new BigDecimal("25")).build()));
        when(rates.findByZoneAndServiceType(COMPANY, zone.getId(), CARGO)).thenReturn(Optional.of(cargo));

        ZoneRateService.Quote q = service.calculate(new QuoteCommand(CUSTOMER, DISTRICT, null, new BigDecimal("5"), BigDecimal.ZERO, false, LocalDate.now(), CARGO));

        assertThat(q.chargeableWeight()).isEqualByComparingTo("20");
        assertThat(q.freight()).isEqualByComparingTo("500");
    }

    private ZoneRateService.Quote priceCargo(ZoneRate card, List<ZoneRateSlab> cardSlabs, String kg) {
        card.setId(UUID.randomUUID());
        when(slabs.findByRate(COMPANY, card.getId())).thenReturn(cardSlabs);
        when(rates.findByZoneAndServiceType(COMPANY, zone.getId(), CARGO)).thenReturn(Optional.of(card));
        return service.calculate(new QuoteCommand(CUSTOMER, DISTRICT, null, new BigDecimal(kg), BigDecimal.ZERO, false, LocalDate.now(), CARGO));
    }

    private static List<ZoneRateSlab> fortyThenThirty() {
        return List.of(
                ZoneRateSlab.builder().minWeight(BigDecimal.ZERO).maxWeight(new BigDecimal("5")).ratePerKg(new BigDecimal("40")).build(),
                ZoneRateSlab.builder().minWeight(new BigDecimal("5")).maxWeight(new BigDecimal("10")).ratePerKg(new BigDecimal("30")).build(),
                ZoneRateSlab.builder().minWeight(BigDecimal.TEN).maxWeight(null).ratePerKg(new BigDecimal("20")).build());
    }

    @Test
    @DisplayName("minimum freight: a parcel whose slab freight is below it is charged the minimum, and fuel % is on the minimum")
    void minimumFreight() {
        ZoneRate card = ZoneRate.builder().zoneId(zone.getId()).customerId(CUSTOMER).serviceTypeId(CARGO)
                .minFreight(new BigDecimal("60")).fuelSurchargePercent(BigDecimal.TEN).build();
        ZoneRateService.Quote light = priceCargo(card, fortyThenThirty(), "0.5");
        assertThat(light.freight()).isEqualByComparingTo("60");
        assertThat(light.minFreightApplied()).isTrue();
        assertThat(light.fuelSurcharge()).isEqualByComparingTo("6");
        assertThat(light.total()).isEqualByComparingTo("66");

        ZoneRateService.Quote heavy = service.calculate(new QuoteCommand(CUSTOMER, DISTRICT, null, new BigDecimal("3"), BigDecimal.ZERO, false, LocalDate.now(), CARGO));
        assertThat(heavy.freight()).isEqualByComparingTo("120");
        assertThat(heavy.minFreightApplied()).isFalse();
        assertThat(heavy.ratePerKg()).isEqualByComparingTo("40");
    }

    @Test
    @DisplayName("whole-weight pricing (default) charges every kg at the slab the weight falls in: 7 kg = 7 x 30")
    void wholeWeightIsTheDefault() {
        ZoneRate card = ZoneRate.builder().zoneId(zone.getId()).customerId(CUSTOMER).serviceTypeId(CARGO).build();
        ZoneRateService.Quote q = priceCargo(card, fortyThenThirty(), "7");
        assertThat(q.freight()).isEqualByComparingTo("210");
        assertThat(q.tiered()).isFalse();
    }

    @Test
    @DisplayName("tiered pricing charges each slab's own kilos: 7 kg = 5 x 40 + 2 x 30; 12 kg = 200 + 150 + 2 x 20; 5 kg = 5 x 40")
    void tieredSlabs() {
        ZoneRate card = ZoneRate.builder().zoneId(zone.getId()).customerId(CUSTOMER).serviceTypeId(CARGO).slabMode(SlabMode.TIERED).build();
        ZoneRateService.Quote seven = priceCargo(card, fortyThenThirty(), "7");
        assertThat(seven.freight()).isEqualByComparingTo("260");
        assertThat(seven.tiered()).isTrue();
        assertThat(seven.ratePerKg()).isEqualByComparingTo("37.14");
        assertThat(ZoneRateServiceImpl.tieredFreight(fortyThenThirty(), new BigDecimal("12"))).isEqualByComparingTo("390");
        assertThat(ZoneRateServiceImpl.tieredFreight(fortyThenThirty(), new BigDecimal("5"))).isEqualByComparingTo("200");
        assertThat(ZoneRateServiceImpl.tieredFreight(fortyThenThirty(), new BigDecimal("0.5"))).isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("tiered with a fixed first slab: the flat price, then the next slab's kilos per kg")
    void tieredWithFixedSlab() {
        List<ZoneRateSlab> fixedFirst = List.of(
                ZoneRateSlab.builder().minWeight(BigDecimal.ZERO).maxWeight(BigDecimal.TEN).ratePerKg(new BigDecimal("100")).fixedRate(true).build(),
                ZoneRateSlab.builder().minWeight(BigDecimal.TEN).maxWeight(null).ratePerKg(new BigDecimal("9")).build());
        assertThat(ZoneRateServiceImpl.tieredFreight(fixedFirst, new BigDecimal("3"))).isEqualByComparingTo("100");
        assertThat(ZoneRateServiceImpl.tieredFreight(fixedFirst, new BigDecimal("12"))).isEqualByComparingTo("118");
    }

    @Test
    @DisplayName("save keeps the minimum freight and slab pricing; a negative minimum freight is refused")
    void saveExtras() {
        when(zones.findByIdWithinCompany(zone.getId(), COMPANY)).thenReturn(Optional.of(zone));
        when(rates.findByZoneAndServiceType(COMPANY, zone.getId(), null)).thenReturn(Optional.empty());
        org.mockito.ArgumentCaptor<ZoneRate> saved = org.mockito.ArgumentCaptor.forClass(ZoneRate.class);
        // stop once the card is handed to the repository: only what is stored matters here
        when(rates.save(saved.capture())).thenThrow(new IllegalStateException("stored"));
        ZoneRateService.SaveCommand ok = new ZoneRateService.SaveCommand(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null, List.of(slab("0", null, "40")), null, new BigDecimal("60"), SlabMode.TIERED);
        assertThatThrownBy(() -> service.save(zone.getId(), null, ok)).hasMessage("stored");
        assertThat(saved.getValue().getMinFreight()).isEqualByComparingTo("60");
        assertThat(saved.getValue().getSlabMode()).isEqualTo(SlabMode.TIERED);

        ZoneRateService.SaveCommand old = save(BigDecimal.ZERO, slab("0", null, "40"));
        assertThatThrownBy(() -> service.save(zone.getId(), null, old)).hasMessage("stored");
        assertThat(saved.getValue().getMinFreight()).isEqualByComparingTo("0");
        assertThat(saved.getValue().getSlabMode()).isEqualTo(SlabMode.WHOLE);

        ZoneRateService.SaveCommand bad = new ZoneRateService.SaveCommand(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null, List.of(slab("0", null, "40")), null, new BigDecimal("-1"), null);
        assertThatThrownBy(() -> service.save(zone.getId(), null, bad)).isInstanceOf(BusinessRuleException.class).hasMessageContaining("Minimum freight");
    }

    private ZoneRateService.SaveCommand save(BigDecimal minChargeable, ZoneRateService.SlabInput... slabs) {
        return new ZoneRateService.SaveCommand(minChargeable, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, null, null, List.of(slabs), null);
    }

    private static ZoneRateService.SlabInput slab(String min, String max, String rate) {
        return new ZoneRateService.SlabInput(new BigDecimal(min), max == null ? null : new BigDecimal(max), new BigDecimal(rate));
    }

    @Test
    @DisplayName("slabs that leave a gap, start above 0 or stop short of the minimum chargeable weight are refused on save")
    void unpriceableSlabsAreRefused() {
        when(zones.findByIdWithinCompany(zone.getId(), COMPANY)).thenReturn(Optional.of(zone));

        assertThatThrownBy(() -> service.save(zone.getId(), null, save(BigDecimal.ONE, slab("0", "5", "50"), slab("6", null, "40"))))
                .hasMessageContaining("gap").hasMessageContaining("5 kg").hasMessageContaining("6 kg");
        assertThatThrownBy(() -> service.save(zone.getId(), null, save(BigDecimal.ONE, slab("1", "5", "50"))))
                .hasMessageContaining("must start at 0 kg");
        assertThatThrownBy(() -> service.save(zone.getId(), null, save(new BigDecimal("20"), slab("0", "10", "50"))))
                .hasMessageContaining("above the heaviest slab (10 kg)");
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
