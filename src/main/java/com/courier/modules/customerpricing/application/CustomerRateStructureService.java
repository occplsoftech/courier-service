package com.courier.modules.customerpricing.application;

import com.courier.modules.customer.application.CustomerPortalLookup;
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
import com.courier.modules.master.domain.ServiceType;
import com.courier.modules.master.domain.ServiceTypeRepository;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import com.courier.shared.security.Roles;
import com.courier.shared.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A business customer's rate structure (V177): the company's printed rate card, imported as it is.
 *
 * <p><b>The sheet</b> (the template, or the same layout typed by hand):
 * <ul>
 *   <li>"AIR COURIER MODE" table: one row per destination group (WEST, METROS, REST OF INDIA, EAST-NORTH EAST) with a
 *       PER KG rate. Saved for the air courier service, any origin.</li>
 *   <li>"DESTINATION" (cargo) table: one row per origin region (North, South, West, East, North East); columns Within
 *       State, Rest of India and North East, each By Surface and/or By Air. A cell may also hold "25/100" (surface/air)
 *       as on the printed card; "-" or blank is "no rate". Saved for the cargo by surface and cargo by air services.</li>
 *   <li>"Minimum Chargeable weight 20kgs": the cargo minimum. Air courier has none.</li>
 * </ul>
 *
 * <p><b>Pricing</b> ({@link #quote}): for a customer on business rates with a structure for the booked service.
 * Cargo picks the row by where the shipment starts (the booking branch's pincode: its regional zone, or North East
 * when its zone is NORTH EAST) and the column by where it goes (same state, else North East by zone, else Rest of
 * India). Air courier picks West or East-North East by the destination's regional zone, then Metros (Delhi, Mumbai,
 * Kolkata, Chennai, Bangalore, Hyderabad), else Rest of India. Freight = rate × max(chargeable weight, minimum).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerRateStructureService {

    private static final String COMPANY_ADMIN = "hasRole('" + Roles.COMPANY_ADMIN + "')";
    private static final int MONEY = 2;

    /** Metro districts (any pincode in Delhi state is a metro too). Names as the geography masters spell them. */
    private static final Set<String> METRO_DISTRICTS = Set.of("MUMBAI", "MUMBAISUBURBAN", "KOLKATA", "CHENNAI",
            "BANGALORE", "BANGALOREURBAN", "BENGALURU", "BENGALURUURBAN", "HYDERABAD");
    private static final Pattern MIN_WEIGHT = Pattern.compile("minimum\\s*chargeable\\s*weight\\D*(\\d+(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile("^\\s*₹?\\s*(\\d+(?:\\.\\d+)?)\\s*$");
    private static final Pattern PAIR = Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?|-)\\s*/\\s*(\\d+(?:\\.\\d+)?|-)\\s*$");

    private final StructureRateRepository rates;
    private final ServiceTypeRepository serviceTypes;
    private final CustomerPortalLookup customerLookup;
    private final CustomerRateSourceService rateSource;
    private final PincodeService pincodes;
    private final DistrictService districts;
    private final StateService states;
    private final RegionalZoneService regionalZones;

    // ------------------------------------------------------------------ records

    public enum Mode { SURFACE, AIR }

    /** The three services the two tables price. Any may be null when its rates are absent from the sheet. */
    public record Services(UUID airCourier, UUID cargoSurface, UUID cargoAir) {
    }

    public record AirRate(DestinationGroup group, String groupLabel, BigDecimal ratePerKg) {
    }

    public record CargoRate(OriginRegion origin, String originLabel, DestinationGroup group, String groupLabel,
                            BigDecimal surface, BigDecimal air) {
    }

    public record Issue(Integer row, String message) {
    }

    /** What the sheet holds, checked; {@code errors} empty means it can be imported. */
    public record Parsed(List<AirRate> airCourier, List<CargoRate> cargo, BigDecimal cargoMinChargeableKg,
                         List<Issue> errors, List<Issue> warnings) {
    }

    public record ImportResult(int ratesSaved, int airCourierRates, int cargoSurfaceRates, int cargoAirRates) {
    }

    /** One customer's stored structure, for display. */
    public record StructureView(UUID customerId, List<Row> rows) {
        public record Row(UUID serviceTypeId, String serviceTypeCode, String serviceTypeName, OriginRegion origin,
                          DestinationGroup group, BigDecimal ratePerKg, BigDecimal minChargeableKg) {
        }
    }

    /** A price from the structure. {@code label}: e.g. "Cargo: West → Rest of India". */
    public record Quote(BigDecimal ratePerKg, BigDecimal chargeableWeight, BigDecimal freight, String label) {
    }

    // ------------------------------------------------------------------ read

    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public StructureView view(UUID customerId) {
        UUID companyId = requireCompany();
        Map<UUID, ServiceType> types = new LinkedHashMap<>();
        List<StructureView.Row> rows = rates.findByCustomer(companyId, customerId).stream()
                .map(r -> {
                    ServiceType t = types.computeIfAbsent(r.getServiceTypeId(),
                            id -> serviceTypes.findByIdWithinCompany(id, companyId).orElse(null));
                    return new StructureView.Row(r.getServiceTypeId(), t == null ? null : t.getCode(),
                            t == null ? null : t.getName(), r.getOriginRegion(), r.getDestinationGroup(),
                            r.getRatePerKg(), r.getMinChargeableKg());
                })
                .sorted((a, b) -> {
                    int c = String.valueOf(a.serviceTypeCode()).compareTo(String.valueOf(b.serviceTypeCode()));
                    if (c != 0) return c;
                    c = Integer.compare(a.origin() == null ? -1 : a.origin().ordinal(), b.origin() == null ? -1 : b.origin().ordinal());
                    return c != 0 ? c : a.group().compareTo(b.group());
                })
                .toList();
        return new StructureView(customerId, rows);
    }

    // ------------------------------------------------------------------ pricing

    /**
     * The customer's structure price for a booking, or empty when the customer is not on business rates or has no
     * structure for the service. Refuses (rather than falling back) when the structure exists but has no rate for the
     * lane, so a wrong price is never quoted silently.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public Optional<Quote> quote(UUID customerId, UUID serviceTypeId, String originPincode, String destinationPincode,
                                 BigDecimal chargeableWeight) {
        UUID companyId = requireCompany();
        if (customerId == null || serviceTypeId == null || destinationPincode == null) {
            return Optional.empty();
        }
        if (!customerLookup.usesBusinessRates(companyId, customerId)) {
            return Optional.empty();
        }
        List<StructureRate> rows = rates.findByCustomerAndService(companyId, customerId, serviceTypeId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Place to = place(destinationPincode);
        boolean cargo = rows.stream().anyMatch(r -> r.getOriginRegion() != null);
        StructureRate rate;
        String label;
        if (cargo) {
            if (originPincode == null || originPincode.isBlank()) {
                throw new BusinessRuleException("The booking branch has no pincode, so the cargo rate (by origin region) cannot be chosen.");
            }
            Place from = place(originPincode);
            OriginRegion origin = from.originRegion();
            if (origin == null) {
                throw new BusinessRuleException("This customer's cargo rates have no row for shipments starting in %s (pincode %s)."
                        .formatted(from.regionLabel(), originPincode.trim()));
            }
            DestinationGroup group = from.stateId() != null && from.stateId().equals(to.stateId()) ? DestinationGroup.WITHIN_STATE
                    : to.northEast() ? DestinationGroup.NORTH_EAST : DestinationGroup.REST_OF_INDIA;
            rate = rows.stream().filter(r -> r.getOriginRegion() == origin && r.getDestinationGroup() == group).findFirst()
                    .orElseThrow(() -> new BusinessRuleException("This customer's rate structure has no %s rate from %s to %s."
                            .formatted(serviceName(companyId, serviceTypeId), origin.label(), group.label())));
            label = "%s → %s".formatted(origin.label(), group.label());
        } else {
            DestinationGroup group = to.airCourierGroup();
            rate = rows.stream().filter(r -> r.getDestinationGroup() == group).findFirst()
                    .orElseThrow(() -> new BusinessRuleException("This customer's rate structure has no %s rate to %s."
                            .formatted(serviceName(companyId, serviceTypeId), group.label())));
            label = group.label();
        }
        BigDecimal weight = chargeableWeight == null ? BigDecimal.ZERO : chargeableWeight;
        BigDecimal chargeable = weight.max(rate.getMinChargeableKg() == null ? BigDecimal.ZERO : rate.getMinChargeableKg());
        BigDecimal freight = rate.getRatePerKg().multiply(chargeable).setScale(MONEY, RoundingMode.HALF_UP);
        return Optional.of(new Quote(rate.getRatePerKg(), chargeable, freight, "Rate structure: " + label));
    }

    /** What pricing needs to know about one pincode. */
    private record Place(UUID stateId, String stateName, String districtName, String regionalZoneCode, String zone) {

        boolean northEast() {
            return "NORTHEAST".equals(norm(zone));
        }

        OriginRegion originRegion() {
            if (northEast()) return OriginRegion.NORTH_EAST;
            if (regionalZoneCode == null) return null;
            return switch (regionalZoneCode) {
                case "NORTH" -> OriginRegion.NORTH;
                case "SOUTH" -> OriginRegion.SOUTH;
                case "EAST" -> OriginRegion.EAST;
                case "WEST" -> OriginRegion.WEST;
                default -> null;
            };
        }

        String regionLabel() {
            return regionalZoneCode == null ? "an unknown region" : regionalZoneCode;
        }

        /** West and East-North East by regional zone first, then the metros, then the rest of India. */
        DestinationGroup airCourierGroup() {
            if ("WEST".equals(regionalZoneCode)) return DestinationGroup.WEST;
            if ("EAST".equals(regionalZoneCode) || northEast()) return DestinationGroup.EAST_NORTH_EAST;
            if ("DELHI".equals(norm(stateName)) || METRO_DISTRICTS.contains(norm(districtName))) return DestinationGroup.METROS;
            return DestinationGroup.REST_OF_INDIA;
        }
    }

    private Place place(String pincode) {
        String code = pincode.trim();
        PincodeLocation loc = pincodes.location(code)
                .orElseThrow(() -> new BusinessRuleException("Pincode " + code + " is not in the pincode master."));
        District district = loc.districtId() == null ? null : tryGet(() -> districts.getById(loc.districtId()));
        UUID stateId = district == null ? null : district.getStateId();
        var state = stateId == null ? null : tryGet(() -> states.getById(stateId));
        var regional = loc.regionalZoneId() == null ? null : tryGet(() -> regionalZones.getById(loc.regionalZoneId()));
        String stateName = state == null ? null : state.getName();
        String rz = regional == null ? null : regional.getCode();
        return new Place(stateId, stateName, district == null ? null : district.getName(), rz, loc.zone());
    }

    private String serviceName(UUID companyId, UUID serviceTypeId) {
        return serviceTypes.findByIdWithinCompany(serviceTypeId, companyId).map(ServiceType::getName).orElse("this service");
    }

    // ------------------------------------------------------------------ import

    @Transactional(readOnly = true)
    @PreAuthorize(COMPANY_ADMIN)
    public Parsed preview(UUID customerId, Services services, InputStream file) {
        UUID companyId = requireCompany();
        requireCustomer(companyId, customerId);
        Parsed parsed = parse(file);
        List<Issue> errors = new ArrayList<>(parsed.errors());
        errors.addAll(checkServices(companyId, services, parsed));
        return new Parsed(parsed.airCourier(), parsed.cargo(), parsed.cargoMinChargeableKg(), errors, parsed.warnings());
    }

    /**
     * All or nothing: refused when the sheet or the service choice has an error. Replaces the customer's structure for
     * the chosen services and switches the customer to their business rates.
     */
    @Transactional
    @PreAuthorize(COMPANY_ADMIN)
    public ImportResult apply(UUID customerId, Services services, InputStream file) {
        UUID companyId = requireCompany();
        Parsed parsed = preview(customerId, services, file);
        if (!parsed.errors().isEmpty()) {
            throw new BusinessRuleException("The sheet has %d problem(s); nothing was imported. Check it, fix it and upload it again."
                    .formatted(parsed.errors().size()));
        }
        List<StructureRate> toSave = new ArrayList<>();
        int air = 0, surface = 0, cargoAir = 0;
        for (AirRate a : parsed.airCourier()) {
            toSave.add(rate(customerId, services.airCourier(), null, a.group(), a.ratePerKg(), BigDecimal.ZERO));
            air++;
        }
        BigDecimal min = parsed.cargoMinChargeableKg() == null ? BigDecimal.ZERO : parsed.cargoMinChargeableKg();
        for (CargoRate c : parsed.cargo()) {
            if (c.surface() != null) {
                toSave.add(rate(customerId, services.cargoSurface(), c.origin(), c.group(), c.surface(), min));
                surface++;
            }
            if (c.air() != null) {
                toSave.add(rate(customerId, services.cargoAir(), c.origin(), c.group(), c.air(), min));
                cargoAir++;
            }
        }
        if (toSave.isEmpty()) {
            throw new BusinessRuleException("No rates were found in the sheet.");
        }
        UUID actor = SecurityUtils.getCurrentUserId().orElse(null);
        Set<UUID> replaced = new java.util.HashSet<>();
        toSave.forEach(r -> replaced.add(r.getServiceTypeId()));
        for (UUID serviceTypeId : replaced) {
            rates.findByCustomerAndService(companyId, customerId, serviceTypeId).forEach(old -> {
                old.softDelete(actor);
                rates.save(old);
            });
        }
        rates.flush();
        rates.saveAll(toSave);
        rateSource.set(customerId, true);
        log.info("Rate structure imported for customer {}: {} air courier, {} cargo surface, {} cargo air rate(s)",
                customerId, air, surface, cargoAir);
        return new ImportResult(toSave.size(), air, surface, cargoAir);
    }

    private static StructureRate rate(UUID customerId, UUID serviceTypeId, OriginRegion origin, DestinationGroup group,
                                      BigDecimal perKg, BigDecimal min) {
        return StructureRate.builder().customerId(customerId).serviceTypeId(serviceTypeId).originRegion(origin)
                .destinationGroup(group).ratePerKg(perKg).minChargeableKg(min).build();
    }

    private List<Issue> checkServices(UUID companyId, Services services, Parsed parsed) {
        List<Issue> errors = new ArrayList<>();
        Services s = services == null ? new Services(null, null, null) : services;
        boolean needAir = !parsed.airCourier().isEmpty();
        boolean needSurface = parsed.cargo().stream().anyMatch(c -> c.surface() != null);
        boolean needCargoAir = parsed.cargo().stream().anyMatch(c -> c.air() != null);
        requireService(companyId, s.airCourier(), needAir, "the air courier rates", errors);
        requireService(companyId, s.cargoSurface(), needSurface, "the cargo by surface rates", errors);
        requireService(companyId, s.cargoAir(), needCargoAir, "the cargo by air rates", errors);
        List<UUID> used = new ArrayList<>();
        if (needAir && s.airCourier() != null) used.add(s.airCourier());
        if (needSurface && s.cargoSurface() != null) used.add(s.cargoSurface());
        if (needCargoAir && s.cargoAir() != null) used.add(s.cargoAir());
        if (used.stream().distinct().count() < used.size()) {
            errors.add(new Issue(null, "Choose a different service type for air courier, cargo by surface and cargo by air: "
                    + "a booking picks its rate by its service type."));
        }
        return errors;
    }

    private void requireService(UUID companyId, UUID id, boolean needed, String what, List<Issue> errors) {
        if (!needed) return;
        if (id == null) {
            errors.add(new Issue(null, "Choose the service type for " + what + "."));
        } else if (serviceTypes.findByIdWithinCompany(id, companyId).isEmpty()) {
            errors.add(new Issue(null, "The service type chosen for " + what + " was not found."));
        }
    }

    private void requireCustomer(UUID companyId, UUID customerId) {
        if (customerId == null) {
            throw new BusinessRuleException("Choose the business customer.");
        }
        try {
            rateSource.get(customerId);
        } catch (ResourceNotFoundException e) {
            throw new BusinessRuleException("No such business customer: " + customerId);
        }
    }

    // ------------------------------------------------------------------ parsing

    /** Reads the two tables and the minimum weight, wherever they sit in the first sheet. */
    static Parsed parse(InputStream file) {
        List<AirRate> air = new ArrayList<>();
        List<CargoRate> cargo = new ArrayList<>();
        List<Issue> errors = new ArrayList<>();
        List<Issue> warnings = new ArrayList<>();
        BigDecimal min = null;
        List<List<String>> grid = new ArrayList<>();
        try (Workbook wb = new XSSFWorkbook(file)) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                List<String> cells = new ArrayList<>();
                if (row != null) {
                    for (int c = 0; c < Math.max(0, row.getLastCellNum()); c++) {
                        Cell cell = row.getCell(c);
                        cells.add(cell == null ? "" : fmt.formatCellValue(cell).trim());
                    }
                }
                grid.add(cells);
            }
        } catch (IOException | RuntimeException e) {
            throw new BusinessRuleException("The file could not be read as an Excel (.xlsx) sheet.");
        }

        for (List<String> row : grid) {
            for (String v : row) {
                Matcher m = MIN_WEIGHT.matcher(v);
                if (m.find()) min = new BigDecimal(m.group(1));
            }
        }

        int airHead = find(grid, "AIRCOURIERMODE");
        if (airHead >= 0) {
            int rateCol = column(grid.get(airHead), "PERKG");
            if (rateCol < 0) {
                errors.add(new Issue(airHead + 1, "The AIR COURIER MODE table has no PER KG column."));
            } else {
                for (int r = airHead + 1; r < grid.size(); r++) {
                    String name = first(grid.get(r));
                    if (name.isEmpty()) break;
                    DestinationGroup g = airGroup(name);
                    if (g == null) break;
                    String cell = at(grid.get(r), rateCol);
                    BigDecimal v = number(cell);
                    if (v == null) errors.add(new Issue(r + 1, "%s: the rate '%s' is not a number.".formatted(name, cell)));
                    else if (air.stream().anyMatch(a -> a.group() == g)) errors.add(new Issue(r + 1, name + " is listed twice."));
                    else air.add(new AirRate(g, g.label(), v));
                }
                if (air.isEmpty()) warnings.add(new Issue(airHead + 1, "The AIR COURIER MODE table has no rates."));
            }
        }

        int cargoHead = find(grid, "DESTINATION");
        if (cargoHead >= 0) {
            List<String> head = grid.get(cargoHead);
            // Which destination group each column belongs to: a merged heading sits in its first column only.
            Map<Integer, DestinationGroup> groupOf = new LinkedHashMap<>();
            DestinationGroup current = null;
            int width = head.size();
            for (int r = cargoHead; r < Math.min(grid.size(), cargoHead + 3); r++) width = Math.max(width, grid.get(r).size());
            for (int c = 1; c < width; c++) {
                String h = norm(at(head, c));
                if (h.contains("WITHINSTATE")) current = DestinationGroup.WITHIN_STATE;
                else if (h.contains("RESTOFINDIA")) current = DestinationGroup.REST_OF_INDIA;
                else if (h.contains("NORTHEAST")) current = DestinationGroup.NORTH_EAST;
                if (current != null) groupOf.put(c, current);
            }
            // Optional sub-heading row: By Surface / By Air.
            int dataStart = cargoHead + 1;
            Map<Integer, Mode> modeOf = new java.util.HashMap<>();
            if (dataStart < grid.size() && originRegion(first(grid.get(dataStart))) == null) {
                List<String> sub = grid.get(dataStart);
                for (Integer c : groupOf.keySet()) {
                    String s = norm(at(sub, c));
                    if (s.contains("SURFACE")) modeOf.put(c, Mode.SURFACE);
                    else if (s.contains("AIR")) modeOf.put(c, Mode.AIR);
                }
                dataStart++;
            }
            // Within State is by surface only, as on the card, when its column says no mode.
            for (Map.Entry<Integer, DestinationGroup> e : groupOf.entrySet()) {
                if (e.getValue() == DestinationGroup.WITHIN_STATE && !modeOf.containsKey(e.getKey())
                        && norm(at(head, e.getKey())).contains("SURFACE")) {
                    modeOf.put(e.getKey(), Mode.SURFACE);
                }
            }
            for (int r = dataStart; r < grid.size(); r++) {
                String name = first(grid.get(r));
                OriginRegion origin = originRegion(name);
                if (origin == null) break;
                Map<DestinationGroup, BigDecimal[]> byGroup = new EnumMap<>(DestinationGroup.class);
                for (Map.Entry<Integer, DestinationGroup> e : groupOf.entrySet()) {
                    String cell = at(grid.get(r), e.getKey());
                    if (cell.isEmpty() || "-".equals(cell)) continue;
                    BigDecimal[] pair = byGroup.computeIfAbsent(e.getValue(), k -> new BigDecimal[2]);
                    Mode mode = modeOf.get(e.getKey());
                    Matcher p = PAIR.matcher(cell);
                    if (mode == null && p.matches()) {
                        pair[0] = "-".equals(p.group(1)) ? null : new BigDecimal(p.group(1));
                        pair[1] = "-".equals(p.group(2)) ? null : new BigDecimal(p.group(2));
                        continue;
                    }
                    BigDecimal v = number(cell);
                    if (v == null) {
                        errors.add(new Issue(r + 1, "%s, %s: '%s' is not a rate.".formatted(name, e.getValue().label(), cell)));
                        continue;
                    }
                    pair[mode == Mode.AIR ? 1 : 0] = v;
                }
                if (cargo.stream().anyMatch(c -> c.origin() == origin)) {
                    errors.add(new Issue(r + 1, name + " is listed twice."));
                    continue;
                }
                for (Map.Entry<DestinationGroup, BigDecimal[]> e : byGroup.entrySet()) {
                    if (e.getValue()[0] != null || e.getValue()[1] != null) {
                        cargo.add(new CargoRate(origin, origin.label(), e.getKey(), e.getKey().label(), e.getValue()[0], e.getValue()[1]));
                    }
                }
            }
            if (cargo.isEmpty()) warnings.add(new Issue(cargoHead + 1, "The cargo (DESTINATION) table has no rates."));
            else if (min == null) warnings.add(new Issue(null, "No \"Minimum Chargeable weight\" line: cargo is charged on the actual chargeable weight."));
        }

        if (airHead < 0 && cargoHead < 0) {
            errors.add(new Issue(null, "Neither an AIR COURIER MODE table nor a DESTINATION (cargo) table was found. Start from the template."));
        }
        return new Parsed(air, cargo, cargo.isEmpty() ? null : min, errors, warnings);
    }

    private static int find(List<List<String>> grid, String key) {
        for (int r = 0; r < grid.size(); r++) {
            for (String v : grid.get(r)) {
                if (norm(v).equals(key) || (key.length() > 11 && norm(v).contains(key))) return r;
            }
        }
        return -1;
    }

    private static int column(List<String> row, String key) {
        for (int c = 0; c < row.size(); c++) {
            if (norm(row.get(c)).contains(key)) return c;
        }
        return -1;
    }

    private static String first(List<String> row) {
        return row.isEmpty() ? "" : row.get(0).trim();
    }

    private static String at(List<String> row, int c) {
        return c < row.size() ? row.get(c).trim() : "";
    }

    private static BigDecimal number(String cell) {
        Matcher m = NUMBER.matcher(cell.replace(",", ""));
        return m.matches() ? new BigDecimal(m.group(1)) : null;
    }

    private static DestinationGroup airGroup(String name) {
        return switch (norm(name)) {
            case "WEST" -> DestinationGroup.WEST;
            case "METRO", "METROS" -> DestinationGroup.METROS;
            case "RESTOFINDIA", "ROI" -> DestinationGroup.REST_OF_INDIA;
            case "EASTNORTHEAST", "EASTANDNORTHEAST", "EASTNE" -> DestinationGroup.EAST_NORTH_EAST;
            default -> null;
        };
    }

    private static OriginRegion originRegion(String name) {
        return switch (norm(name)) {
            case "NORTH" -> OriginRegion.NORTH;
            case "SOUTH" -> OriginRegion.SOUTH;
            case "EAST" -> OriginRegion.EAST;
            case "WEST" -> OriginRegion.WEST;
            case "NORTHEAST", "NE" -> OriginRegion.NORTH_EAST;
            default -> null;
        };
    }

    /** Upper case, letters and digits only: "East-North East" → "EASTNORTHEAST". */
    static String norm(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    // ------------------------------------------------------------------ template

    /** The template: the printed rate card's layout with OCCPL's standard rates filled in, ready to edit. */
    @Transactional(readOnly = true)
    @PreAuthorize(COMPANY_ADMIN)
    public byte[] template() {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("Rate Structure");
            CellStyle title = wb.createCellStyle();
            Font tf = wb.createFont();
            tf.setBold(true);
            tf.setUnderline(Font.U_SINGLE);
            tf.setFontHeightInPoints((short) 13);
            title.setFont(tf);
            CellStyle head = boxed(wb, true);
            head.setFillForegroundColor(IndexedColors.ROSE.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle cell = boxed(wb, false);
            CellStyle bold = boxed(wb, true);
            int[] widths = {26, 16, 14, 14, 14, 14};
            for (int i = 0; i < widths.length; i++) s.setColumnWidth(i, widths[i] * 256);

            int r = 0;
            put(s, r, 0, "DOMESTIC AIR COURIER SERVICES (Documents/Small Parcels)", title);
            r = 2;
            put(s, r, 0, "AIR COURIER MODE", head);
            for (int c = 1; c < 4; c++) put(s, r, c, "", head);
            s.addMergedRegion(new CellRangeAddress(r, r, 0, 3));
            put(s, r, 4, "PER KG", head);
            Object[][] air = {{"WEST", 65}, {"METROS", 95}, {"REST OF INDIA", 100}, {"EAST-NORTH EAST", 130}};
            for (Object[] a : air) {
                r++;
                put(s, r, 0, (String) a[0], cell);
                for (int c = 1; c < 4; c++) put(s, r, c, "", cell);
                s.addMergedRegion(new CellRangeAddress(r, r, 0, 3));
                num(s, r, 4, (Integer) a[1], cell);
            }
            r += 3;
            put(s, r, 0, "DOMESTIC CARGO SERVICES Surface and Air Mode", title);
            r += 2;
            int top = r;
            put(s, top, 0, "DESTINATION", head);
            put(s, top + 1, 0, "", head);
            s.addMergedRegion(new CellRangeAddress(top, top + 1, 0, 0));
            put(s, top, 1, "Within State", head);
            put(s, top, 2, "Rest of India", head);
            put(s, top, 3, "", head);
            s.addMergedRegion(new CellRangeAddress(top, top, 2, 3));
            put(s, top, 4, "North East", head);
            put(s, top, 5, "", head);
            s.addMergedRegion(new CellRangeAddress(top, top, 4, 5));
            String[] sub = {"By Surface", "By Surface", "By Air", "By Surface", "By Air"};
            for (int c = 0; c < sub.length; c++) put(s, top + 1, c + 1, sub[c], head);
            Object[][] cargo = {{"North", 20, 25, 100, 30, 110}, {"South", 20, 25, 100, 30, 125}, {"West", 20, 25, 100, 35, 125},
                    {"East", 20, 25, 100, 35, 110}, {"North East", 20, 30, 125, null, null}};
            r = top + 1;
            for (Object[] row : cargo) {
                r++;
                put(s, r, 0, (String) row[0], bold);
                for (int c = 1; c < row.length; c++) {
                    if (row[c] == null) put(s, r, c, "-", cell);
                    else num(s, r, c, (Integer) row[c], cell);
                }
            }
            r += 2;
            put(s, r, 0, "* Minimum Chargeable weight 20kgs", null);
            r += 2;
            put(s, r, 0, "Rows of the cargo table are where the shipment starts; columns are where it goes. "
                    + "A cell may also hold surface/air as \"25/100\". Use - for no rate.", null);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the rate structure template", e);
        }
    }

    private static CellStyle boxed(Workbook wb, boolean boldText) {
        CellStyle st = wb.createCellStyle();
        st.setBorderTop(BorderStyle.THIN);
        st.setBorderBottom(BorderStyle.THIN);
        st.setBorderLeft(BorderStyle.THIN);
        st.setBorderRight(BorderStyle.THIN);
        st.setAlignment(HorizontalAlignment.CENTER);
        if (boldText) {
            Font f = wb.createFont();
            f.setBold(true);
            st.setFont(f);
        }
        return st;
    }

    private static void put(Sheet s, int r, int c, String v, CellStyle st) {
        Row row = s.getRow(r) == null ? s.createRow(r) : s.getRow(r);
        Cell cell = row.createCell(c);
        cell.setCellValue(v);
        if (st != null) cell.setCellStyle(st);
    }

    private static void num(Sheet s, int r, int c, int v, CellStyle st) {
        Row row = s.getRow(r) == null ? s.createRow(r) : s.getRow(r);
        Cell cell = row.createCell(c);
        cell.setCellValue(v);
        cell.setCellStyle(st);
    }

    // ------------------------------------------------------------------ helpers

    private static UUID requireCompany() {
        return CompanyContext.getCompanyId().orElseThrow(() -> new BusinessRuleException(
                "No company is bound to this request. Customer rates belong to a company."));
    }

    private static <T> T tryGet(java.util.function.Supplier<T> s) {
        try {
            return s.get();
        } catch (ResourceNotFoundException e) {
            return null;
        }
    }
}
