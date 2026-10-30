package com.courier.modules.customerpricing.application;

import com.courier.modules.customerpricing.application.PricingZoneService.LocationInput;
import com.courier.modules.customerpricing.application.ZoneRateService.SaveCommand;
import com.courier.modules.customerpricing.application.ZoneRateService.SlabInput;
import com.courier.modules.districtfreight.domain.DistrictLookupPort;
import com.courier.modules.master.domain.ServiceType;
import com.courier.modules.master.domain.ServiceTypeRepository;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.security.Roles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sets up all of a business customer's rates from one Excel sheet: the pricing zones (by district), and for each zone one rate
 * card per service type (AIR, SURFACE, CARGO, PREMIUM; blank = all services) with its charges and up to five weight slabs.
 *
 * <p>{@link #preview} checks the sheet row by row and writes nothing; {@link #apply} runs the same checks and, only when every
 * row is fine, saves everything in one transaction (a zone that already exists for the customer is reused, a rate card that
 * already exists is replaced), then switches the customer to their business rates.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerRateImportService {

    public static final int MAX_ROWS = 300;
    static final int MAX_SLABS = 5;
    private static final String COMPANY_ADMIN = "hasRole('" + Roles.COMPANY_ADMIN + "')";

    static final String ZONE = "Zone Name";
    static final String DISTRICTS = "Destination Districts";
    static final String SERVICE = "Service Type";
    static final String MIN_WEIGHT = "Min Chargeable Weight (kg)";
    static final String DOCKET = "Docket Charge";
    static final String MATHADI = "Mathadi Charge";
    static final String PACK = "Pack Charge";
    static final String ODA = "ODA Charge";
    static final String FUEL = "Fuel Surcharge %";
    static final String FOV = "FOV %";

    private static final List<String> FIXED = List.of(ZONE, DISTRICTS, SERVICE, MIN_WEIGHT, DOCKET, MATHADI, PACK, ODA, FUEL, FOV);

    private final PricingZoneService zoneService;
    private final ZoneRateService rateService;
    private final CustomerRateSourceService rateSource;
    private final DistrictLookupPort districts;
    private final ServiceTypeRepository serviceTypes;

    public record ImportRow(int row, String zone, List<String> districts, String serviceType, boolean ok, String error,
                            String slabsSummary) {
    }

    public record ImportPreview(List<ImportRow> rows, int okCount, int errorCount, int zoneCount) {
    }

    public record ImportResult(int zonesCreated, int zonesReused, int cardsSaved) {
    }

    /** One checked sheet row, ready to save. */
    private record Parsed(int row, String zone, List<LocationInput> locations, List<String> districtNames, UUID serviceTypeId,
                          String serviceLabel, SaveCommand command, String slabs) {
    }

    // ------------------------------------------------------------------ template

    @Transactional(readOnly = true)
    @PreAuthorize(COMPANY_ADMIN)
    public byte[] template() {
        UUID companyId = requireCompany();
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle head = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            head.setFont(bold);
            Sheet sheet = wb.createSheet("Rates");
            List<String> headers = new ArrayList<>(FIXED);
            for (int i = 1; i <= MAX_SLABS; i++) {
                headers.add("Slab " + i + " Up To (kg)");
                headers.add("Slab " + i + " Rate/kg");
            }
            Row h = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                Cell c = h.createCell(i);
                c.setCellValue(headers.get(i));
                c.setCellStyle(head);
                sheet.setColumnWidth(i, (i == 1 ? 30 : 18) * 256);
            }
            String[][] sample = {
                    {"Pune", "Pune", "AIR", "1", "20", "0", "0", "0", "10", "0.5", "5", "90", "10", "80", "", "70"},
                    {"Pune", "Pune", "SURFACE", "1", "20", "0", "0", "0", "10", "0.5", "5", "50", "10", "40", "", "35"},
                    {"Pune", "Pune", "CARGO", "5", "20", "0", "0", "0", "8", "0.5", "10", "30", "", "25"},
                    {"Mumbai Region", "Mumbai, Thane", "SURFACE", "1", "20", "0", "0", "100", "10", "0.5", "5", "60", "", "45"}};
            for (int r = 0; r < sample.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < sample[r].length; c++) {
                    if (!sample[r][c].isEmpty()) {
                        row.createCell(c).setCellValue(sample[r][c]);
                    }
                }
            }
            Sheet types = wb.createSheet("Service Types");
            Row th = types.createRow(0);
            th.createCell(0).setCellValue("Code (use in the Service Type column)");
            th.createCell(1).setCellValue("Name");
            th.getCell(0).setCellStyle(head);
            th.getCell(1).setCellStyle(head);
            int n = 1;
            for (ServiceType t : activeTypes(companyId)) {
                Row r = types.createRow(n++);
                r.createCell(0).setCellValue(t.getCode());
                r.createCell(1).setCellValue(t.getName());
            }
            types.setColumnWidth(0, 40 * 256);
            types.setColumnWidth(1, 30 * 256);
            Sheet help = wb.createSheet("Instructions");
            String[] lines = {
                    "One row per zone and service type. Up to " + MAX_ROWS + " rows. Replace the sample rows.",
                    "Zone Name: any name (e.g. Pune, Mumbai Region). Rows with the same zone name share the zone; a zone that already exists for the customer is reused.",
                    "Destination Districts: the districts the zone covers, separated by commas. Needed on at least one row of a new zone; a district can belong to one zone of the customer only.",
                    "Service Type: a code from the Service Types sheet (AIR, SURFACE, CARGO, PREMIUM...). Leave it blank for a rate that applies to every service without a rate of its own.",
                    "Slabs: 'Up To' is the heaviest weight the slab covers, 'Rate/kg' its price. Slab 1 starts above 0, the next above the previous Up To. Leave the last Up To blank for no limit.",
                    "With slabs 5 and 10: a 5 kg parcel is charged on slab 1, 5.3 kg on slab 2. The whole weight is charged at its slab's rate.",
                    "A rate that already exists for the zone and service type is replaced. Nothing is saved until every row is correct and you confirm."};
            for (int i = 0; i < lines.length; i++) {
                help.createRow(i).createCell(0).setCellValue(lines[i]);
            }
            help.setColumnWidth(0, 140 * 256);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessRuleException("Could not build the template.");
        }
    }

    // ------------------------------------------------------------------ preview / apply

    @Transactional(readOnly = true)
    @PreAuthorize(COMPANY_ADMIN)
    public ImportPreview preview(UUID customerId, InputStream file) {
        UUID companyId = requireCompany();
        Checked c = check(companyId, customerId, file);
        long ok = c.rows().stream().filter(ImportRow::ok).count();
        return new ImportPreview(c.rows(), (int) ok, c.rows().size() - (int) ok, (int) c.parsed().stream().map(Parsed::zone).distinct().count());
    }

    @Transactional
    @PreAuthorize(COMPANY_ADMIN)
    public ImportResult apply(UUID customerId, InputStream file) {
        UUID companyId = requireCompany();
        Checked c = check(companyId, customerId, file);
        if (c.rows().stream().anyMatch(r -> !r.ok())) {
            throw new BusinessRuleException("Some rows have errors. Fix them in your sheet and upload it again; nothing was saved.");
        }
        Map<String, UUID> zoneIds = new HashMap<>();
        int created = 0, reused = 0, saved = 0;
        Map<String, List<Parsed>> byZone = new LinkedHashMap<>();
        c.parsed().forEach(p -> byZone.computeIfAbsent(p.zone().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(p));
        for (List<Parsed> group : byZone.values()) {
            String name = group.get(0).zone();
            var existing = zoneService.search(customerId, null, name).stream()
                    .filter(z -> z.zone().getZoneName().equalsIgnoreCase(name)).findFirst();
            UUID zoneId;
            if (existing.isPresent()) {
                zoneId = existing.get().zone().getId();
                reused++;
            } else {
                Set<LocationInput> locations = new LinkedHashSet<>();
                group.forEach(p -> locations.addAll(p.locations()));
                zoneId = zoneService.create(customerId, name, null, new ArrayList<>(locations)).zone().getId();
                created++;
            }
            zoneIds.put(name.toLowerCase(Locale.ROOT), zoneId);
            for (Parsed p : group) {
                rateService.save(zoneId, p.serviceTypeId(), p.command());
                saved++;
            }
        }
        rateSource.set(customerId, true);
        log.info("Customer {} rates imported: {} zone(s) created, {} reused, {} rate card(s) saved", customerId, created, reused, saved);
        return new ImportResult(created, reused, saved);
    }

    // ------------------------------------------------------------------ checking

    private record Checked(List<ImportRow> rows, List<Parsed> parsed) {
    }

    private Checked check(UUID companyId, UUID customerId, InputStream file) {
        List<Map<String, String>> sheet = read(file);
        if (sheet.isEmpty()) {
            throw new BusinessRuleException("The sheet has no rates. Fill one row per zone and service type.");
        }
        Map<String, ServiceType> typesByCode = new HashMap<>();
        activeTypes(companyId).forEach(t -> typesByCode.put(t.getCode().toUpperCase(Locale.ROOT), t));
        List<String> existingZones = zoneService.search(customerId, null, null).stream()
                .map(z -> z.zone().getZoneName().toLowerCase(Locale.ROOT)).toList();

        List<ImportRow> rows = new ArrayList<>();
        List<Parsed> parsed = new ArrayList<>();
        Set<String> seenCards = new LinkedHashSet<>();
        Map<UUID, String> districtOwner = new HashMap<>();
        for (Map<String, String> v : sheet) {
            int rowNo = Integer.parseInt(v.get("#"));
            String zone = v.get(ZONE);
            List<String> names = splitList(v.get(DISTRICTS));
            String service = v.get(SERVICE);
            try {
                if (blank(zone)) {
                    throw new BusinessRuleException("Zone name is required.");
                }
                zone = zone.trim();
                String zoneKey = zone.toLowerCase(Locale.ROOT);
                ServiceType type = null;
                if (!blank(service)) {
                    type = typesByCode.get(service.trim().toUpperCase(Locale.ROOT));
                    if (type == null) {
                        throw new BusinessRuleException("Service type '" + service.trim() + "' is not one of your service types ("
                                + String.join(", ", typesByCode.keySet()) + ").");
                    }
                }
                List<LocationInput> locations = new ArrayList<>();
                Map<UUID, String> claimed = new LinkedHashMap<>();
                for (String d : names) {
                    var found = districts.findDistrictByName(d)
                            .orElseThrow(() -> new BusinessRuleException("District '" + d + "' was not found."));
                    locations.add(new LocationInput(found.districtId(), null));
                    String owner = districtOwner.get(found.districtId());
                    claimed.put(found.districtId(), zoneKey);
                    if (owner != null && !owner.equals(zoneKey)) {
                        throw new BusinessRuleException("District '" + d + "' is in two zones (" + owner + " and " + zoneKey
                                + "); a district can belong to one zone of the customer.");
                    }
                }
                if (locations.isEmpty() && !existingZones.contains(zoneKey)
                        && sheet.stream().noneMatch(o -> zoneKey.equalsIgnoreCase(String.valueOf(o.get(ZONE)).trim()) && !splitList(o.get(DISTRICTS)).isEmpty())) {
                    throw new BusinessRuleException("Zone '" + zone + "' is new: give its destination districts.");
                }
                String cardKey = zoneKey + "|" + (type == null ? "*" : type.getCode().toUpperCase(Locale.ROOT));
                if (!seenCards.add(cardKey)) {
                    throw new BusinessRuleException("This zone and service type already appears on another row.");
                }
                List<SlabInput> slabs = new ArrayList<>();
                StringBuilder summary = new StringBuilder();
                BigDecimal prev = BigDecimal.ZERO;
                for (int i = 1; i <= MAX_SLABS; i++) {
                    String upTo = v.get("Slab " + i + " Up To (kg)");
                    String rate = v.get("Slab " + i + " Rate/kg");
                    if (blank(upTo) && blank(rate)) {
                        continue;
                    }
                    if (blank(rate)) {
                        throw new BusinessRuleException("Slab " + i + " needs a rate per kg.");
                    }
                    BigDecimal max = blank(upTo) ? null : decimal(upTo, "Slab " + i + " up to");
                    if (max != null && max.compareTo(prev) <= 0) {
                        throw new BusinessRuleException("Slab " + i + " up to must be more than " + plain(prev) + " kg.");
                    }
                    BigDecimal price = decimal(rate, "Slab " + i + " rate");
                    if (price.signum() < 0) {
                        throw new BusinessRuleException("Slab " + i + " rate cannot be negative.");
                    }
                    slabs.add(new SlabInput(prev, max, price));
                    summary.append(summary.length() == 0 ? "" : ", ").append(plain(prev)).append('-')
                            .append(max == null ? "no limit" : plain(max)).append(" kg @ ").append(plain(price));
                    if (max == null) {
                        // an open-ended slab must be the last one used
                        for (int j = i + 1; j <= MAX_SLABS; j++) {
                            if (!blank(v.get("Slab " + j + " Up To (kg)")) || !blank(v.get("Slab " + j + " Rate/kg"))) {
                                throw new BusinessRuleException("Slab " + i + " has no upper limit, so no slab can follow it.");
                            }
                        }
                        break;
                    }
                    prev = max;
                }
                if (slabs.isEmpty()) {
                    throw new BusinessRuleException("At least one weight slab is required.");
                }
                SaveCommand cmd = new SaveCommand(orZero(v.get(MIN_WEIGHT), "Min chargeable weight"), orZero(v.get(DOCKET), "Docket"),
                        orZero(v.get(MATHADI), "Mathadi"), orZero(v.get(PACK), "Pack"), orZero(v.get(ODA), "ODA"),
                        percent(v.get(FUEL), "Fuel surcharge %"), percent(v.get(FOV), "FOV %"), null, null, slabs, null);
                claimed.forEach(districtOwner::putIfAbsent); // only a fully valid row claims its districts
                parsed.add(new Parsed(rowNo, zone, locations, names, type == null ? null : type.getId(),
                        type == null ? "All services" : type.getName(), cmd, summary.toString()));
                rows.add(new ImportRow(rowNo, zone, names, type == null ? "All services" : type.getName(), true, null, summary.toString()));
            } catch (BusinessRuleException e) {
                rows.add(new ImportRow(rowNo, zone, names, blank(service) ? "All services" : service.trim(), false, e.getMessage(), null));
            }
        }
        return new Checked(rows, parsed);
    }

    private List<ServiceType> activeTypes(UUID companyId) {
        return serviceTypes.findAll().stream().filter(t -> companyId.equals(t.getCompanyId()) && t.isActive() && t.getCode() != null)
                .sorted(Comparator.comparing(ServiceType::getCode)).toList();
    }

    // ------------------------------------------------------------------ values

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static List<String> splitList(String s) {
        if (blank(s)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String p : s.split("[,;]")) {
            if (!p.isBlank()) {
                out.add(p.trim());
            }
        }
        return out;
    }

    private static BigDecimal decimal(String raw, String label) {
        try {
            return new BigDecimal(raw.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            throw new BusinessRuleException(label + " must be a number.");
        }
    }

    private static BigDecimal orZero(String raw, String label) {
        if (blank(raw)) {
            return BigDecimal.ZERO;
        }
        BigDecimal v = decimal(raw, label);
        if (v.signum() < 0) {
            throw new BusinessRuleException(label + " cannot be negative.");
        }
        return v;
    }

    private static BigDecimal percent(String raw, String label) {
        BigDecimal v = orZero(raw, label);
        if (v.compareTo(new BigDecimal("100")) > 0) {
            throw new BusinessRuleException(label + " cannot be more than 100.");
        }
        return v;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static UUID requireCompany() {
        return CompanyContext.getCompanyId().orElseThrow(() -> new BusinessRuleException(
                "No company is bound to this request. Customer rates belong to a company."));
    }

    // ------------------------------------------------------------------ sheet

    /** The data rows keyed by heading (plus "#" = the sheet row number); fully blank rows are skipped. */
    private List<Map<String, String>> read(InputStream file) {
        DataFormatter fmt = new DataFormatter();
        try (Workbook wb = new XSSFWorkbook(file)) {
            Sheet sheet = wb.getSheetAt(0);
            Row head = sheet.getRow(0);
            if (head == null) {
                throw new BusinessRuleException("The first row must hold the column headings. Download the template.");
            }
            Map<Integer, String> cols = new HashMap<>();
            Set<String> known = new LinkedHashSet<>(FIXED);
            for (int i = 1; i <= MAX_SLABS; i++) {
                known.add("Slab " + i + " Up To (kg)");
                known.add("Slab " + i + " Rate/kg");
            }
            for (Cell c : head) {
                String t = fmt.formatCellValue(c).trim();
                for (String k : known) {
                    if (k.equalsIgnoreCase(t)) {
                        cols.put(c.getColumnIndex(), k);
                    }
                }
            }
            if (!cols.values().containsAll(List.of(ZONE, DISTRICTS, SERVICE, "Slab 1 Up To (kg)", "Slab 1 Rate/kg"))) {
                throw new BusinessRuleException("The column headings do not match the template. Download the template again and use it as is.");
            }
            List<Map<String, String>> rows = new ArrayList<>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row r = sheet.getRow(i);
                if (r == null) {
                    continue;
                }
                Map<String, String> m = new HashMap<>();
                boolean any = false;
                for (Map.Entry<Integer, String> e : cols.entrySet()) {
                    Cell c = r.getCell(e.getKey());
                    String val = c == null ? "" : fmt.formatCellValue(c).trim();
                    any |= !val.isEmpty();
                    m.put(e.getValue(), val);
                }
                if (!any) {
                    continue;
                }
                if (rows.size() >= MAX_ROWS) {
                    throw new BusinessRuleException("The sheet has more than " + MAX_ROWS + " rows. Split it into smaller files.");
                }
                m.put("#", String.valueOf(i + 1));
                rows.add(m);
            }
            return rows;
        } catch (IOException | RuntimeException e) {
            if (e instanceof BusinessRuleException b) {
                throw b;
            }
            throw new BusinessRuleException("This file could not be read. Upload an Excel (.xlsx) file made from the template.");
        }
    }
}
