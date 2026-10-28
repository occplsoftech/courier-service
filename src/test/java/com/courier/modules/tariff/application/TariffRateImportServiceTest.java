package com.courier.modules.tariff.application;

import com.courier.modules.master.application.PackageTypeService;
import com.courier.modules.master.application.ServiceTypeService;
import com.courier.modules.master.domain.PackageType;
import com.courier.modules.master.domain.ServiceType;
import com.courier.modules.tariff.application.TariffRateCardService.LaneInput;
import com.courier.modules.tariff.domain.SlabRateType;
import com.courier.modules.tariff.domain.TariffRateCard;
import com.courier.modules.tariff.domain.TariffRateCardRepository;
import com.courier.modules.tariff.domain.TariffRateSlabRepository;
import com.courier.modules.tariff.domain.TariffZone;
import com.courier.modules.tariff.domain.TariffZoneRepository;
import com.courier.modules.tariff.domain.ZoneUsage;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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

/** The rate sheet import, with sheets in OCCPL Courier's cash-booking layout built in memory. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TariffRateImportServiceTest {

    private static final UUID COMPANY = UUID.randomUUID();

    @Mock private TariffRateCardRepository cards;
    @Mock private TariffRateSlabRepository slabs;
    @Mock private TariffZoneRepository zones;
    @Mock private ServiceTypeService serviceTypeService;
    @Mock private PackageTypeService packageTypeService;
    @Mock private TariffRateCardService rateCardService;

    private TariffRateImportService service;
    private TariffRateCard card;
    private final TariffZone pune = zone("PUNE", "Pune city", ZoneUsage.BOTH);
    private final TariffZone central = zone("CENTRAL", "Central India", ZoneUsage.TO);
    private final TariffZone south = zone("SOUTH", "South India", ZoneUsage.TO);
    private final TariffZone kerala = zone("KERALA", "Kerala", ZoneUsage.TO);
    private final TariffZone exPune = zone("EXPUNE", "Ex Pune", ZoneUsage.TO);

    private static TariffZone zone(String code, String name, ZoneUsage usage) {
        TariffZone z = TariffZone.builder().code(code).name(name).zoneUsage(usage).build();
        z.setCompanyId(COMPANY);
        return z;
    }

    private static ServiceType serviceType(String code, String name) {
        ServiceType s = new ServiceType();
        s.setCode(code);
        s.setName(name);
        return s;
    }

    private static PackageType packageType(String code, String name, boolean document) {
        PackageType p = new PackageType();
        p.setCode(code);
        p.setName(name);
        p.setDocumentType(document);
        return p;
    }

    private final ServiceType air = serviceType("AIR", "By Air");
    private final ServiceType surface = serviceType("SURFACE", "Surface");
    private final PackageType doc = packageType("DOC", "Document", true);
    private final PackageType nondoc = packageType("NONDOC", "Non Document", false);

    @BeforeEach
    void setUp() {
        service = new TariffRateImportService(cards, slabs, zones, serviceTypeService, packageTypeService, rateCardService);
        CompanyContext.setCompanyId(COMPANY);
        card = TariffRateCard.builder().code("PUNE_CASH").name("Pune cash").fromZoneId(pune.getId())
                .effectiveFrom(LocalDate.of(2025, 4, 1)).build();
        card.setCompanyId(COMPANY);
        when(cards.findByIdWithinCompany(card.getId(), COMPANY)).thenReturn(Optional.of(card));
        when(zones.findByIdWithinCompany(pune.getId(), COMPANY)).thenReturn(Optional.of(pune));
        when(zones.search(eq(COMPANY), any(), any(), any())).thenReturn(List.of(pune, central, south, kerala, exPune));
        when(serviceTypeService.search(any(), any())).thenReturn(new PageImpl<>(List.of(air, surface)));
        when(packageTypeService.search(any(), any())).thenReturn(new PageImpl<>(List.of(doc, nondoc)));
        when(slabs.findLane(any(), any(), any(), any(), any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        CompanyContext.clear();
    }

    /** A sheet in the real layout: an "Admin" title row, the header, then the rows given. */
    private static MockMultipartFile sheet(Object[]... rows) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("Sheet1");
            s.createRow(0).createCell(0).setCellValue("Admin");
            String[] header = {"Sr.", "Courier", "from zone", "to zone", "State", "City", "Mode", "Product", "Applicable From",
                    "Weight From", "Weight To", "Rate", "Rate Type", "Action"};
            Row h = s.createRow(1);
            for (int i = 0; i < header.length; i++) {
                h.createCell(i).setCellValue(header[i]);
            }
            for (int r = 0; r < rows.length; r++) {
                Row row = s.createRow(r + 2);
                Object[] v = rows[r];
                for (int c = 0; c < v.length; c++) {
                    if (v[c] instanceof Number n) {
                        row.createCell(c).setCellValue(n.doubleValue());
                    } else if (v[c] != null) {
                        row.createCell(c).setCellValue(v[c].toString());
                    }
                }
            }
            wb.write(out);
            return new MockMultipartFile("file", "rates.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }
    }

    private static Object[] row(int sr, String from, String to, String state, String mode, String product, double wFrom,
                                double wTo, double rate, String type) {
        return new Object[]{sr, "SELF", from, to, state, null, mode, product, "01-04-2025", wFrom, wTo, rate, type, "-->"};
    }

    /** Pune → Central by Air, documents: three fixed slabs, then per extra kg. */
    private static Object[][] centralAirDoc() {
        return new Object[][]{
                row(1, "PUNE", "CENTRAL", null, "BY AIR", "Document", 0.001, 0.25, 200, "Fixed"),
                row(2, "PUNE", "CENTRAL", null, "BY AIR", "Document", 0.251, 0.5, 200, "Fixed"),
                row(3, "PUNE", "CENTRAL", null, "BY AIR", "Document", 0.501, 1, 200, "Fixed"),
                row(4, "PUNE", "CENTRAL", null, "BY AIR", "Document", 1.001, 99, 200, "Per Kg")};
    }

    private static Object[][] concat(Object[][]... parts) {
        List<Object[]> all = new ArrayList<>();
        for (Object[][] p : parts) {
            all.addAll(List.of(p));
        }
        return all.toArray(new Object[0][]);
    }

    @Test
    @DisplayName("reads the cash-booking layout: names matched loosely, one lane, nothing saved on preview")
    void preview() throws IOException {
        TariffRateImportService.Result r = service.preview(card.getId(), sheet(centralAirDoc()));

        assertThat(r.errors()).isEmpty();
        assertThat(r.rowsRead()).isEqualTo(4);
        assertThat(r.lanes()).singleElement().satisfies(l -> {
            assertThat(l.toZoneCode()).isEqualTo("CENTRAL");
            assertThat(l.serviceTypeCode()).isEqualTo("AIR");
            assertThat(l.packageTypeCode()).isEqualTo("DOC");
            assertThat(l.slabCount()).isEqualTo(4);
            assertThat(l.summary()).isEqualTo("≤0.25 kg 200 · ≤0.5 kg 200 · ≤1 kg 200 · +200/kg to 99 kg");
            assertThat(l.replaces()).isFalse();
        });
        verify(rateCardService, never()).saveLane(any(), any());
    }

    @Test
    @DisplayName("repeated rows count once; other from zones are skipped; a state row goes to that state's zone; EX PUNE finds EXPUNE")
    void cleaning() throws IOException {
        TariffRateImportService.Result r = service.preview(card.getId(), sheet(concat(centralAirDoc(), centralAirDoc(), new Object[][]{
                row(9, "MUMBAI", "CENTRAL", null, "BY AIR", "Document", 0.001, 1, 150, "Fixed"),
                row(10, "PUNE", "SOUTH", "KERALA", "SURFACE", "Non Document", 0.001, 1, 120, "Fixed"),
                row(11, "PUNE", "SOUTH", "KERALA", "SURFACE", "Non Document", 1.001, 50, 60, "Per Kg"),
                row(12, "PUNE", "EX PUNE", null, "SURFACE", "Document", 0.001, 0.5, 40, "Fixed"),
                row(13, "PUNE", "EX PUNE", null, "SURFACE", "Document", 0.501, 9999, 40, "Addtion 500GM")})));

        assertThat(r.errors()).isEmpty();
        assertThat(r.duplicateRows()).isEqualTo(4);
        assertThat(r.skippedRows()).isEqualTo(1);
        assertThat(r.lanes()).extracting(TariffRateImportService.LanePreview::toZoneCode).containsExactly("CENTRAL", "KERALA", "EXPUNE");
        assertThat(r.warnings()).extracting(TariffRateImportService.Issue::message).anySatisfy(m -> assertThat(m).contains("MUMBAI"))
                .anySatisfy(m -> assertThat(m).contains("4 repeated row(s)"))
                .anySatisfy(m -> assertThat(m).contains("Addition 500GM"));
    }

    @Test
    @DisplayName("problems are listed by row: unknown zone, unknown mode, a state with no zone, two rates for one band, a gap")
    void problems() throws IOException {
        TariffRateImportService.Result r = service.preview(card.getId(), sheet(
                row(1, "PUNE", "NOWHERE", null, "BY AIR", "Document", 0.001, 1, 100, "Fixed"),
                row(2, "PUNE", "CENTRAL", null, "TELEPORT", "Document", 0.001, 1, 100, "Fixed"),
                row(3, "PUNE", "SOUTH", "GOA", "SURFACE", "Document", 0.001, 1, 100, "Fixed"),
                row(4, "PUNE", "CENTRAL", null, "SURFACE", "Document", 0.001, 1, 100, "Fixed"),
                row(5, "PUNE", "CENTRAL", null, "SURFACE", "Document", 0.001, 1, 110, "Fixed"),
                row(6, "PUNE", "CENTRAL", null, "SURFACE", "Document", 5, 10, 50, "Per Kg")));

        // Excel row numbers: the "Admin" title is row 1 and the header row 2, so the sheet's first rate is row 3.
        assertThat(r.errors()).extracting(TariffRateImportService.Issue::row).contains(3, 4, 5, 7);
        assertThat(r.errors()).extracting(TariffRateImportService.Issue::message)
                .anySatisfy(m -> assertThat(m).contains("NOWHERE"))
                .anySatisfy(m -> assertThat(m).contains("TELEPORT"))
                .anySatisfy(m -> assertThat(m).contains("create a to zone named GOA"))
                .anySatisfy(m -> assertThat(m).contains("rate 110 FIXED here but 100 FIXED on row 6"))
                .anySatisfy(m -> assertThat(m).contains("gap"));
    }

    @Test
    @DisplayName("import saves each lane through the rate card service; any problem refuses the whole file")
    void importAllOrNothing() throws IOException {
        TariffRateImportService.Result ok = service.importFile(card.getId(), sheet(concat(centralAirDoc(), new Object[][]{
                row(5, "PUNE", "CENTRAL", null, "SURFACE", "Non Document", 0.001, 1, 120, "Fixed")})));

        assertThat(ok.imported()).isTrue();
        ArgumentCaptor<LaneInput> lanes = ArgumentCaptor.forClass(LaneInput.class);
        verify(rateCardService, times(2)).saveLane(eq(card.getId()), lanes.capture());
        assertThat(lanes.getAllValues().get(0).slabs()).hasSize(4);
        assertThat(lanes.getAllValues().get(0).slabs().get(3).rateType()).isEqualTo(SlabRateType.PER_EXTRA_KG);
        assertThat(lanes.getAllValues().get(0).slabs().get(3).stepKg()).isEqualByComparingTo(BigDecimal.ONE);

        assertThatThrownBy(() -> service.importFile(card.getId(), sheet(row(1, "PUNE", "NOWHERE", null, "BY AIR", "Document", 0.001, 1, 100, "Fixed"))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("nothing was imported");
        verify(rateCardService, times(2)).saveLane(any(), any());
    }

    @Test
    @DisplayName("a sheet without the expected header is refused with what is missing")
    void badHeader() throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Row h = wb.createSheet().createRow(0);
            h.createCell(0).setCellValue("to zone");
            h.createCell(1).setCellValue("Rate");
            wb.write(out);
            MockMultipartFile file = new MockMultipartFile("file", "x.xlsx", null, out.toByteArray());
            assertThatThrownBy(() -> service.preview(card.getId(), file)).hasMessageContaining("missing column(s): FROMZONE");
        }
    }
}
