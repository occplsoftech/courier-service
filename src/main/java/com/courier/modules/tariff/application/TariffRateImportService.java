package com.courier.modules.tariff.application;

import com.courier.modules.master.application.PackageTypeService;
import com.courier.modules.master.application.ServiceTypeService;
import com.courier.modules.master.domain.MasterDataCriteria;
import com.courier.modules.master.domain.MasterDataEntity;
import com.courier.modules.master.domain.MasterStatus;
import com.courier.modules.tariff.application.TariffRateCardService.LaneInput;
import com.courier.modules.tariff.application.TariffRateCardService.SlabInput;
import com.courier.modules.tariff.domain.SlabRateType;
import com.courier.modules.tariff.domain.TariffRateCard;
import com.courier.modules.tariff.domain.TariffRateCardRepository;
import com.courier.modules.tariff.domain.TariffRateSlab;
import com.courier.modules.tariff.domain.TariffRateSlabRepository;
import com.courier.modules.tariff.domain.TariffZone;
import com.courier.modules.tariff.domain.TariffZoneRepository;
import com.courier.modules.tariff.domain.TariffZoneStatus;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import com.courier.shared.security.Roles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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
import java.util.stream.Collectors;

/**
 * Loads a rate sheet in OCCPL Courier's cash-booking format (Sr, Courier, From zone, To zone, State, City, Mode, Product,
 * Applicable From, Weight From, Weight To, Rate, Rate Type) into one rate card.
 *
 * <ul>
 *   <li>The header row is found among the first rows (the sheet starts with an "Admin" title row).</li>
 *   <li>Names are matched loosely: case, spaces and punctuation are ignored, against codes and names. "EX PUNE" finds
 *       zone EXPUNE; "BY AIR" finds a service type coded AIR or named "By Air"; "Document" finds DOC.</li>
 *   <li>A row with a State or City (the sheet's "SOUTH, but Kerala" rows) goes to the to zone named after that city or
 *       state, which must exist.</li>
 *   <li>Rows for another from zone than the card's are skipped with a warning. Identical repeated rows count once; two
 *       different rates for the same weight band are an error.</li>
 *   <li>Rate Type "Fixed" is FIXED; "Per Kg" and "Addition 500GM" are PER_EXTRA_KG with a 1 kg step (each extra kg, as the
 *       company chose for every service type), with a warning on the 500 g rows so the step can be changed per lane.</li>
 * </ul>
 *
 * <p>Preview writes nothing. Import is all or nothing: any error refuses the whole file; otherwise every lane in the
 * file replaces that lane on the card, in one transaction. Lanes not in the file are left as they are.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TariffRateImportService {

    private static final String WRITE = "hasRole('" + Roles.COMPANY_ADMIN + "')";
    private static final int HEADER_SEARCH_ROWS = 15;
    private static final List<DateTimeFormatter> DATES = List.of(
            DateTimeFormatter.ofPattern("dd-MM-yyyy"), DateTimeFormatter.ofPattern("d-M-yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"), DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"), DateTimeFormatter.ofPattern("M/d/yy"));

    private final TariffRateCardRepository cards;
    private final TariffRateSlabRepository slabs;
    private final TariffZoneRepository zones;
    private final ServiceTypeService serviceTypeService;
    private final PackageTypeService packageTypeService;
    private final TariffRateCardService rateCardService;

    /** One problem or note, tied to a sheet row when there is one (row numbers as Excel shows them). */
    public record Issue(Integer row, String message) {
    }

    /** What one lane of the file becomes. {@code replaces}: the card already has rates for it. */
    public record LanePreview(UUID toZoneId, String toZoneCode, UUID serviceTypeId, String serviceTypeCode,
                              UUID packageTypeId, String packageTypeCode, int slabCount, String summary, boolean replaces) {
    }

    public record Result(String rateCardCode, String fromZoneCode, int rowsRead, int duplicateRows, int skippedRows,
                         List<LanePreview> lanes, List<Issue> errors, List<Issue> warnings, boolean imported) {
    }

    /**
     * How a sheet is read. {@code additionStepKg}: the weight one "Addition 500GM" rate buys (0.5 or 1 kg); "Per Kg" is
     * always 1 kg. {@code strict}: also refuse lanes whose rate types look swapped (a business customer's upload).
     */
    record Options(BigDecimal additionStepKg, boolean strict) {
        static final Options COMPANY = new Options(BigDecimal.ONE, false);
    }

    @Transactional(readOnly = true)
    @PreAuthorize(WRITE)
    public Result preview(UUID cardId, MultipartFile file) {
        return plan(cardId, file).result(false);
    }

    /** Saves every lane of a plan with no errors onto its (saved) card. */
    void save(Plan plan, String fileName) {
        for (PlannedLane lane : plan.lanes.values()) {
            rateCardService.saveLane(plan.card.getId(), new LaneInput(lane.toZone.getId(), lane.serviceType.getId(), lane.packageType.getId(),
                    lane.slabs.stream().map(s -> new SlabInput(s.getWeightFromKg(), s.getWeightToKg(), s.getRateType(), s.getRate(),
                            s.getStepKg())).toList()));
        }
        log.info("Imported {} lane(s) from '{}' into rate card {}", plan.lanes.size(), fileName, plan.card.getCode());
    }

    @Transactional
    @PreAuthorize(WRITE)
    public Result importFile(UUID cardId, MultipartFile file) {
        Plan plan = plan(cardId, file);
        if (!plan.errors.isEmpty()) {
            throw new BusinessRuleException("The sheet has %d problem(s); nothing was imported. Preview it to see them, fix the sheet or the zones, then import again."
                    .formatted(plan.errors.size()));
        }
        if (plan.lanes.isEmpty()) {
            throw new BusinessRuleException("No rates for rate card " + plan.card.getCode() + " were found in the sheet.");
        }
        save(plan, file.getOriginalFilename());
        return plan.result(true);
    }

    // ------------------------------------------------------------------ planning

    record SheetRow(int row, String fromZone, String toZone, String state, String city, String mode, String product,
                            String applicableFrom, BigDecimal weightFrom, BigDecimal weightTo, BigDecimal rate, String rateType) {
    }

    static final class PlannedLane {
        final TariffZone toZone;
        final MasterDataEntity serviceType;
        final MasterDataEntity packageType;
        final Map<String, TariffRateSlab> byBand = new LinkedHashMap<>();
        final Map<String, Integer> bandRow = new HashMap<>();
        List<TariffRateSlab> slabs = List.of();
        boolean replaces;

        PlannedLane(TariffZone toZone, MasterDataEntity serviceType, MasterDataEntity packageType) {
            this.toZone = toZone;
            this.serviceType = serviceType;
            this.packageType = packageType;
        }
    }

    static final class Plan {
        TariffRateCard card;
        TariffZone fromZone;
        int rowsRead;
        int duplicates;
        int skipped;
        final Map<String, PlannedLane> lanes = new LinkedHashMap<>();
        final List<Issue> errors = new ArrayList<>();
        final List<Issue> warnings = new ArrayList<>();

        Result result(boolean imported) {
            List<LanePreview> previews = lanes.values().stream().map(l -> new LanePreview(l.toZone.getId(), l.toZone.getCode(),
                    l.serviceType.getId(), l.serviceType.getCode(), l.packageType.getId(), l.packageType.getCode(),
                    l.slabs.size(), summary(l.slabs), l.replaces)).toList();
            return new Result(card.getCode(), fromZone == null ? null : fromZone.getCode(), rowsRead, duplicates, skipped,
                    previews, List.copyOf(errors), List.copyOf(warnings), imported);
        }
    }

    private Plan plan(UUID cardId, MultipartFile file) {
        UUID companyId = CompanyContext.getCompanyId().orElseThrow(() -> new BusinessRuleException(
                "No company is bound to this request. Rate cards belong to a company."));
        TariffRateCard card = cards.findByIdWithinCompany(cardId, companyId)
                .orElseThrow(() -> new ResourceNotFoundException("TariffRateCard", cardId));
        return plan(card, companyId, Options.COMPANY, read(file));
    }

    /**
     * Plans the rows onto {@code card}, which may not be saved yet (a business customer's first upload: no lane then
     * "replaces" anything). Writes nothing.
     */
    Plan plan(TariffRateCard card, UUID companyId, Options options, List<SheetRow> rows) {
        Plan plan = new Plan();
        plan.card = card;
        plan.fromZone = zones.findByIdWithinCompany(plan.card.getFromZoneId(), companyId).orElse(null);
        String cardFromKey = plan.fromZone == null ? "" : key(plan.fromZone.getCode());
        String cardFromNameKey = plan.fromZone == null ? "" : key(plan.fromZone.getName());

        plan.rowsRead = rows.size();

        Map<String, TariffZone> toZones = new HashMap<>();
        zones.search(companyId, null, TariffZoneStatus.ACTIVE, null).stream()
                .filter(z -> z.getZoneUsage().coversTo())
                .forEach(z -> { toZones.putIfAbsent(key(z.getCode()), z); toZones.putIfAbsent(key(z.getName()), z); });
        Map<String, MasterDataEntity> services = lookup(serviceTypeService.search(
                MasterDataCriteria.of(null, Set.of(MasterStatus.ACTIVE), null), PageRequest.of(0, 500)).getContent());
        Map<String, MasterDataEntity> packages = lookup(packageTypeService.search(
                MasterDataCriteria.of(null, Set.of(MasterStatus.ACTIVE), null), PageRequest.of(0, 500)).getContent());

        Set<String> otherFromZones = new LinkedHashSet<>();
        Set<String> dates = new LinkedHashSet<>();
        Set<Integer> halfKgRows = new LinkedHashSet<>();
        for (SheetRow r : rows) {
            String fromKey = key(r.fromZone());
            if (!fromKey.equals(cardFromKey) && !fromKey.equals(cardFromNameKey)) {
                otherFromZones.add(r.fromZone());
                plan.skipped++;
                continue;
            }
            if (r.applicableFrom() != null && !r.applicableFrom().isBlank()) {
                dates.add(r.applicableFrom().trim());
            }
            String place = !blank(r.city()) ? r.city() : r.state();
            TariffZone toZone = !blank(place) ? zone(toZones, place) : zone(toZones, r.toZone());
            if (toZone == null) {
                plan.errors.add(new Issue(r.row(), blank(place)
                        ? "No active to zone matches \"%s\". Create it (code or name %s) or correct the sheet.".formatted(r.toZone(), r.toZone())
                        : "\"%s\" rows with %s \"%s\" need their own to zone: create a to zone named %s.".formatted(r.toZone(),
                        !blank(r.city()) ? "city" : "state", place, place)));
                continue;
            }
            ModeMatch mode = matchMode(services, packages, r.mode());
            MasterDataEntity service = mode == null ? null : mode.service();
            if (service == null) {
                plan.errors.add(new Issue(r.row(), "No service type matches mode \"%s\". Add it under Masters → Service Types.".formatted(r.mode())));
                continue;
            }
            MasterDataEntity pkg = mode.packageType() != null ? mode.packageType() : match(packages, r.product());
            if (pkg == null) {
                plan.errors.add(new Issue(r.row(), "No package type matches product \"%s\". Add it under Masters → Package Types.".formatted(r.product())));
                continue;
            }
            SlabRateType type = rateType(r.rateType());
            if (type == null) {
                plan.errors.add(new Issue(r.row(), "Rate type \"%s\" is not Fixed, Per Kg, Addition or Per Box.".formatted(r.rateType())));
                continue;
            }
            boolean addition = type == SlabRateType.PER_EXTRA_KG && isAddition(r.rateType());
            if (addition) {
                halfKgRows.add(r.row());
            }
            if (r.weightFrom() == null || r.weightTo() == null || r.rate() == null) {
                plan.errors.add(new Issue(r.row(), "Weight from, weight to and rate must be numbers."));
                continue;
            }

            PlannedLane lane = plan.lanes.computeIfAbsent(toZone.getId() + ":" + service.getId() + ":" + pkg.getId(),
                    k -> new PlannedLane(toZone, service, pkg));
            String band = r.weightFrom().stripTrailingZeros().toPlainString() + "-" + r.weightTo().stripTrailingZeros().toPlainString();
            TariffRateSlab slab = TariffRateSlab.builder().rateCardId(plan.card.getId()).toZoneId(toZone.getId())
                    .serviceTypeId(service.getId()).packageTypeId(pkg.getId()).weightFromKg(r.weightFrom()).weightToKg(r.weightTo())
                    .rateType(type).rate(r.rate())
                    .stepKg(type != SlabRateType.PER_EXTRA_KG ? null : addition ? options.additionStepKg() : BigDecimal.ONE).build();
            TariffRateSlab seen = lane.byBand.get(band);
            if (seen == null) {
                lane.byBand.put(band, slab);
                lane.bandRow.put(band, r.row());
            } else if (seen.getRateType() == slab.getRateType() && seen.getRate().compareTo(slab.getRate()) == 0) {
                plan.duplicates++;
            } else {
                plan.errors.add(new Issue(r.row(), "%s · %s · %s, %s kg: rate %s %s here but %s %s on row %d.".formatted(toZone.getCode(),
                        service.getCode(), pkg.getCode(), band, plain(slab.getRate()), slab.getRateType(), plain(seen.getRate()),
                        seen.getRateType(), lane.bandRow.get(band))));
            }
        }

        for (PlannedLane lane : plan.lanes.values()) {
            String name = lane.toZone.getCode() + " · " + lane.serviceType.getCode() + " · " + lane.packageType.getCode();
            if (options.strict()) {
                List<Issue> swapped = swappedRateTypes(lane, name);
                if (!swapped.isEmpty()) {
                    plan.errors.addAll(swapped);
                    lane.slabs = new ArrayList<>(lane.byBand.values());
                    continue;
                }
            }
            try {
                lane.slabs = TariffRateSlab.validateLane(new ArrayList<>(lane.byBand.values()));
            } catch (BusinessRuleException invalid) {
                plan.errors.add(new Issue(null, name + ": " + invalid.getMessage()));
                lane.slabs = new ArrayList<>(lane.byBand.values());
            }
            lane.replaces = plan.card.getId() != null && !slabs.findLane(companyId, plan.card.getId(), lane.toZone.getId(),
                    lane.serviceType.getId(), lane.packageType.getId()).isEmpty();
        }

        if (!otherFromZones.isEmpty()) {
            plan.warnings.add(new Issue(null, "%d row(s) for other from zones were skipped (%s). This card is for %s."
                    .formatted(plan.skipped, String.join(", ", otherFromZones), plan.fromZone == null ? "?" : plan.fromZone.getCode())));
        }
        if (plan.duplicates > 0) {
            plan.warnings.add(new Issue(null, plan.duplicates + " repeated row(s) were counted once."));
        }
        if (!halfKgRows.isEmpty() && options.additionStepKg().compareTo(BigDecimal.ONE) == 0) {
            plan.warnings.add(new Issue(null, "%d \"Addition 500GM\" row(s) were read as a rate per extra kg. Change the step to 0.5 kg on those lanes if the rate is per 500 g."
                    .formatted(halfKgRows.size())));
        } else if (!halfKgRows.isEmpty()) {
            plan.warnings.add(new Issue(null, "%d \"Addition 500GM\" row(s) add their rate for each extra %s kg or part of it."
                    .formatted(halfKgRows.size(), plain(options.additionStepKg()))));
        }
        for (String d : dates) {
            LocalDate parsed = date(d);
            if (parsed != null && plan.card.getEffectiveFrom() != null && !parsed.equals(plan.card.getEffectiveFrom())) {
                plan.warnings.add(new Issue(null, "The sheet says applicable from %s; the card starts %s.".formatted(parsed, plan.card.getEffectiveFrom())));
            }
        }
        return plan;
    }

    // ------------------------------------------------------------------- reading

    List<SheetRow> read(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessRuleException("Choose an Excel file to import.");
        }
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        try (InputStream in = file.getInputStream(); Workbook workbook = WorkbookFactory.create(in)) {
            Sheet sheet = workbook.getSheetAt(0);
            int headerRow = -1;
            Map<String, Integer> columns = Map.of();
            for (int r = sheet.getFirstRowNum(); r <= Math.min(sheet.getLastRowNum(), sheet.getFirstRowNum() + HEADER_SEARCH_ROWS); r++) {
                Map<String, Integer> found = header(sheet.getRow(r), formatter);
                if (found.containsKey("TOZONE") && found.containsKey("RATE")) {
                    headerRow = r;
                    columns = found;
                    break;
                }
            }
            if (headerRow < 0) {
                throw new BusinessRuleException("No header row found. The sheet needs columns From zone, To zone, Mode, Product, Weight From, Weight To, Rate and Rate Type.");
            }
            Map<String, Integer> cols = columns;
            List<String> missing = List.of("FROMZONE", "TOZONE", "MODE", "PRODUCT", "WEIGHTFROM", "WEIGHTTO", "RATE", "RATETYPE")
                    .stream().filter(c -> !cols.containsKey(c)).toList();
            if (!missing.isEmpty()) {
                throw new BusinessRuleException("The sheet is missing column(s): " + String.join(", ", missing) + ".");
            }
            List<SheetRow> rows = new ArrayList<>();
            for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String to = text(row, cols.get("TOZONE"), formatter);
                String rate = text(row, cols.get("RATE"), formatter);
                if (blank(to) && blank(rate)) {
                    continue;
                }
                rows.add(new SheetRow(r + 1, text(row, cols.get("FROMZONE"), formatter), to,
                        text(row, cols.get("STATE"), formatter), text(row, cols.get("CITY"), formatter),
                        text(row, cols.get("MODE"), formatter), text(row, cols.get("PRODUCT"), formatter),
                        text(row, cols.get("APPLICABLEFROM"), formatter),
                        number(text(row, cols.get("WEIGHTFROM"), formatter)), number(text(row, cols.get("WEIGHTTO"), formatter)),
                        number(rate), text(row, cols.get("RATETYPE"), formatter)));
            }
            if (rows.isEmpty()) {
                throw new BusinessRuleException("The sheet has a header but no rate rows.");
            }
            return rows;
        } catch (IOException | RuntimeException e) {
            if (e instanceof BusinessRuleException b) {
                throw b;
            }
            throw new BusinessRuleException("Could not read the Excel file: " + e.getMessage());
        }
    }

    private static Map<String, Integer> header(Row row, DataFormatter formatter) {
        Map<String, Integer> columns = new HashMap<>();
        if (row == null) {
            return columns;
        }
        for (Cell cell : row) {
            String k = key(formatter.formatCellValue(cell));
            String purpose = switch (k) {
                case "FROMZONE" -> "FROMZONE";
                case "TOZONE" -> "TOZONE";
                case "STATE" -> "STATE";
                case "CITY" -> "CITY";
                case "MODE" -> "MODE";
                case "PRODUCT" -> "PRODUCT";
                case "APPLICABLEFROM" -> "APPLICABLEFROM";
                case "WEIGHTFROM" -> "WEIGHTFROM";
                case "WEIGHTTO" -> "WEIGHTTO";
                case "RATE" -> "RATE";
                case "RATETYPE" -> "RATETYPE";
                default -> null;
            };
            if (purpose != null) {
                columns.putIfAbsent(purpose, cell.getColumnIndex());
            }
        }
        return columns;
    }

    private static String text(Row row, Integer col, DataFormatter formatter) {
        if (col == null) {
            return null;
        }
        Cell cell = row.getCell(col);
        return cell == null ? null : formatter.formatCellValue(cell).trim();
    }

    // ------------------------------------------------------------------ matching

    /**
     * A zone by code or name, read loosely ({@link #key}); "EX" and "X" in front are the same ("EXPUNE" finds zone X_PUNE
     * "X - Pune", and "X MAHARASHTRA" finds EXMAHARASHTRA).
     */
    static <Z> Z zone(Map<String, Z> zonesByKey, String text) {
        String k = key(text);
        if (k.isEmpty()) {
            return null;
        }
        Z hit = zonesByKey.get(k);
        if (hit == null && k.startsWith("EX") && k.length() > 2) {
            hit = zonesByKey.get(k.substring(1));
        }
        if (hit == null && k.startsWith("X") && k.length() > 1) {
            hit = zonesByKey.get("E" + k);
        }
        return hit;
    }

    /** Uppercase letters and digits only: "Ex Pune" and "EX-PUNE" are both EXPUNE. */
    static String key(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static Map<String, MasterDataEntity> lookup(List<? extends MasterDataEntity> rows) {
        Map<String, MasterDataEntity> map = new HashMap<>();
        for (MasterDataEntity e : rows) {
            map.putIfAbsent(key(e.getCode()), e);
            map.putIfAbsent(key(e.getName()), e);
        }
        return map;
    }

    /** By code or name; then without a leading "BY" ("BY AIR" → AIR); then the first code the text starts with ("Document" → DOC). */
    static MasterDataEntity match(Map<String, MasterDataEntity> map, String text) {
        String k = key(text);
        if (k.isEmpty()) {
            return null;
        }
        MasterDataEntity hit = map.get(k);
        if (hit == null && k.startsWith("BY") && k.length() > 2) {
            hit = map.get(k.substring(2));
        }
        if (hit == null) {
            hit = map.entrySet().stream().filter(e -> e.getKey().length() >= 3 && k.startsWith(e.getKey()))
                    .max((a, b) -> Integer.compare(a.getKey().length(), b.getKey().length()))
                    .map(Map.Entry::getValue).orElse(null);
        }
        return hit;
    }

    static SlabRateType rateType(String text) {
        String k = key(text);
        if (k.startsWith("FIX")) {
            return SlabRateType.FIXED;
        }
        if (k.contains("BOX")) {
            return SlabRateType.PER_BOX;
        }
        if (k.contains("KG") || k.startsWith("ADD") || k.contains("GM") || k.contains("EXTRA")) {
            return SlabRateType.PER_EXTRA_KG;
        }
        return null;
    }

    /** "Addition 500GM" / "Addtion 500GM": adds per step of extra weight, as opposed to "Per Kg". */
    static boolean isAddition(String text) {
        String k = key(text);
        return k.startsWith("ADD") || k.contains("500") || k.contains("GM");
    }

    /** A mode's service type and, when the mode names one too ("LAPTOP-SURFACE"), its package type. */
    record ModeMatch(MasterDataEntity service, MasterDataEntity packageType) {
    }

    /**
     * Matches a sheet's mode to a service type. Tried in order: a word naming a package type plus words naming a service
     * ("LAPTOP-SURFACE" = SURFACE for LAPTOP); the same words in any order ("Cargo SURFACE" = SURFACE CARGO); the code or
     * name as {@link #match} reads it ("BY AIR" = AIR); finally a service whose name holds every word ("Cargo" = SURFACE
     * CARGO), preferring the surface one when several do.
     */
    static ModeMatch matchMode(Map<String, MasterDataEntity> services, Map<String, MasterDataEntity> packages, String mode) {
        List<String> words = words(mode);
        if (words.isEmpty()) {
            return null;
        }
        if (words.size() > 1) {
            for (String w : words) {
                MasterDataEntity pkg = packages.get(w);
                List<String> rest = words.stream().filter(x -> !x.equals(w)).toList();
                MasterDataEntity service = rest.isEmpty() ? null : byWords(services, rest);
                if (pkg != null && service != null) {
                    return new ModeMatch(service, pkg);
                }
            }
        }
        MasterDataEntity service = byWords(services, words);
        if (service == null) {
            service = match(services, mode);
        }
        if (service == null) {
            Set<String> wanted = Set.copyOf(words);
            List<MasterDataEntity> holding = services.values().stream().distinct()
                    .filter(e -> Set.copyOf(words(e.getName())).containsAll(wanted) || Set.copyOf(words(e.getCode())).containsAll(wanted))
                    .toList();
            service = holding.size() == 1 ? holding.get(0) : holding.stream()
                    .filter(e -> words(e.getName()).contains("SURFACE") || words(e.getCode()).contains("SURFACE"))
                    .findFirst().orElse(null);
        }
        return service == null ? null : new ModeMatch(service, null);
    }

    private static MasterDataEntity byWords(Map<String, MasterDataEntity> services, List<String> words) {
        Set<String> wanted = Set.copyOf(words);
        return services.values().stream().distinct()
                .filter(e -> Set.copyOf(words(e.getCode())).equals(wanted) || Set.copyOf(words(e.getName())).equals(wanted))
                .findFirst().orElse(null);
    }

    /** "By Air" → [AIR]; "LAPTOP-SURFACE" → [LAPTOP, SURFACE]; "Surface_Cargo" → [SURFACE, CARGO]. */
    static List<String> words(String text) {
        if (text == null) {
            return List.of();
        }
        return java.util.Arrays.stream(text.toUpperCase(Locale.ROOT).split("[^A-Z0-9]+"))
                .filter(w -> !w.isEmpty() && !w.equals("BY")).distinct().toList();
    }

    /**
     * A business customer's lane whose rate types look swapped, one issue per row: the lightest band adding per kg (it
     * must be Fixed or Per Box), or a last band reaching {@value #SWAP_CHECK_KG_TEXT} kg or more that is Fixed and costs no
     * more than the band before it, which would charge the same for any weight up to its limit ("0–0.5 kg ₹45 Fixed", then
     * "0.501–99 kg ₹45 Fixed"). Equal Fixed prices on light bands (≤0.25, ≤0.5, ≤1 kg all 200) are fine.
     */
    static final String SWAP_CHECK_KG_TEXT = "10";
    static final BigDecimal SWAP_CHECK_KG = new BigDecimal(SWAP_CHECK_KG_TEXT);

    static List<Issue> swappedRateTypes(PlannedLane lane, String name) {
        List<Map.Entry<String, TariffRateSlab>> sorted = lane.byBand.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getValue().getWeightFromKg())).toList();
        List<Issue> issues = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            TariffRateSlab slab = sorted.get(i).getValue();
            Integer row = lane.bandRow.get(sorted.get(i).getKey());
            if (i == 0 && slab.getRateType() == SlabRateType.PER_EXTRA_KG) {
                issues.add(new Issue(row, "%s: the first weight band (%s) is Per Kg / Addition at %s; the first band must be Fixed. Are Fixed and Per Kg swapped?"
                        .formatted(name, slab.band(), plain(slab.getRate()))));
            }
            boolean lastHeavyBand = i == sorted.size() - 1 && slab.getWeightToKg().compareTo(SWAP_CHECK_KG) >= 0;
            if (i > 0 && lastHeavyBand && slab.getRateType() == SlabRateType.FIXED) {
                TariffRateSlab prev = sorted.get(i - 1).getValue();
                if (prev.getRate() != null && slab.getRate() != null && slab.getRate().compareTo(prev.getRate()) <= 0) {
                    issues.add(new Issue(row, "%s: Fixed %s for %s is not more than %s for %s, so every weight up to %s kg would cost %s. Did you mean Per Kg?"
                            .formatted(name, plain(slab.getRate()), slab.band(), plain(prev.getRate()), prev.band(),
                                    plain(slab.getWeightToKg()), plain(slab.getRate()))));
                }
            }
        }
        return issues;
    }

    private static BigDecimal number(String text) {
        if (blank(text)) {
            return null;
        }
        try {
            return new BigDecimal(text.replace(",", "").replace("₹", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate date(String text) {
        for (DateTimeFormatter f : DATES) {
            try {
                return LocalDate.parse(text, f);
            } catch (RuntimeException ignored) {
                // try the next format
            }
        }
        return null;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static String summary(List<TariffRateSlab> lane) {
        return lane.stream().map(s -> switch (s.getRateType()) {
            case FIXED -> "≤" + plain(s.getWeightToKg()) + " kg " + plain(s.getRate());
            case PER_BOX -> "≤" + plain(s.getWeightToKg()) + " kg " + plain(s.getRate()) + "/box";
            case PER_EXTRA_KG -> "+" + plain(s.getRate()) + (s.getStepKg() == null || s.getStepKg().compareTo(BigDecimal.ONE) == 0
                    ? "/kg" : "/" + plain(s.getStepKg().multiply(new BigDecimal("1000"))) + " g") + " to " + plain(s.getWeightToKg()) + " kg";
        }).collect(Collectors.joining(" · "));
    }
}
