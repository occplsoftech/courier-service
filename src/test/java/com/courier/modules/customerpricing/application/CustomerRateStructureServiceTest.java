package com.courier.modules.customerpricing.application;

import com.courier.modules.customer.application.CustomerPortalLookup;
import com.courier.modules.customerpricing.application.CustomerRateStructureService.Parsed;
import com.courier.modules.customerpricing.domain.DestinationGroup;
import com.courier.modules.customerpricing.domain.OriginRegion;
import com.courier.modules.customerpricing.domain.StructureRate;
import com.courier.modules.customerpricing.domain.StructureRateRepository;
import com.courier.modules.master.application.DistrictService;
import com.courier.modules.master.application.PincodeLocation;
import com.courier.modules.master.application.PincodeService;
import com.courier.modules.master.application.RegionalZoneService;
import com.courier.modules.master.application.StateService;
import com.courier.modules.master.domain.District;
import com.courier.modules.master.domain.RegionalZone;
import com.courier.modules.master.domain.ServiceTypeRepository;
import com.courier.modules.master.domain.State;
import com.courier.shared.company.CompanyContext;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CustomerRateStructureServiceTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID CARGO_SURFACE = UUID.randomUUID();
    private static final UUID AIR_COURIER = UUID.randomUUID();
    private static final UUID MAHARASHTRA = UUID.randomUUID();
    private static final UUID KARNATAKA = UUID.randomUUID();
    private static final UUID ASSAM = UUID.randomUUID();
    private static final UUID DELHI = UUID.randomUUID();

    @Mock private StructureRateRepository rates;
    @Mock private ServiceTypeRepository serviceTypes;
    @Mock private CustomerPortalLookup customerLookup;
    @Mock private CustomerRateSourceService rateSource;
    @Mock private PincodeService pincodes;
    @Mock private DistrictService districts;
    @Mock private StateService states;
    @Mock private RegionalZoneService regionalZones;
    @InjectMocks private CustomerRateStructureService service;

    @BeforeEach
    void setUp() {
        CompanyContext.setCompanyId(COMPANY);
        when(customerLookup.usesBusinessRates(COMPANY, CUSTOMER)).thenReturn(true);
        state(MAHARASHTRA, "Maharashtra");
        state(KARNATAKA, "Karnataka");
        state(ASSAM, "Assam");
        state(DELHI, "Delhi");
        pin("411001", "Pune", MAHARASHTRA, "WEST", "PUNE");
        pin("440009", "Nagpur", MAHARASHTRA, "WEST", "VIDHARBHA");
        pin("560052", "Bangalore", KARNATAKA, "SOUTH", "REST OF INDIA");
        pin("577001", "Davangere", KARNATAKA, "SOUTH", "REST OF INDIA");
        pin("781001", "Kamrup", ASSAM, "EAST", "NORTH EAST");
        pin("110001", "New Delhi", DELHI, "NORTH", "REST OF INDIA");
        pin("482001", "Jabalpur", UUID.randomUUID(), "CENTRAL", "REST OF INDIA");
    }

    @AfterEach
    void tearDown() {
        CompanyContext.clear();
    }

    // ------------------------------------------------------------------ parsing

    @Test
    @DisplayName("the template reads back as the printed rate card: 4 air courier rates, the cargo grid, 20 kg minimum")
    void templateRoundTrip() {
        Parsed p = CustomerRateStructureService.parse(new ByteArrayInputStream(service.template()));

        assertThat(p.errors()).isEmpty();
        assertThat(p.airCourier()).extracting(a -> a.group() + "=" + a.ratePerKg().intValue())
                .containsExactly("WEST=65", "METROS=95", "REST_OF_INDIA=100", "EAST_NORTH_EAST=130");
        assertThat(p.cargoMinChargeableKg()).isEqualByComparingTo("20");
        assertThat(cell(p, OriginRegion.WEST, DestinationGroup.WITHIN_STATE)).isEqualTo("20/-");
        assertThat(cell(p, OriginRegion.WEST, DestinationGroup.REST_OF_INDIA)).isEqualTo("25/100");
        assertThat(cell(p, OriginRegion.WEST, DestinationGroup.NORTH_EAST)).isEqualTo("35/125");
        assertThat(cell(p, OriginRegion.NORTH_EAST, DestinationGroup.REST_OF_INDIA)).isEqualTo("30/125");
        assertThat(cell(p, OriginRegion.NORTH_EAST, DestinationGroup.NORTH_EAST)).isNull();
        assertThat(p.cargo()).hasSize(14);
    }

    @Test
    @DisplayName("a sheet typed like the photo, with 25/100 cells and no By Surface / By Air row, reads the same")
    void photoLayout() throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet();
            row(s, 0, "DESTINATION", "Within State By Surface", "Rest of India By Surface/Air", "North East By Surface/Air");
            row(s, 1, "North", "20", "25/100", "30/110");
            row(s, 2, "North East", "20", "30/125", "-");
            row(s, 4, "* Minimum Chargeable weight 20kgs");
            wb.write(out);
            Parsed p = CustomerRateStructureService.parse(new ByteArrayInputStream(out.toByteArray()));

            assertThat(p.errors()).isEmpty();
            assertThat(cell(p, OriginRegion.NORTH, DestinationGroup.WITHIN_STATE)).isEqualTo("20/-");
            assertThat(cell(p, OriginRegion.NORTH, DestinationGroup.NORTH_EAST)).isEqualTo("30/110");
            assertThat(cell(p, OriginRegion.NORTH_EAST, DestinationGroup.NORTH_EAST)).isNull();
            assertThat(p.cargoMinChargeableKg()).isEqualByComparingTo("20");
        }
    }

    @Test
    @DisplayName("a rate that is not a number is reported with its row")
    void badRate() throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet();
            row(s, 0, "AIR COURIER MODE", "", "", "", "PER KG");
            row(s, 1, "WEST", "", "", "", "sixty");
            wb.write(out);
            Parsed p = CustomerRateStructureService.parse(new ByteArrayInputStream(out.toByteArray()));
            assertThat(p.errors()).singleElement().satisfies(i -> {
                assertThat(i.row()).isEqualTo(2);
                assertThat(i.message()).contains("not a number");
            });
        }
    }

    // ------------------------------------------------------------------ pricing

    @Test
    @DisplayName("cargo: the row by where it starts, the column by where it goes, at least 20 kg")
    void cargoPricing() {
        structure(CARGO_SURFACE,
                rate(OriginRegion.WEST, DestinationGroup.WITHIN_STATE, "20"),
                rate(OriginRegion.WEST, DestinationGroup.REST_OF_INDIA, "25"),
                rate(OriginRegion.WEST, DestinationGroup.NORTH_EAST, "35"),
                rate(OriginRegion.SOUTH, DestinationGroup.WITHIN_STATE, "20"));

        var roi = service.quote(CUSTOMER, CARGO_SURFACE, "411001", "560052", new BigDecimal("12")).orElseThrow();
        assertThat(roi.chargeableWeight()).isEqualByComparingTo("20");
        assertThat(roi.freight()).isEqualByComparingTo("500");
        assertThat(roi.label()).isEqualTo("Rate structure: West → Rest of India");

        assertThat(service.quote(CUSTOMER, CARGO_SURFACE, "411001", "440009", new BigDecimal("30")).orElseThrow().freight())
                .isEqualByComparingTo("600");
        assertThat(service.quote(CUSTOMER, CARGO_SURFACE, "411001", "781001", new BigDecimal("20")).orElseThrow().freight())
                .isEqualByComparingTo("700");
        assertThat(service.quote(CUSTOMER, CARGO_SURFACE, "560052", "577001", new BigDecimal("25")).orElseThrow().freight())
                .isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("cargo refuses a lane the structure has no rate for, and an origin region with no row")
    void cargoRefusals() {
        structure(CARGO_SURFACE, rate(OriginRegion.WEST, DestinationGroup.WITHIN_STATE, "20"));

        assertThatThrownBy(() -> service.quote(CUSTOMER, CARGO_SURFACE, "411001", "560052", BigDecimal.ONE))
                .hasMessageContaining("no").hasMessageContaining("West to Rest of India");
        assertThatThrownBy(() -> service.quote(CUSTOMER, CARGO_SURFACE, "482001", "411001", BigDecimal.ONE))
                .hasMessageContaining("CENTRAL");
    }

    @Test
    @DisplayName("air courier: West and East-North East by region, then metros, else Rest of India; any origin")
    void airCourierPricing() {
        structure(AIR_COURIER,
                rate(null, DestinationGroup.WEST, "65"), rate(null, DestinationGroup.METROS, "95"),
                rate(null, DestinationGroup.REST_OF_INDIA, "100"), rate(null, DestinationGroup.EAST_NORTH_EAST, "130"));

        assertThat(price(AIR_COURIER, "440009")).isEqualByComparingTo("65");
        assertThat(price(AIR_COURIER, "781001")).isEqualByComparingTo("130");
        assertThat(price(AIR_COURIER, "560052")).isEqualByComparingTo("95");
        assertThat(price(AIR_COURIER, "110001")).isEqualByComparingTo("95");
        assertThat(price(AIR_COURIER, "577001")).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("no structure for the service, or a customer on company rates: nothing, so other pricing applies")
    void notApplicable() {
        when(rates.findByCustomerAndService(COMPANY, CUSTOMER, AIR_COURIER)).thenReturn(List.of());
        assertThat(service.quote(CUSTOMER, AIR_COURIER, "411001", "560052", BigDecimal.ONE)).isEmpty();

        structure(AIR_COURIER, rate(null, DestinationGroup.METROS, "95"));
        when(customerLookup.usesBusinessRates(COMPANY, CUSTOMER)).thenReturn(false);
        assertThat(service.quote(CUSTOMER, AIR_COURIER, "411001", "560052", BigDecimal.ONE)).isEmpty();
    }

    // ------------------------------------------------------------------ fixtures

    private BigDecimal price(UUID service, String to) {
        return this.service.quote(CUSTOMER, service, "411001", to, BigDecimal.ONE).orElseThrow().freight();
    }

    private void structure(UUID serviceTypeId, StructureRate... rows) {
        for (StructureRate r : rows) {
            r.setServiceTypeId(serviceTypeId);
            if (r.getOriginRegion() != null) r.setMinChargeableKg(new BigDecimal("20"));
        }
        when(rates.findByCustomerAndService(COMPANY, CUSTOMER, serviceTypeId)).thenReturn(List.of(rows));
    }

    private static StructureRate rate(OriginRegion origin, DestinationGroup group, String perKg) {
        return StructureRate.builder().customerId(CUSTOMER).originRegion(origin).destinationGroup(group)
                .ratePerKg(new BigDecimal(perKg)).minChargeableKg(BigDecimal.ZERO).build();
    }

    private void state(UUID id, String name) {
        State s = new State();
        s.setId(id);
        s.setName(name);
        when(states.getById(id)).thenReturn(s);
    }

    private void pin(String code, String districtName, UUID stateId, String regional, String zone) {
        UUID districtId = UUID.randomUUID();
        UUID rzId = UUID.randomUUID();
        District d = new District();
        d.setId(districtId);
        d.setName(districtName);
        d.setStateId(stateId);
        when(districts.getById(districtId)).thenReturn(d);
        RegionalZone rz = new RegionalZone();
        rz.setId(rzId);
        rz.setCode(regional);
        when(regionalZones.getById(rzId)).thenReturn(rz);
        when(pincodes.location(eq(code))).thenReturn(Optional.of(new PincodeLocation(UUID.randomUUID(), null, null, districtId,
                true, true, false, rzId, null, null, null, zone)));
    }

    private static String cell(Parsed p, OriginRegion origin, DestinationGroup group) {
        return p.cargo().stream().filter(c -> c.origin() == origin && c.group() == group).findFirst()
                .map(c -> fmt(c.surface()) + "/" + fmt(c.air())).orElse(null);
    }

    private static String fmt(BigDecimal v) {
        return v == null ? "-" : String.valueOf(v.intValue());
    }

    private static void row(Sheet s, int r, String... values) {
        Row row = s.createRow(r);
        for (int c = 0; c < values.length; c++) row.createCell(c).setCellValue(values[c]);
    }
}
