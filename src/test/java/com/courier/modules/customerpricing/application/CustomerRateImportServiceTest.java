package com.courier.modules.customerpricing.application;

import com.courier.modules.customerpricing.application.CustomerRateImportService.ImportPreview;
import com.courier.modules.customerpricing.application.PricingZoneService.LocationInput;
import com.courier.modules.districtfreight.domain.DistrictLookupPort;
import com.courier.modules.districtfreight.domain.DistrictLookupPort.DistrictRef;
import com.courier.modules.master.domain.ServiceType;
import com.courier.modules.master.domain.ServiceTypeRepository;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CustomerRateImportServiceTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID PUNE = UUID.randomUUID();
    private static final UUID MUMBAI = UUID.randomUUID();
    private static final UUID AIR = UUID.randomUUID();
    private static final UUID SURFACE = UUID.randomUUID();

    @Mock private PricingZoneService zoneService;
    @Mock private ZoneRateService rateService;
    @Mock private CustomerRateSourceService rateSource;
    @Mock private DistrictLookupPort districts;
    @Mock private ServiceTypeRepository serviceTypes;

    private CustomerRateImportService service;

    @BeforeEach
    void setUp() {
        CompanyContext.setCompanyId(COMPANY);
        service = new CustomerRateImportService(zoneService, rateService, rateSource, districts, serviceTypes);
        when(districts.findDistrictByName("Pune")).thenReturn(Optional.of(new DistrictRef(PUNE, "PUN", "Pune", true)));
        when(districts.findDistrictByName("Mumbai")).thenReturn(Optional.of(new DistrictRef(MUMBAI, "MUM", "Mumbai", true)));
        when(serviceTypes.findAll()).thenReturn(List.of(type(AIR, "AIR", "Air"), type(SURFACE, "SURFACE", "Surface")));
        when(zoneService.search(eq(CUSTOMER), any(), any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        CompanyContext.clear();
    }

    private static ServiceType type(UUID id, String code, String name) {
        ServiceType t = new ServiceType();
        t.setId(id);
        t.setCode(code);
        t.setName(name);
        t.setCompanyId(COMPANY);
        return t;
    }

    /** The template's header row, then the given rows (each a map heading -> value). */
    private byte[] sheet(List<Map<String, String>> rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(service.template())); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.getSheetAt(0);
            List<String> headers = new java.util.ArrayList<>();
            s.getRow(0).forEach(c -> headers.add(c.getStringCellValue()));
            for (int r = s.getLastRowNum(); r >= 1; r--) {
                s.removeRow(s.getRow(r));
            }
            int n = 1;
            for (Map<String, String> m : rows) {
                Row row = s.createRow(n++);
                for (int c = 0; c < headers.size(); c++) {
                    String v = m.get(headers.get(c));
                    if (v != null) {
                        row.createCell(c).setCellValue(v);
                    }
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static Map<String, String> row(String zone, String districts, String serviceType, String... slabs) {
        Map<String, String> m = new java.util.HashMap<>();
        m.put("Zone Name", zone);
        m.put("Destination Districts", districts);
        m.put("Service Type", serviceType);
        m.put("Min Chargeable Weight (kg)", "1");
        for (int i = 0; i < slabs.length; i += 2) {
            m.put("Slab " + (i / 2 + 1) + " Up To (kg)", slabs[i]);
            m.put("Slab " + (i / 2 + 1) + " Rate/kg", slabs[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("the template lists the company's service types and opens with the sample rows")
    void templateHasServiceTypes() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(service.template()))) {
            assertThat(wb.getSheet("Service Types").getRow(1).getCell(0).getStringCellValue()).isEqualTo("AIR");
            assertThat(wb.getSheet("Rates").getRow(0).getCell(0).getStringCellValue()).isEqualTo("Zone Name");
            assertThat(wb.getSheet("Rates").getLastRowNum()).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    @DisplayName("preview checks every row and reports the bad ones without saving anything")
    void previewReportsErrors() throws Exception {
        byte[] file = sheet(List.of(
                row("Pune", "Pune", "AIR", "5", "90", "10", "80", "", "70"),
                row("Pune", "", "SURFACE", "5", "50", "", "40"),
                row("Mumbai", "Mumbai", "BULLET", "5", "60"),
                row("Nowhere", "Atlantis", "AIR", "5", "60"),
                row("Pune", "", "AIR", "5", "60")));

        ImportPreview p = service.preview(CUSTOMER, new ByteArrayInputStream(file));

        assertThat(p.rows()).hasSize(5);
        assertThat(p.okCount()).isEqualTo(2);
        assertThat(p.rows().get(0).slabsSummary()).isEqualTo("0-5 kg @ 90, 5-10 kg @ 80, 10-no limit kg @ 70");
        assertThat(p.rows().get(2).error()).contains("BULLET");
        assertThat(p.rows().get(3).error()).contains("Atlantis");
        assertThat(p.rows().get(4).error()).contains("already appears");
        verify(rateService, never()).save(any(), any(), any());
    }

    @Test
    @DisplayName("slab ranges must ascend, and an open-ended slab must be the last")
    void slabRules() throws Exception {
        ImportPreview p = service.preview(CUSTOMER, new ByteArrayInputStream(sheet(List.of(
                row("A", "Pune", "AIR", "10", "90", "5", "80"),
                row("B", "Mumbai", "AIR", "", "90", "5", "80"),
                row("A", "", "SURFACE", "5", ""),
                row("A", "", "", "5", "-1")))));

        assertThat(p.rows().get(0).error()).contains("Slab 2 up to must be more than 10");
        assertThat(p.rows().get(1).error()).contains("no upper limit");
        assertThat(p.rows().get(2).error()).contains("needs a rate");
        assertThat(p.rows().get(3).error()).contains("negative");
    }

    @Test
    @DisplayName("a district in two zones of the same customer is refused")
    void districtInTwoZones() throws Exception {
        ImportPreview p = service.preview(CUSTOMER, new ByteArrayInputStream(sheet(List.of(
                row("North", "Pune", "AIR", "5", "90"),
                row("South", "Pune", "AIR", "5", "90")))));
        assertThat(p.rows().get(1).error()).contains("two zones");
    }

    @Test
    @DisplayName("apply creates each zone once with its districts, saves every card and turns business rates on")
    void applySavesEverything() throws Exception {
        UUID zoneId = UUID.randomUUID();
        PricingZoneView view = org.mockito.Mockito.mock(PricingZoneView.class);
        com.courier.modules.customerpricing.domain.PricingZone zone = com.courier.modules.customerpricing.domain.PricingZone.builder().customerId(CUSTOMER).zoneName("Pune").build();
        zone.setId(zoneId);
        when(view.zone()).thenReturn(zone);
        when(zoneService.create(eq(CUSTOMER), eq("Pune"), any(), any())).thenReturn(view);

        ImportResultHolder r = new ImportResultHolder(service.apply(CUSTOMER, new ByteArrayInputStream(sheet(List.of(
                row("Pune", "Pune", "AIR", "5", "90", "", "80"),
                row("Pune", "", "SURFACE", "5", "50"))))));

        assertThat(r.result.zonesCreated()).isEqualTo(1);
        assertThat(r.result.cardsSaved()).isEqualTo(2);
        ArgumentCaptor<List<LocationInput>> loc = ArgumentCaptor.forClass(List.class);
        verify(zoneService, times(1)).create(eq(CUSTOMER), eq("Pune"), any(), loc.capture());
        assertThat(loc.getValue()).containsExactly(new LocationInput(PUNE, null));
        verify(rateService).save(eq(zoneId), eq(AIR), any());
        verify(rateService).save(eq(zoneId), eq(SURFACE), any());
        verify(rateSource).set(CUSTOMER, true);
    }

    @Test
    @DisplayName("apply refuses a sheet with any error and saves nothing")
    void applyIsAllOrNothing() throws Exception {
        assertThatThrownBy(() -> service.apply(CUSTOMER, new ByteArrayInputStream(sheet(List.of(
                row("Pune", "Pune", "AIR", "5", "90"),
                row("Bad", "Atlantis", "AIR", "5", "90"))))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("nothing was saved");
        verify(zoneService, never()).create(any(), any(), any(), any());
        verify(rateService, never()).save(any(), any(), any());
    }

    @Test
    @DisplayName("a file that is not a rate sheet is refused with a message")
    void junkFile() {
        assertThatThrownBy(() -> service.preview(CUSTOMER, new ByteArrayInputStream("nope".getBytes())))
                .hasMessageContaining("could not be read");
    }

    private record ImportResultHolder(CustomerRateImportService.ImportResult result) {
    }
}
