package com.courier.modules.districtfreight.application;

import com.courier.modules.customerpricing.application.PricingZoneService;
import com.courier.modules.customerpricing.application.PricingZoneView;
import com.courier.modules.customerpricing.application.ZoneRateService;
import com.courier.modules.districtfreight.domain.BranchLookupPort;
import com.courier.modules.districtfreight.domain.DistrictFreightStatus;
import com.courier.modules.districtfreight.domain.DistrictLevelFreight;
import com.courier.modules.districtfreight.domain.DistrictLevelFreightRepository;
import com.courier.modules.districtfreight.domain.PincodeCoverageLookupPort;
import com.courier.modules.districtfreight.domain.ZoneTariffPort;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.security.AuthenticatedUser;
import com.courier.shared.security.Roles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Mirrors {@code DistrictLevelFreightServiceImplTest}'s own fixture shape — same
 *  ICHALKARANJI -&gt; PUNE lane the brief's own worked examples use. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FreightCalculationServiceImplTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID CALLER = UUID.randomUUID();
    private static final UUID BRANCH = UUID.randomUUID();
    private static final UUID DISTRICT = UUID.randomUUID();
    private static final UUID PINCODE = UUID.randomUUID();
    private static final String DESTINATION_PINCODE = "411001";

    @Mock private DistrictLevelFreightRepository repository;
    @Mock private BranchLookupPort branchLookup;
    @Mock private PincodeCoverageLookupPort coverageLookup;
    @Mock private PricingZoneService zoneService;
    @Mock private ZoneRateService zoneRateService;
    @Mock private ZoneTariffPort zoneTariff;

    private FreightCalculationServiceImpl service;

    private static final BranchLookupPort.BranchRef ACTIVE_BRANCH =
            new BranchLookupPort.BranchRef(BRANCH, "ICHALKARANJI", "Ichalkaranji", true);
    private static final PincodeCoverageLookupPort.CoverageRef SERVICEABLE_NON_ODA =
            new PincodeCoverageLookupPort.CoverageRef(PINCODE, DESTINATION_PINCODE, true, false,
                    DISTRICT, "PUNE", "Pune", true, "Pune", null);
    private static final PincodeCoverageLookupPort.CoverageRef SERVICEABLE_ODA =
            new PincodeCoverageLookupPort.CoverageRef(PINCODE, DESTINATION_PINCODE, true, true,
                    DISTRICT, "PUNE", "Pune", true, "Pune", null);

    @BeforeEach
    void setUp() {
        service = new FreightCalculationServiceImpl(repository, branchLookup, coverageLookup,
                zoneService, zoneRateService, zoneTariff);
        CompanyContext.setCompanyId(COMPANY);
        AuthenticatedUser principal = new AuthenticatedUser(
                CALLER, COMPANY, "ops@test.com", Set.of(Roles.COMPANY_ADMIN), "jti");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.authorities()));

        when(branchLookup.findBranch(eq(BRANCH), eq(COMPANY))).thenReturn(Optional.of(ACTIVE_BRANCH));
    }

    @AfterEach
    void tearDown() {
        CompanyContext.clear();
        SecurityContextHolder.clearContext();
    }

    private DistrictLevelFreight row() {
        DistrictLevelFreight f = new DistrictLevelFreight();
        f.setId(UUID.randomUUID());
        f.setBranchId(BRANCH);
        f.setDistrictId(DISTRICT);
        f.setRate1To15(new BigDecimal("10.00"));
        f.setRate16To50(new BigDecimal("8.50"));
        f.setRate51To100(new BigDecimal("8.00"));
        f.setRate101To1000(new BigDecimal("7.50"));
        f.setRate1001To1500(new BigDecimal("6.00"));
        f.setRate1501To2000(new BigDecimal("6.00"));
        f.setOdaApplicable(true);
        f.setOdaCharge(new BigDecimal("250.00"));
        f.setStatus(DistrictFreightStatus.ACTIVE);
        return f;
    }

    @Test
    @DisplayName("ICHALKARANJI -> PUNE, 20 KG -> the 16-50 KG slab's rate, base freight 170.00 before ODA")
    void workedExample20Kg() {
        when(coverageLookup.findByPincode(DESTINATION_PINCODE)).thenReturn(Optional.of(SERVICEABLE_NON_ODA));
        when(repository.findByCompanyIdAndBranchIdAndDistrictId(COMPANY, BRANCH, DISTRICT))
                .thenReturn(Optional.of(row()));

        FreightCalculationResult result = service.calculate(BRANCH, DESTINATION_PINCODE, null, new BigDecimal("20"));

        assertThat(result.weightSlabLabel()).isEqualTo("16-50 KG");
        assertThat(result.ratePerKg()).isEqualByComparingTo("8.50");
        assertThat(result.baseFreight()).isEqualByComparingTo("170.00");
        assertThat(result.odaApplicable()).isFalse();
        assertThat(result.odaCharge()).isEqualByComparingTo("0.00");
        assertThat(result.totalFreight()).isEqualByComparingTo("170.00");
    }

    @Test
    @DisplayName("a chosen destination Area routes through findByPincodeAndArea instead of "
            + "the pincode's legacy single area — resolves District/ODA off that exact link")
    void destinationAreaRoutesThroughAreaSpecificCoverageLookup() {
        UUID areaId = UUID.randomUUID();
        when(coverageLookup.findByPincodeAndArea(DESTINATION_PINCODE, areaId))
                .thenReturn(Optional.of(SERVICEABLE_ODA));
        when(repository.findByCompanyIdAndBranchIdAndDistrictId(COMPANY, BRANCH, DISTRICT))
                .thenReturn(Optional.of(row()));

        FreightCalculationResult result = service.calculate(BRANCH, DESTINATION_PINCODE, areaId, new BigDecimal("20"));

        assertThat(result.baseFreight()).isEqualByComparingTo("170.00");
        assertThat(result.odaApplicable()).isTrue();
        verify(coverageLookup, never()).findByPincode(any());
    }

    @Test
    @DisplayName("ICHALKARANJI -> PUNE, 60 KG -> the 51-100 KG slab's rate, base freight 480.00")
    void workedExample60Kg() {
        when(coverageLookup.findByPincode(DESTINATION_PINCODE)).thenReturn(Optional.of(SERVICEABLE_NON_ODA));
        when(repository.findByCompanyIdAndBranchIdAndDistrictId(COMPANY, BRANCH, DISTRICT))
                .thenReturn(Optional.of(row()));

        FreightCalculationResult result = service.calculate(BRANCH, DESTINATION_PINCODE, null, new BigDecimal("60"));

        assertThat(result.weightSlabLabel()).isEqualTo("51-100 KG");
        assertThat(result.baseFreight()).isEqualByComparingTo("480.00");
    }

    @ParameterizedTest(name = "{0} KG matches slab {1} at rate {2}")
    @DisplayName("every slab boundary from 1 to 2000 KG matches its own slab, never a progressive split")
    @CsvSource({
            "1, 1-15 KG, 10.00",
            "15, 1-15 KG, 10.00",
            "16, 16-50 KG, 8.50",
            "50, 16-50 KG, 8.50",
            "51, 51-100 KG, 8.00",
            "100, 51-100 KG, 8.00",
            "101, 101-1000 KG, 7.50",
            "1000, 101-1000 KG, 7.50",
            "1001, 1001-1500 KG, 6.00",
            "2000, 1501-2000 KG, 6.00"
    })
    void everyBoundaryWeightMatchesOneSlab(String weight, String slabLabel, String ratePerKg) {
        when(coverageLookup.findByPincode(DESTINATION_PINCODE)).thenReturn(Optional.of(SERVICEABLE_NON_ODA));
        when(repository.findByCompanyIdAndBranchIdAndDistrictId(COMPANY, BRANCH, DISTRICT))
                .thenReturn(Optional.of(row()));

        FreightCalculationResult result = service.calculate(BRANCH, DESTINATION_PINCODE, null, new BigDecimal(weight));

        assertThat(result.weightSlabLabel()).isEqualTo(slabLabel);
        assertThat(result.ratePerKg()).isEqualByComparingTo(ratePerKg);
        assertThat(result.baseFreight())
                .isEqualByComparingTo(new BigDecimal(weight).multiply(new BigDecimal(ratePerKg)));
    }

    @Test
    @DisplayName("2001 KG (past the configured maximum) is rejected, not floored to the highest slab")
    void aboveMaximumWeightRejected() {
        when(coverageLookup.findByPincode(DESTINATION_PINCODE)).thenReturn(Optional.of(SERVICEABLE_NON_ODA));
        when(repository.findByCompanyIdAndBranchIdAndDistrictId(COMPANY, BRANCH, DISTRICT))
                .thenReturn(Optional.of(row()));

        assertThatThrownBy(() -> service.calculate(BRANCH, DESTINATION_PINCODE, null, new BigDecimal("2001")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("outside the configured");
    }

    @Test
    @DisplayName("zero weight is rejected before any lookup runs")
    void zeroWeightRejected() {
        assertThatThrownBy(() -> service.calculate(BRANCH, DESTINATION_PINCODE, null, BigDecimal.ZERO))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("greater than zero");
    }

    @Test
    @DisplayName("negative weight is rejected before any lookup runs")
    void negativeWeightRejected() {
        assertThatThrownBy(() -> service.calculate(BRANCH, DESTINATION_PINCODE, null, new BigDecimal("-5")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("greater than zero");
    }

    @Test
    @DisplayName("an ODA destination adds the row's own configured ODA charge, never a hardcoded 250")
    void odaDestinationAddsConfiguredCharge() {
        when(coverageLookup.findByPincode(DESTINATION_PINCODE)).thenReturn(Optional.of(SERVICEABLE_ODA));
        DistrictLevelFreight configured = row();
        configured.setOdaCharge(new BigDecimal("375.50"));
        when(repository.findByCompanyIdAndBranchIdAndDistrictId(COMPANY, BRANCH, DISTRICT))
                .thenReturn(Optional.of(configured));

        FreightCalculationResult result = service.calculate(BRANCH, DESTINATION_PINCODE, null, new BigDecimal("20"));

        assertThat(result.odaApplicable()).isTrue();
        assertThat(result.odaCharge()).isEqualByComparingTo("375.50");
        assertThat(result.totalFreight()).isEqualByComparingTo(result.baseFreight().add(new BigDecimal("375.50")));
    }

    @Test
    @DisplayName("a non-ODA destination adds no ODA charge even though the row allows ODA")
    void nonOdaDestinationAddsNoCharge() {
        when(coverageLookup.findByPincode(DESTINATION_PINCODE)).thenReturn(Optional.of(SERVICEABLE_NON_ODA));
        when(repository.findByCompanyIdAndBranchIdAndDistrictId(COMPANY, BRANCH, DISTRICT))
                .thenReturn(Optional.of(row()));

        FreightCalculationResult result = service.calculate(BRANCH, DESTINATION_PINCODE, null, new BigDecimal("20"));

        assertThat(result.odaApplicable()).isFalse();
        assertThat(result.odaCharge()).isEqualByComparingTo("0.00");
        assertThat(result.totalFreight()).isEqualByComparingTo(result.baseFreight());
    }

    @Test
    @DisplayName("the row's own odaApplicable=false wins even when the destination itself is ODA")
    void rowOdaDisabledWinsOverDestinationOda() {
        when(coverageLookup.findByPincode(DESTINATION_PINCODE)).thenReturn(Optional.of(SERVICEABLE_ODA));
        DistrictLevelFreight configured = row();
        configured.setOdaApplicable(false);
        when(repository.findByCompanyIdAndBranchIdAndDistrictId(COMPANY, BRANCH, DISTRICT))
                .thenReturn(Optional.of(configured));

        FreightCalculationResult result = service.calculate(BRANCH, DESTINATION_PINCODE, null, new BigDecimal("20"));

        assertThat(result.odaApplicable()).isFalse();
        assertThat(result.odaCharge()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("no District Level Freight configuration for this From Station + District is a clear, blocking error")
    void missingConfigurationRejected() {
        when(coverageLookup.findByPincode(DESTINATION_PINCODE)).thenReturn(Optional.of(SERVICEABLE_NON_ODA));
        when(repository.findByCompanyIdAndBranchIdAndDistrictId(COMPANY, BRANCH, DISTRICT))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.calculate(BRANCH, DESTINATION_PINCODE, null, new BigDecimal("20")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("No District Level Freight configuration exists");
    }

    @Test
    @DisplayName("an INACTIVE configuration row is treated the same as no configuration at all")
    void inactiveConfigurationRejected() {
        when(coverageLookup.findByPincode(DESTINATION_PINCODE)).thenReturn(Optional.of(SERVICEABLE_NON_ODA));
        DistrictLevelFreight inactive = row();
        inactive.setStatus(DistrictFreightStatus.INACTIVE);
        when(repository.findByCompanyIdAndBranchIdAndDistrictId(COMPANY, BRANCH, DISTRICT))
                .thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> service.calculate(BRANCH, DESTINATION_PINCODE, null, new BigDecimal("20")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("No District Level Freight configuration exists");
    }

    @Test
    @DisplayName("a pincode not on file / with no resolvable district is a clear, blocking error")
    void unresolvableDestinationRejected() {
        when(coverageLookup.findByPincode(DESTINATION_PINCODE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.calculate(BRANCH, DESTINATION_PINCODE, null, new BigDecimal("20")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("not on file");
    }

    @Test
    @DisplayName("a not-serviceable pincode is rejected even though it resolves to a district")
    void notServiceableDestinationRejected() {
        PincodeCoverageLookupPort.CoverageRef notServiceable =
                new PincodeCoverageLookupPort.CoverageRef(PINCODE, DESTINATION_PINCODE, false, false,
                        DISTRICT, "PUNE", "Pune", true, "Pune", null);
        when(coverageLookup.findByPincode(DESTINATION_PINCODE)).thenReturn(Optional.of(notServiceable));

        assertThatThrownBy(() -> service.calculate(BRANCH, DESTINATION_PINCODE, null, new BigDecimal("20")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("not serviceable");
    }

    @Test
    @DisplayName("the pincode's own coverage record picks a single correct district even when other "
            + "pincodes/areas exist in the same city, since the lookup is keyed by the exact pincode")
    void correctDistrictSelectedFromCoverage() {
        UUID otherDistrict = UUID.randomUUID();
        PincodeCoverageLookupPort.CoverageRef thisPincode =
                new PincodeCoverageLookupPort.CoverageRef(PINCODE, DESTINATION_PINCODE, true, false,
                        DISTRICT, "PUNE", "Pune", true, "Pune", null);
        // A different pincode elsewhere resolving to a different district must never be consulted —
        // findByPincode is only ever stubbed for DESTINATION_PINCODE, so any lookup by another code
        // would return an unstubbed (empty) result and fail this test with a different exception.
        when(coverageLookup.findByPincode(DESTINATION_PINCODE)).thenReturn(Optional.of(thisPincode));
        when(repository.findByCompanyIdAndBranchIdAndDistrictId(COMPANY, BRANCH, DISTRICT))
                .thenReturn(Optional.of(row()));
        when(repository.findByCompanyIdAndBranchIdAndDistrictId(COMPANY, BRANCH, otherDistrict))
                .thenReturn(Optional.empty());

        FreightCalculationResult result = service.calculate(BRANCH, DESTINATION_PINCODE, null, new BigDecimal("20"));

        assertThat(result.districtId()).isEqualTo(DISTRICT);
        assertThat(result.districtCode()).isEqualTo("PUNE");
    }

    @Test
    @DisplayName("an inactive booking branch is refused as a From Station")
    void inactiveBranchRejected() {
        when(branchLookup.findBranch(eq(BRANCH), eq(COMPANY)))
                .thenReturn(Optional.of(new BranchLookupPort.BranchRef(BRANCH, "ICHALKARANJI", "Ichalkaranji", false)));

        assertThatThrownBy(() -> service.calculate(BRANCH, DESTINATION_PINCODE, null, new BigDecimal("20")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("inactive");
    }

    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID CITY = UUID.randomUUID();

    private PincodeCoverageLookupPort.CoverageRef withCity() {
        return new PincodeCoverageLookupPort.CoverageRef(PINCODE, DESTINATION_PINCODE, true, true,
                DISTRICT, "PUNE", "Pune", true, "Pune", CITY);
    }

    @Test
    @DisplayName("a business customer whose zone covers the destination is priced from the zone rate card, not District Level Freight")
    void customerZonePricesFreight() {
        when(coverageLookup.findByPincode(DESTINATION_PINCODE)).thenReturn(Optional.of(withCity()));
        when(zoneService.resolve(CUSTOMER, DISTRICT, CITY)).thenReturn(Optional.of(
                new PricingZoneView(null, "Acme", java.util.List.of(), true)));
        when(zoneRateService.calculate(any())).thenReturn(new ZoneRateService.Quote(UUID.randomUUID(), "Zone A",
                new BigDecimal("20"), new BigDecimal("20"), new BigDecimal("6.00"), new BigDecimal("120.00"),
                new BigDecimal("50.00"), new BigDecimal("30.00"), new BigDecimal("10.00"), new BigDecimal("80.00"),
                new BigDecimal("12.00"), new BigDecimal("5.00"), new BigDecimal("307.00")));

        FreightCalculationResult r = service.calculate(BRANCH, DESTINATION_PINCODE, null, new BigDecimal("20"),
                CUSTOMER, new BigDecimal("1000"));

        assertThat(r.customerZoneName()).isEqualTo("Zone A");
        assertThat(r.ratePerKg()).isEqualByComparingTo("6.00");
        assertThat(r.baseFreight()).isEqualByComparingTo("120.00");
        assertThat(r.odaCharge()).isEqualByComparingTo("80.00");
        assertThat(r.customerExtraCharges()).isEqualByComparingTo("107.00");
        verify(repository, never()).findByCompanyIdAndBranchIdAndDistrictId(any(), any(), any());
    }

    @Test
    @DisplayName("a business customer with no zone for the destination falls back to District Level Freight")
    void customerWithoutZoneFallsBack() {
        when(coverageLookup.findByPincode(DESTINATION_PINCODE)).thenReturn(Optional.of(withCity()));
        when(zoneService.resolve(CUSTOMER, DISTRICT, CITY)).thenReturn(Optional.empty());
        when(repository.findByCompanyIdAndBranchIdAndDistrictId(COMPANY, BRANCH, DISTRICT))
                .thenReturn(Optional.of(row()));

        FreightCalculationResult r = service.calculate(BRANCH, DESTINATION_PINCODE, null, new BigDecimal("20"),
                CUSTOMER, null);

        assertThat(r.customerZoneName()).isNull();
        assertThat(r.baseFreight()).isEqualByComparingTo("170.00");
        assertThat(r.customerExtraCharges()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("an ordinary booking never consults customer zones")
    void ordinaryBookingSkipsZones() {
        when(coverageLookup.findByPincode(DESTINATION_PINCODE)).thenReturn(Optional.of(withCity()));
        when(repository.findByCompanyIdAndBranchIdAndDistrictId(COMPANY, BRANCH, DISTRICT))
                .thenReturn(Optional.of(row()));

        service.calculate(BRANCH, DESTINATION_PINCODE, null, new BigDecimal("20"));

        org.mockito.Mockito.verifyNoInteractions(zoneService, zoneRateService);
    }

    // ------------------------------------------------------------- zone tariff

    private static final UUID SERVICE = UUID.randomUUID();
    private static final UUID PACKAGE = UUID.randomUUID();
    private static final FreightCalculationService.TariffInput TARIFF_INPUT =
            new FreightCalculationService.TariffInput(SERVICE, PACKAGE, 2, java.time.LocalDate.of(2025, 6, 1));

    @Test
    @DisplayName("a zone tariff covering the branch's pincode prices the freight; its charges become required Other Charges")
    void zoneTariffPrices() {
        when(coverageLookup.findByPincode(DESTINATION_PINCODE)).thenReturn(Optional.of(SERVICEABLE_NON_ODA));
        when(branchLookup.findPostalCode(BRANCH, COMPANY)).thenReturn(Optional.of("416115"));
        when(zoneTariff.price("416115", DESTINATION_PINCODE, SERVICE, PACKAGE, new BigDecimal("2.3"), 2, null,
                java.time.LocalDate.of(2025, 6, 1)))
                .thenReturn(Optional.of(new ZoneTariffPort.TariffPrice("Zone tariff PUNE_CASH: PUNE → CENTRAL",
                        "200 up to 1 kg + 2 × 200 per extra kg", new BigDecimal("600"), new BigDecimal("80"))));

        FreightCalculationResult result = service.calculate(BRANCH, DESTINATION_PINCODE, null, new BigDecimal("2.3"),
                null, null, TARIFF_INPUT);

        assertThat(result.baseFreight()).isEqualByComparingTo("600.00");
        assertThat(result.totalFreight()).isEqualByComparingTo("600.00");
        assertThat(result.customerExtraCharges()).isEqualByComparingTo("80.00");
        assertThat(result.customerZoneName()).isEqualTo("Zone tariff PUNE_CASH: PUNE → CENTRAL");
        assertThat(result.weightSlabLabel()).isEqualTo("200 up to 1 kg + 2 × 200 per extra kg");
        assertThat(result.ratePerKg()).isEqualByComparingTo("260.87");
        assertThat(result.matchedFreightId()).isNull();
    }

    @Test
    @DisplayName("no zone tariff for the branch (or no service/package type given): District Level Freight as before")
    void zoneTariffNotApplicable() {
        when(coverageLookup.findByPincode(DESTINATION_PINCODE)).thenReturn(Optional.of(SERVICEABLE_NON_ODA));
        when(repository.findByCompanyIdAndBranchIdAndDistrictId(COMPANY, BRANCH, DISTRICT)).thenReturn(Optional.of(row()));
        when(branchLookup.findPostalCode(BRANCH, COMPANY)).thenReturn(Optional.of("416115"));
        when(zoneTariff.price(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        assertThat(service.calculate(BRANCH, DESTINATION_PINCODE, null, new BigDecimal("20"), null, null, TARIFF_INPUT)
                .baseFreight()).isEqualByComparingTo("170.00");
        assertThat(service.calculate(BRANCH, DESTINATION_PINCODE, null, new BigDecimal("20"))
                .baseFreight()).isEqualByComparingTo("170.00");
        org.mockito.Mockito.verify(zoneTariff, org.mockito.Mockito.times(1))
                .price(any(), any(), any(), any(), any(), any(), any(), any());
    }
}
