package com.courier.modules.districtfreight.application;

import com.courier.modules.company.application.CompanySettingsService;
import com.courier.modules.company.domain.CompanySettings;
import com.courier.modules.customerpricing.application.CustomerRateStructureService;
import com.courier.modules.customerpricing.application.PricingZoneService;
import com.courier.modules.customerpricing.application.ZoneRateService;
import com.courier.modules.districtfreight.domain.NetworkCoveragePort;
import com.courier.modules.districtfreight.domain.PincodeCoverageLookupPort;
import com.courier.modules.districtfreight.domain.ZoneTariffPort;
import com.courier.modules.master.domain.PackageType;
import com.courier.modules.master.domain.PackageTypeRepository;
import com.courier.modules.master.domain.ServiceType;
import com.courier.modules.master.domain.ServiceTypeRepository;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AllRateCalculatorServiceTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();

    @Mock private ServiceTypeRepository serviceTypes;
    @Mock private PackageTypeRepository packageTypes;
    @Mock private PincodeCoverageLookupPort coverageLookup;
    @Mock private NetworkCoveragePort networkCoverage;
    @Mock private ZoneTariffPort zoneTariff;
    @Mock private PricingZoneService zoneService;
    @Mock private ZoneRateService zoneRateService;
    @Mock private CustomerRateStructureService rateStructure;
    @Mock private CompanySettingsService companySettings;
    @InjectMocks private AllRateCalculatorService service;

    private ServiceType cargo;
    private ServiceType air;
    private ServiceType premium;
    private PackageType parcel;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        CompanyContext.setCompanyId(COMPANY);
        cargo = service("CARGO", "Cargo", 1);
        air = service("AIR", "By Air", 2);
        premium = service("PREMIUM", "Premium", 3);
        when(serviceTypes.findAll(any(Specification.class))).thenReturn(List.of(premium, air, cargo));
        parcel = new PackageType();
        parcel.setId(UUID.randomUUID());
        parcel.setName("Non-document");
        when(packageTypes.findAll(any(Specification.class))).thenReturn(List.of(parcel));
        when(coverageLookup.findByPincode("560052")).thenReturn(Optional.of(new PincodeCoverageLookupPort.CoverageRef(
                UUID.randomUUID(), "560052", true, false, UUID.randomUUID(), "BLR", "Bangalore", true, "Bangalore", UUID.randomUUID())));
        CompanySettings settings = new CompanySettings();
        settings.setGstPercentage(new BigDecimal("18"));
        when(companySettings.get()).thenReturn(settings);
        when(networkCoverage.coverage(eq("560052"), any(), any())).thenReturn(Optional.empty());
        when(zoneService.resolve(any(), any(), any())).thenReturn(Optional.empty());
        when(zoneTariff.price(any(), any(), any(), any(), any(), anyInt(), any(), any(), any())).thenReturn(Optional.empty());
        when(rateStructure.quote(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        CompanyContext.clear();
    }

    @Test
    @DisplayName("every service gets a row, in display order: the structure first, Zone Tariff next, else why not")
    void everyServicePriced() {
        when(rateStructure.quote(eq(CUSTOMER), eq(cargo.getId()), eq("411001"), eq("560052"), any())).thenReturn(Optional.of(
                new CustomerRateStructureService.Quote(new BigDecimal("25"), new BigDecimal("20"), new BigDecimal("500"),
                        "Rate structure: West → Rest of India")));
        when(zoneTariff.price(eq("411001"), eq("560052"), eq(air.getId()), any(), any(), anyInt(), any(), any(), any()))
                .thenReturn(Optional.of(new ZoneTariffPort.TariffPrice("Zone tariff PUNE_CASH: PUNE → REST_OF_INDIA",
                        "200 up to 1 kg", new BigDecimal("200"), new BigDecimal("20"))));
        when(networkCoverage.coverage("560052", premium.getId(), parcel.getId())).thenReturn(Optional.of(
                new NetworkCoveragePort.NetworkCoverageRef("Air", false, null, null)));

        var r = service.calculate(new AllRateCalculatorService.Input("411001", "560052", new BigDecimal("12"), null, CUSTOMER, null));

        assertThat(r.rows()).extracting(AllRateCalculatorService.Row::serviceTypeCode).containsExactly("CARGO", "AIR", "PREMIUM");
        var c = r.rows().get(0);
        assertThat(c.source()).isEqualTo("Customer rate structure");
        assertThat(c.freight()).isEqualByComparingTo("500");
        assertThat(c.gst()).isEqualByComparingTo("90");
        assertThat(c.total()).isEqualByComparingTo("590");
        var a = r.rows().get(1);
        assertThat(a.source()).isEqualTo("Zone Tariff");
        assertThat(a.total()).isEqualByComparingTo("259.60");
        assertThat(r.rows().get(2).error()).contains("Air service does not deliver to pincode 560052");
    }

    @Test
    @DisplayName("a refusal on one service is that row's error; the others still price")
    void refusalStaysOnItsRow() {
        when(rateStructure.quote(eq(CUSTOMER), eq(cargo.getId()), any(), any(), any()))
                .thenThrow(new BusinessRuleException("This customer's cargo rates have no row for shipments starting in CENTRAL."));
        var r = service.calculate(new AllRateCalculatorService.Input("482001", "560052", BigDecimal.ONE, null, CUSTOMER, null));
        assertThat(r.rows().get(0).error()).contains("CENTRAL");
        assertThat(r.rows().get(1).error()).isEqualTo("No rate for this route on this service.");
    }

    @Test
    @DisplayName("bad input is refused up front")
    void badInput() {
        assertThatThrownBy(() -> service.calculate(new AllRateCalculatorService.Input("41100", "560052", BigDecimal.ONE, null, null, null)))
                .hasMessageContaining("6-digit from pincode");
        assertThatThrownBy(() -> service.calculate(new AllRateCalculatorService.Input("411001", "560052", BigDecimal.ZERO, null, null, null)))
                .hasMessageContaining("weight");
    }

    private static ServiceType service(String code, String name, int order) {
        ServiceType s = new ServiceType();
        s.setId(UUID.randomUUID());
        s.setCode(code);
        s.setName(name);
        s.setDisplayOrder(order);
        return s;
    }
}
