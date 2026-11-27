package com.courier.modules.tariff.application;

import com.courier.modules.customer.application.CustomerPortalLookup;
import com.courier.modules.customer.domain.Customer;
import com.courier.modules.customer.domain.CustomerType;
import com.courier.modules.customerpricing.application.CustomerRateSourceService;
import com.courier.modules.master.application.PackageTypeService;
import com.courier.modules.master.application.ServiceTypeService;
import com.courier.modules.master.domain.PackageType;
import com.courier.modules.master.domain.ServiceType;
import com.courier.modules.tariff.application.CustomerTariffImportService.UploadOptions;
import com.courier.modules.tariff.application.CustomerTariffImportService.UploadResult;
import com.courier.modules.tariff.application.TariffRateCardService.ChargeInput;
import com.courier.modules.tariff.application.TariffRateCardService.LaneInput;
import com.courier.modules.tariff.domain.ChargeCalcMethod;
import com.courier.modules.tariff.domain.SlabRateType;
import com.courier.modules.tariff.domain.TariffChargeRule;
import com.courier.modules.tariff.domain.TariffChargeRuleRepository;
import com.courier.modules.tariff.domain.TariffRateCard;
import com.courier.modules.tariff.domain.TariffRateCardRepository;
import com.courier.modules.tariff.domain.TariffRateSlabRepository;
import com.courier.modules.tariff.domain.TariffStatus;
import com.courier.modules.tariff.domain.TariffZone;
import com.courier.modules.tariff.domain.TariffZoneRepository;
import com.courier.modules.tariff.domain.ZoneUsage;
import com.courier.shared.audit.application.AuditService;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A business customer's rate sheet in the old software's layout, uploaded onto their own rate card. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CustomerTariffImportServiceTest {

    private static final UUID COMPANY = UUID.randomUUID();

    @Mock private TariffRateCardRepository cards;
    @Mock private TariffRateSlabRepository slabs;
    @Mock private TariffChargeRuleRepository charges;
    @Mock private TariffZoneRepository zones;
    @Mock private ServiceTypeService serviceTypeService;
    @Mock private PackageTypeService packageTypeService;
    @Mock private TariffRateCardService rateCardService;
    @Mock private CustomerPortalLookup customers;
    @Mock private CustomerRateSourceService rateSource;
    @Mock private AuditService auditService;

    private CustomerTariffImportService service;
    private Customer customer;
    private final List<TariffRateCard> saved = new ArrayList<>();

    private final TariffZone pune = zone("PUNE", "Pune city", ZoneUsage.BOTH);
    private final TariffZone central = zone("CENTRAL", "Central", ZoneUsage.TO);
    private final TariffZone east = zone("EAST", "East", ZoneUsage.TO);

    private final ServiceType air = serviceType("AIR", "By Air");
    private final ServiceType surface = serviceType("SURFACE", "Surface");
    private final ServiceType premium = serviceType("PREMIUM", "Premium");
    private final ServiceType surfaceCargo = serviceType("SURFACE_CARGO", "Surface Cargo");
    private final ServiceType airCargo = serviceType("AIR_CARGO", "Air Cargo");
    private final PackageType doc = packageType("DOC", "Document", true);
    private final PackageType nondoc = packageType("NONDOC", "Non Document", false);
    private final PackageType laptop = packageType("LAPTOP", "Laptop", false);

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

    @BeforeEach
    void setUp() {
        TariffRateImportService importer = new TariffRateImportService(cards, slabs, zones, serviceTypeService, packageTypeService,
                rateCardService);
        service = new CustomerTariffImportService(importer, rateCardService, cards, charges, slabs, zones, customers, rateSource,
                auditService);
        CompanyContext.setCompanyId(COMPANY);
        customer = Customer.builder().customerType(CustomerType.BUSINESS).companyName("A&A Global Resources Pvt Ltd.")
                .customerCode("CUS-00042").build();
        customer.setCompanyId(COMPANY);
        when(customers.findActiveBusinessCustomer(COMPANY, customer.getId())).thenReturn(Optional.of(customer));

        when(zones.findByIdWithinCompany(pune.getId(), COMPANY)).thenReturn(Optional.of(pune));
        when(zones.search(eq(COMPANY), any(), any(), any())).thenReturn(List.of(pune, central, east));
        when(serviceTypeService.search(any(), any())).thenReturn(new PageImpl<>(List.of(air, surface, premium, surfaceCargo, airCargo)));
        when(packageTypeService.search(any(), any())).thenReturn(new PageImpl<>(List.of(doc, nondoc, laptop)));
        when(slabs.findLane(any(), any(), any(), any(), any())).thenReturn(List.of());
        when(cards.findByCustomer(eq(COMPANY), eq(customer.getId()), any(), any())).thenAnswer(i -> List.copyOf(saved));
        when(cards.save(any(TariffRateCard.class))).thenAnswer(i -> {
            saved.add(i.getArgument(0));
            return i.getArgument(0);
        });
    }

    @AfterEach
    void tearDown() {
        CompanyContext.clear();
    }

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
                for (int c = 0; c < rows[r].length; c++) {
                    Object v = rows[r][c];
                    if (v instanceof Number n) {
                        row.createCell(c).setCellValue(n.doubleValue());
                    } else if (v != null) {
                        row.createCell(c).setCellValue(v.toString());
                    }
                }
            }
            wb.write(out);
            return new MockMultipartFile("file", "A_A_Global.xlsx", "application/octet-stream", out.toByteArray());
        }
    }

    private static Object[] row(int sr, String to, String mode, String product, double wFrom, double wTo, double rate, String type) {
        return new Object[]{sr, "SELF", "PUNE", to, null, null, mode, product, "01-04-2025", wFrom, wTo, rate, type, "-->"};
    }

    /** Rows as the four customers' sheets have them, every mode once. */
    private static Object[][] goodRows() {
        return new Object[][]{
                row(1, "CENTRAL", "SURFACE", "Document", 0.001, 0.5, 60, "Fixed"),
                row(2, "CENTRAL", "SURFACE", "Document", 0.501, 99, 100, "Addtion 500GM"),
                row(3, "CENTRAL", "BY AIR", "Non Document", 0.001, 1, 80, "Fixed"),
                row(4, "CENTRAL", "BY AIR", "Non Document", 1.001, 99, 80, "Per Kg"),
                row(5, "CENTRAL", "Premium", "Document", 0.001, 0.5, 250, "Fixed"),
                row(6, "CENTRAL", "Premium", "Document", 0.501, 999, 250, "Addtion 500GM"),
                row(7, "CENTRAL", "Cargo", "Non Document", 0.001, 20, 500, "Fixed"),
                row(8, "CENTRAL", "Cargo", "Non Document", 20.001, 99, 25, "Per Kg"),
                row(9, "EAST", "Cargo SURFACE", "Non Document", 0.001, 17, 850, "Fixed"),
                row(10, "EAST", "Cargo SURFACE", "Non Document", 17.001, 999, 50, "Per Kg"),
                row(11, "EAST", "LAPTOP-SURFACE", "Non Document", 0.001, 5, 650, "Per Box")};
    }

    @Test
    @DisplayName("modes map to the five service types; LAPTOP-SURFACE is SURFACE for laptops, per box; nothing saved on a check")
    void previewMapsModes() throws IOException {
        UploadResult r = service.preview(customer.getId(), new UploadOptions(null, null), sheet(goodRows()));

        assertThat(r.sheet().errors()).isEmpty();
        assertThat(r.newCard()).isTrue();
        assertThat(r.rateCardId()).isNull();
        assertThat(r.fromZoneId()).isEqualTo(pune.getId());
        assertThat(r.additionStepKg()).isEqualByComparingTo("0.5");
        assertThat(r.sheet().lanes()).extracting(l -> l.toZoneCode() + " " + l.serviceTypeCode() + " " + l.packageTypeCode())
                .containsExactly("CENTRAL SURFACE DOC", "CENTRAL AIR NONDOC", "CENTRAL PREMIUM DOC", "CENTRAL SURFACE_CARGO NONDOC",
                        "EAST SURFACE_CARGO NONDOC", "EAST SURFACE LAPTOP");
        assertThat(r.sheet().lanes().get(0).summary()).isEqualTo("≤0.5 kg 60 · +100/500 g to 99 kg");
        assertThat(r.sheet().lanes().get(5).summary()).isEqualTo("≤5 kg 650/box");
        verify(cards, never()).save(any());
        verify(rateCardService, never()).saveLane(any(), any());
    }

    @Test
    @DisplayName("\"Addition 500GM\" can be read per extra 1 kg instead")
    void additionPerKg() throws IOException {
        UploadResult r = service.preview(customer.getId(), new UploadOptions(null, BigDecimal.ONE), sheet(goodRows()));
        assertThat(r.sheet().lanes().get(0).summary()).isEqualTo("≤0.5 kg 60 · +100/kg to 99 kg");
        assertThatThrownBy(() -> service.preview(customer.getId(), new UploadOptions(null, new BigDecimal("2")), sheet(goodRows())))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("swapped Fixed / Per Kg rows are errors with their row numbers, and the upload is refused")
    void swappedRows() throws IOException {
        MockMultipartFile bad = sheet(
                row(1, "EAST", "BY AIR", "Non Document", 0.001, 0.5, 110, "Per Kg"),
                row(2, "EAST", "BY AIR", "Non Document", 0.501, 99, 110, "Fixed"),
                row(3, "CENTRAL", "SURFACE", "Non Document", 0.001, 0.5, 45, "Fixed"),
                row(4, "CENTRAL", "SURFACE", "Non Document", 0.501, 99, 45, "Fixed"),
                // Equal Fixed prices on light bands are fine.
                row(5, "CENTRAL", "BY AIR", "Document", 0.001, 0.25, 200, "Fixed"),
                row(6, "CENTRAL", "BY AIR", "Document", 0.251, 0.5, 200, "Fixed"),
                row(7, "CENTRAL", "BY AIR", "Document", 0.501, 99, 200, "Per Kg"));

        UploadResult r = service.preview(customer.getId(), new UploadOptions(null, null), bad);

        assertThat(r.sheet().errors()).extracting(TariffRateImportService.Issue::row).containsExactlyInAnyOrder(3, 4, 6); // Excel rows: data starts on row 3
        assertThat(r.sheet().errors()).anySatisfy(e -> assertThat(e.message()).contains("first weight band").contains("swapped"));
        assertThat(r.sheet().errors()).anySatisfy(e -> assertThat(e.message()).contains("every weight up to 99 kg would cost 45"));
        assertThatThrownBy(() -> service.importFile(customer.getId(), new UploadOptions(null, null), bad))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("nothing was imported");
        verify(cards, never()).save(any());
        verify(rateSource, never()).set(any(), eq(true));
    }

    @Test
    @DisplayName("first upload: creates the customer's card, copies the company's charges, saves lanes, activates, business rates on")
    void firstUpload() throws IOException {
        TariffRateCard company = TariffRateCard.builder().code("PUNE_CASH").name("Pune cash").fromZoneId(pune.getId())
                .effectiveFrom(LocalDate.of(2024, 4, 1)).gstPercent(new BigDecimal("18")).volumetricDivisor(5000)
                .status(TariffStatus.ACTIVE).build();
        when(cards.findByFromZone(COMPANY, pune.getId(), TariffStatus.ACTIVE)).thenReturn(List.of(company));
        TariffChargeRule fuel = TariffChargeRule.builder().rateCardId(company.getId()).chargeCode("FUEL").chargeName("Fuel")
                .calcMethod(ChargeCalcMethod.PERCENT_OF_FREIGHT).chargeValue(new BigDecimal("10")).build();
        when(charges.findByCard(COMPANY, company.getId())).thenReturn(List.of(fuel));

        UploadResult r = service.importFile(customer.getId(), new UploadOptions(null, null), sheet(goodRows()));

        assertThat(saved).singleElement().satisfies(c -> {
            assertThat(c.getCustomerId()).isEqualTo(customer.getId());
            assertThat(c.getCode()).isEqualTo("CUST-CUS-00042-PUNE");
            assertThat(c.getName()).isEqualTo("A&A Global Resources Pvt Ltd. rates");
            assertThat(c.getEffectiveFrom()).isEqualTo(LocalDate.of(2025, 4, 1));
            assertThat(c.getVolumetricDivisor()).isEqualTo(5000);
        });
        assertThat(r.newCard()).isTrue();
        assertThat(r.chargesFrom()).isEqualTo("PUNE_CASH");
        assertThat(r.sheet().imported()).isTrue();
        UUID cardId = saved.get(0).getId();
        ArgumentCaptor<ChargeInput> charge = ArgumentCaptor.forClass(ChargeInput.class);
        verify(rateCardService).addCharge(eq(cardId), charge.capture());
        assertThat(charge.getValue().code()).isEqualTo("FUEL");
        ArgumentCaptor<LaneInput> lanes = ArgumentCaptor.forClass(LaneInput.class);
        verify(rateCardService, times(6)).saveLane(eq(cardId), lanes.capture());
        assertThat(lanes.getAllValues().get(0).slabs().get(1).stepKg()).isEqualByComparingTo("0.5");
        assertThat(lanes.getAllValues().get(5).slabs().get(0).rateType()).isEqualTo(SlabRateType.PER_BOX);
        verify(rateCardService).activate(cardId);
        verify(rateSource).set(customer.getId(), true);
    }

    @Test
    @DisplayName("a later upload reuses the customer's card for that from zone")
    void laterUploadReusesCard() throws IOException {
        TariffRateCard existing = TariffRateCard.builder().customerId(customer.getId()).code("CUST-CUS-00042-PUNE").name("x")
                .fromZoneId(pune.getId()).effectiveFrom(LocalDate.of(2025, 4, 1)).status(TariffStatus.ACTIVE).build();
        saved.add(existing);

        UploadResult r = service.importFile(customer.getId(), new UploadOptions(pune.getId(), null), sheet(goodRows()));

        assertThat(r.newCard()).isFalse();
        assertThat(r.rateCardId()).isEqualTo(existing.getId());
        verify(cards, never()).save(any());
        verify(rateCardService, never()).activate(any());
        verify(rateCardService, never()).addCharge(any(), any());
    }

    @Test
    @DisplayName("only an active business customer can have rates uploaded")
    void notABusinessCustomer() throws IOException {
        UUID someone = UUID.randomUUID();
        when(customers.findActiveBusinessCustomer(COMPANY, someone)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.preview(someone, new UploadOptions(null, null), sheet(goodRows())))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("business customer");
        verify(cards, never()).findByCustomer(any(), any(), isNull(), any());
    }
}
