package com.courier.modules.tariff.application;

import com.courier.modules.customer.application.CustomerPortalLookup;
import com.courier.modules.customer.domain.Customer;
import com.courier.modules.customerpricing.application.CustomerRateSourceService;
import com.courier.modules.tariff.application.TariffRateCardService.ChargeInput;
import com.courier.modules.tariff.application.TariffRateImportService.Options;
import com.courier.modules.tariff.application.TariffRateImportService.Plan;
import com.courier.modules.tariff.application.TariffRateImportService.Result;
import com.courier.modules.tariff.application.TariffRateImportService.SheetRow;
import com.courier.modules.tariff.domain.TariffChargeRule;
import com.courier.modules.tariff.domain.TariffChargeRuleRepository;
import com.courier.modules.tariff.domain.TariffRateCard;
import com.courier.modules.tariff.domain.TariffRateCardRepository;
import com.courier.modules.tariff.domain.TariffRateSlabRepository;
import com.courier.modules.tariff.domain.TariffStatus;
import com.courier.modules.tariff.domain.TariffZone;
import com.courier.modules.tariff.domain.TariffZoneRepository;
import com.courier.modules.tariff.domain.TariffZoneStatus;
import com.courier.modules.tariff.domain.TotalRounding;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.audit.domain.AuditAction;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import com.courier.shared.security.Roles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A business customer's own zone-tariff rate card, uploaded from their row in the old software's rate sheet layout
 * (Sr, Courier, From zone, To zone, State, City, Mode, Product, Applicable From, Weight From, Weight To, Rate, Rate Type).
 *
 * <ul>
 *   <li>The from zone is the one chosen, else the sheet's (the from zone most of its rows name).</li>
 *   <li>The customer has one card per from zone. The first upload creates it ({@code CUST-…}, effective from the sheet's
 *       Applicable From, else today), copying GST, rounding, volumetric divisor and the charge rules (fuel, docket…) of the
 *       company's active card for that from zone, so the customer keeps paying the same extras. Later uploads replace the
 *       lanes in the file and keep the others.</li>
 *   <li>"Addition 500GM" adds its rate per extra 0.5 kg or per extra 1 kg, as chosen; "Per Kg" per extra kg; "Per Box"
 *       is rate × boxes.</li>
 *   <li>Strict: a lane whose Fixed / Per Kg look swapped is refused, row by row, so the sheet is fixed first.</li>
 *   <li>Import is all or nothing. It activates the card and switches the customer to business rates.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerTariffImportService {

    private static final String WRITE = "hasRole('" + Roles.COMPANY_ADMIN + "')";
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final List<DateTimeFormatter> DATES = List.of(
            DateTimeFormatter.ofPattern("dd-MM-yyyy"), DateTimeFormatter.ofPattern("d-M-yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"), DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    static final BigDecimal HALF_KG = new BigDecimal("0.5");

    private final TariffRateImportService importer;
    private final TariffRateCardService rateCardService;
    private final TariffRateCardRepository cards;
    private final TariffChargeRuleRepository charges;
    private final TariffRateSlabRepository slabs;
    private final TariffZoneRepository zones;
    private final CustomerPortalLookup customers;
    private final CustomerRateSourceService rateSource;
    private final AuditService auditService;

    /** The upload's choices. {@code fromZoneId} null: the sheet's; {@code additionStepKg} 0.5 or 1 (null = 0.5). */
    public record UploadOptions(UUID fromZoneId, BigDecimal additionStepKg) {
    }

    /**
     * What the upload does or did. {@code rateCardId} null on a preview before the first upload; {@code newCard}: the
     * import creates (created) the card; {@code chargesFrom}: the company card whose charges a new card copies.
     */
    public record UploadResult(UUID customerId, String customerName, UUID rateCardId, boolean newCard, UUID fromZoneId,
                               BigDecimal additionStepKg, String chargesFrom, Result sheet) {
    }

    /** One of the customer's cards, for their row and the upload screen. */
    public record CustomerCard(UUID id, String code, String name, UUID fromZoneId, String fromZoneCode, LocalDate effectiveFrom,
                               LocalDate effectiveTo, TariffStatus status, long laneCount) {
    }

    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public List<CustomerCard> cardsOf(UUID customerId) {
        UUID companyId = requireCompany();
        Map<UUID, String> zoneCodes = new HashMap<>();
        return cards.findByCustomer(companyId, customerId, null, null).stream().map(c -> new CustomerCard(c.getId(), c.getCode(),
                c.getName(), c.getFromZoneId(), zoneCodes.computeIfAbsent(c.getFromZoneId(),
                id -> zones.findByIdWithinCompany(id, companyId).map(TariffZone::getCode).orElse(null)),
                c.getEffectiveFrom(), c.getEffectiveTo(), c.getStatus(), slabs.findByCard(companyId, c.getId()).stream()
                        .map(TariffRateCardServiceImpl::laneKey).distinct().count()))
                .toList();
    }

    @Transactional(readOnly = true)
    @PreAuthorize(WRITE)
    public UploadResult preview(UUID customerId, UploadOptions options, MultipartFile file) {
        Prepared p = prepare(customerId, options, file);
        return p.result(p.plan.result(false));
    }

    @Transactional
    @PreAuthorize(WRITE)
    public UploadResult importFile(UUID customerId, UploadOptions options, MultipartFile file) {
        Prepared p = prepare(customerId, options, file);
        if (!p.plan.errors.isEmpty()) {
            throw new BusinessRuleException("The sheet has %d problem(s); nothing was imported. Check it to see them, fix the sheet, then upload it again."
                    .formatted(p.plan.errors.size()));
        }
        if (p.plan.lanes.isEmpty()) {
            throw new BusinessRuleException("No rates from zone %s were found in the sheet.".formatted(p.fromZone.getCode()));
        }
        if (p.newCard) {
            TariffRateCard saved = cards.save(p.card);
            p.card = saved;
            p.plan.card = saved;
            if (p.chargesFrom != null) {
                copyCharges(p.companyId, p.chargesFrom, saved.getId());
            }
            log.info("Rate card {} created for business customer {} by upload", saved.getCode(), customerId);
            auditService.record(AuditAction.TARIFF_RATE_CARD_CREATED, "TariffRateCard", saved.getId(),
                    Map.of("code", saved.getCode(), "customerId", customerId.toString()));
        }
        importer.save(p.plan, file.getOriginalFilename());
        if (!p.card.isActive()) {
            rateCardService.activate(p.card.getId());
        }
        rateSource.set(customerId, true);
        return p.result(p.plan.result(true));
    }

    // ------------------------------------------------------------------ helpers

    private final class Prepared {
        UUID companyId;
        Customer customer;
        TariffZone fromZone;
        TariffRateCard card;
        boolean newCard;
        TariffRateCard chargesFrom;
        BigDecimal step;
        Plan plan;

        UploadResult result(Result sheet) {
            UUID cardId = newCard && !sheet.imported() ? null : card.getId();
            return new UploadResult(customer.getId(), customer.displayName(), cardId, newCard, fromZone.getId(), step,
                    chargesFrom == null ? null : chargesFrom.getCode(), sheet);
        }
    }

    private Prepared prepare(UUID customerId, UploadOptions options, MultipartFile file) {
        Prepared p = new Prepared();
        p.companyId = requireCompany();
        p.customer = customers.findActiveBusinessCustomer(p.companyId, customerId)
                .orElseThrow(() -> new BusinessRuleException("Rates can be uploaded only for an active business customer."));
        p.step = options == null || options.additionStepKg() == null ? HALF_KG : options.additionStepKg();
        if (p.step.compareTo(HALF_KG) != 0 && p.step.compareTo(BigDecimal.ONE) != 0) {
            throw new BusinessRuleException("\"Addition 500GM\" adds per 0.5 kg or per 1 kg.");
        }
        List<SheetRow> rows = importer.read(file);
        p.fromZone = fromZone(p.companyId, options == null ? null : options.fromZoneId(), rows);

        Optional<TariffRateCard> existing = cards.findByCustomer(p.companyId, customerId, p.fromZone.getId(), null).stream()
                .max(Comparator.comparing((TariffRateCard c) -> c.isActive()).thenComparing(TariffRateCard::getEffectiveFrom));
        if (existing.isPresent()) {
            p.card = existing.get();
        } else {
            p.newCard = true;
            LocalDate start = rows.stream().map(SheetRow::applicableFrom).map(CustomerTariffImportService::date)
                    .filter(java.util.Objects::nonNull).findFirst().orElse(LocalDate.now(INDIA));
            p.chargesFrom = cards.findByFromZone(p.companyId, p.fromZone.getId(), TariffStatus.ACTIVE).stream()
                    .filter(c -> c.covers(LocalDate.now(INDIA)))
                    .max(Comparator.comparing(TariffRateCard::getEffectiveFrom)).orElse(null);
            p.card = TariffRateCard.builder()
                    .customerId(customerId)
                    .code(code(p.companyId, p.customer, p.fromZone))
                    .name(truncate(p.customer.displayName() + " rates", 100))
                    .fromZoneId(p.fromZone.getId())
                    .effectiveFrom(start)
                    .gstPercent(p.chargesFrom == null ? new BigDecimal("18.00") : p.chargesFrom.getGstPercent())
                    .volumetricDivisor(p.chargesFrom == null ? null : p.chargesFrom.getVolumetricDivisor())
                    .totalRounding(p.chargesFrom == null ? TotalRounding.NEAREST_RUPEE : p.chargesFrom.getTotalRounding())
                    .description("Business customer's own rates, uploaded from Excel")
                    .status(TariffStatus.INACTIVE)
                    .build();
            p.card.applyInvariants();
        }
        p.plan = importer.plan(p.card, p.companyId, new Options(p.step, true), rows);
        return p;
    }

    /** The chosen from zone, else the one most rows name. */
    private TariffZone fromZone(UUID companyId, UUID chosen, List<SheetRow> rows) {
        if (chosen != null) {
            TariffZone z = zones.findByIdWithinCompany(chosen, companyId)
                    .orElseThrow(() -> new ResourceNotFoundException("TariffZone", chosen));
            if (!z.getZoneUsage().coversFrom()) {
                throw new BusinessRuleException("Zone " + z.getCode() + " is a to zone only; choose a from zone.");
            }
            return z;
        }
        Map<String, TariffZone> fromZones = new HashMap<>();
        zones.search(companyId, null, TariffZoneStatus.ACTIVE, null).stream()
                .filter(z -> z.getZoneUsage().coversFrom())
                .forEach(z -> { fromZones.putIfAbsent(TariffRateImportService.key(z.getCode()), z);
                    fromZones.putIfAbsent(TariffRateImportService.key(z.getName()), z); });
        Map<String, Long> named = rows.stream().map(r -> TariffRateImportService.key(r.fromZone())).filter(k -> !k.isEmpty())
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        String top = named.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                .orElseThrow(() -> new BusinessRuleException("The sheet names no From zone. Choose the from zone."));
        TariffZone z = TariffRateImportService.zone(fromZones, top);
        if (z == null) {
            throw new BusinessRuleException("No active from zone matches \"%s\" from the sheet. Create it or choose the from zone.".formatted(top));
        }
        return z;
    }

    /** CUST-&lt;customer code&gt;-&lt;from zone&gt;, at most 30 characters, made unique with a number. */
    private String code(UUID companyId, Customer customer, TariffZone from) {
        String who = clean(customer.getCustomerCode() != null ? customer.getCustomerCode() : customer.displayName());
        String base = truncate("CUST-" + truncate(who, 16) + "-" + truncate(clean(from.getCode()), 8), 27);
        String code = base;
        for (int n = 2; cards.existsByCode(companyId, code, null); n++) {
            code = base + "-" + n;
        }
        return code;
    }

    private void copyCharges(UUID companyId, TariffRateCard from, UUID toCardId) {
        for (TariffChargeRule r : charges.findByCard(companyId, from.getId())) {
            rateCardService.addCharge(toCardId, new ChargeInput(r.getChargeCode(), r.getChargeName(), r.getToZoneId(),
                    r.getServiceTypeId(), r.getPackageTypeId(), r.getAppliesWhen(), r.getCalcMethod(), r.getChargeValue(),
                    r.getMinAmount(), r.getMaxAmount(), r.isTaxable(), r.getDisplayOrder(), r.getStatus()));
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max).replaceAll("-+$", "");
    }

    private static LocalDate date(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (DateTimeFormatter f : DATES) {
            try {
                return LocalDate.parse(text.trim(), f);
            } catch (RuntimeException ignored) {
                // next format
            }
        }
        return null;
    }

    private static UUID requireCompany() {
        return CompanyContext.getCompanyId().orElseThrow(() -> new BusinessRuleException(
                "No company is bound to this request. Rate cards belong to a company."));
    }
}
