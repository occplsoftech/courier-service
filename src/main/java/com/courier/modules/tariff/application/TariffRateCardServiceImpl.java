package com.courier.modules.tariff.application;

import com.courier.modules.master.application.PackageTypeService;
import com.courier.modules.master.application.ServiceTypeService;
import com.courier.modules.tariff.domain.TariffChargeRule;
import com.courier.modules.tariff.domain.TariffChargeRuleRepository;
import com.courier.modules.tariff.domain.TariffRateCard;
import com.courier.modules.tariff.domain.TariffRateCardRepository;
import com.courier.modules.tariff.domain.TariffRateSlab;
import com.courier.modules.tariff.domain.TariffRateSlabRepository;
import com.courier.modules.tariff.domain.TariffStatus;
import com.courier.modules.tariff.domain.TariffZone;
import com.courier.modules.tariff.domain.TariffZoneRepository;
import com.courier.modules.tariff.domain.TotalRounding;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.audit.domain.AuditAction;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.DuplicateResourceException;
import com.courier.shared.exception.ResourceNotFoundException;
import com.courier.shared.security.Roles;
import com.courier.shared.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Rate card use cases. Every reference (zones, service and package types) is checked before anything is written. */
@Slf4j
@Service
@RequiredArgsConstructor
public class TariffRateCardServiceImpl implements TariffRateCardService {

    private static final String ENTITY = "TariffRateCard";
    private static final String WRITE = "hasRole('" + Roles.COMPANY_ADMIN + "')";
    private static final String READ = "isAuthenticated()";

    private final TariffRateCardRepository cards;
    private final TariffRateSlabRepository slabs;
    private final TariffChargeRuleRepository charges;
    private final TariffZoneRepository zones;
    private final ServiceTypeService serviceTypeService;
    private final PackageTypeService packageTypeService;
    private final AuditService auditService;

    // ------------------------------------------------------------------ cards

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public RateCardDetail create(CardInput input) {
        UUID companyId = requireCompany();
        TariffRateCard card = TariffRateCard.builder().status(TariffStatus.INACTIVE).build();
        apply(companyId, card, input);
        requireCodeAvailable(companyId, card.getCode(), null);
        TariffRateCard saved = cards.save(card);
        log.info("Tariff rate card {} ({}) created in company {} by {}", saved.getCode(), saved.getId(), companyId, actor());
        auditService.record(AuditAction.TARIFF_RATE_CARD_CREATED, ENTITY, saved.getId(),
                Map.of("code", saved.getCode(), "fromZoneId", saved.getFromZoneId().toString()));
        return detail(companyId, saved);
    }

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public RateCardDetail update(UUID cardId, CardInput input, Long expectedVersion) {
        UUID companyId = requireCompany();
        TariffRateCard card = load(cardId, companyId);
        if (expectedVersion != null && !Objects.equals(card.getVersion(), expectedVersion)) {
            throw new ObjectOptimisticLockingFailureException(TariffRateCard.class, card.getId());
        }
        apply(companyId, card, input);
        requireCodeAvailable(companyId, card.getCode(), card.getId());
        if (card.isActive()) {
            requireNoOverlap(companyId, card);
        }
        TariffRateCard saved = cards.save(card);
        auditService.record(AuditAction.TARIFF_RATE_CARD_UPDATED, ENTITY, saved.getId(), Map.of("code", saved.getCode()));
        return detail(companyId, saved);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READ)
    public RateCardDetail getById(UUID cardId) {
        UUID companyId = requireCompany();
        return detail(companyId, load(cardId, companyId));
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READ)
    public List<RateCardSummary> search(UUID fromZoneId, TariffStatus status, String search) {
        UUID companyId = requireCompany();
        String pattern = search == null || search.isBlank() ? null : "%" + search.trim().toLowerCase() + "%";
        List<TariffRateCard> found = cards.search(companyId, fromZoneId, status, pattern);
        Map<UUID, String> zoneCodes = zones.findAllWithinCompany(
                        found.stream().map(TariffRateCard::getFromZoneId).collect(Collectors.toSet()), companyId)
                .stream().collect(Collectors.toMap(TariffZone::getId, TariffZone::getCode));
        return found.stream().map(c -> {
            Set<String> lanes = slabs.findByCard(companyId, c.getId()).stream().map(TariffRateCardServiceImpl::laneKey)
                    .collect(Collectors.toSet());
            return new RateCardSummary(c, zoneCodes.get(c.getFromZoneId()), lanes.size(),
                    charges.findByCard(companyId, c.getId()).size());
        }).toList();
    }

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public RateCardDetail activate(UUID cardId) {
        UUID companyId = requireCompany();
        TariffRateCard card = load(cardId, companyId);
        if (slabs.findByCard(companyId, card.getId()).isEmpty()) {
            throw new BusinessRuleException("Add at least one rate before activating rate card " + card.getCode() + ".");
        }
        requireNoOverlap(companyId, card);
        card.activate();
        TariffRateCard saved = cards.save(card);
        // Audit after save: recording switches the company binding, which would otherwise flush this dirty card under
        // the wrong company and trip the isolation guard.
        auditService.record(AuditAction.TARIFF_RATE_CARD_ACTIVATED, ENTITY, saved.getId(), Map.of("code", saved.getCode()));
        return detail(companyId, saved);
    }

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public RateCardDetail deactivate(UUID cardId) {
        UUID companyId = requireCompany();
        TariffRateCard card = load(cardId, companyId);
        card.deactivate();
        TariffRateCard saved = cards.save(card);
        auditService.record(AuditAction.TARIFF_RATE_CARD_DEACTIVATED, ENTITY, saved.getId(), Map.of("code", saved.getCode()));
        return detail(companyId, saved);
    }

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public void delete(UUID cardId) {
        UUID companyId = requireCompany();
        TariffRateCard card = load(cardId, companyId);
        UUID actor = SecurityUtils.getCurrentUserId().orElse(null);
        slabs.findByCard(companyId, card.getId()).forEach(s -> {
            s.softDelete(actor);
            slabs.save(s);
        });
        charges.findByCard(companyId, card.getId()).forEach(r -> {
            r.softDelete(actor);
            charges.save(r);
        });
        card.softDelete(actor);
        cards.save(card);
        log.warn("Tariff rate card {} ({}) deleted in company {} by {}", card.getCode(), card.getId(), companyId, actor());
        auditService.record(AuditAction.TARIFF_RATE_CARD_DELETED, ENTITY, card.getId(), Map.of("code", card.getCode()));
    }

    // ------------------------------------------------------------------ lanes

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public RateCardDetail saveLane(UUID cardId, LaneInput lane) {
        UUID companyId = requireCompany();
        TariffRateCard card = load(cardId, companyId);
        if (lane == null) {
            throw new BusinessRuleException("Lane details are required.");
        }
        TariffZone toZone = requireToZone(companyId, lane.toZoneId());
        requireServiceType(lane.serviceTypeId());
        requirePackageType(lane.packageTypeId());
        List<TariffRateSlab> fresh = (lane.slabs() == null ? List.<SlabInput>of() : lane.slabs()).stream()
                .map(s -> TariffRateSlab.builder()
                        .rateCardId(card.getId()).toZoneId(toZone.getId())
                        .serviceTypeId(lane.serviceTypeId()).packageTypeId(lane.packageTypeId())
                        .weightFromKg(s.weightFromKg()).weightToKg(s.weightToKg())
                        .rateType(s.rateType()).rate(s.rate()).stepKg(s.stepKg())
                        .build())
                .toList();
        List<TariffRateSlab> sorted = TariffRateSlab.validateLane(fresh);

        UUID actor = SecurityUtils.getCurrentUserId().orElse(null);
        slabs.findLane(companyId, card.getId(), toZone.getId(), lane.serviceTypeId(), lane.packageTypeId()).forEach(old -> {
            old.softDelete(actor);
            slabs.save(old);
        });
        sorted.forEach(slabs::save);
        auditService.record(AuditAction.TARIFF_RATE_LANE_SAVED, ENTITY, card.getId(), Map.of("code", card.getCode(),
                "toZone", toZone.getCode(), "serviceTypeId", lane.serviceTypeId().toString(),
                "packageTypeId", lane.packageTypeId().toString(), "slabs", String.valueOf(sorted.size())));
        return detail(companyId, card);
    }

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public RateCardDetail deleteLane(UUID cardId, UUID toZoneId, UUID serviceTypeId, UUID packageTypeId) {
        UUID companyId = requireCompany();
        TariffRateCard card = load(cardId, companyId);
        List<TariffRateSlab> lane = slabs.findLane(companyId, card.getId(), toZoneId, serviceTypeId, packageTypeId);
        if (lane.isEmpty()) {
            throw new ResourceNotFoundException("TariffRateLane", toZoneId);
        }
        UUID actor = SecurityUtils.getCurrentUserId().orElse(null);
        lane.forEach(s -> {
            s.softDelete(actor);
            slabs.save(s);
        });
        auditService.record(AuditAction.TARIFF_RATE_LANE_DELETED, ENTITY, card.getId(), Map.of("code", card.getCode(),
                "toZoneId", toZoneId.toString(), "serviceTypeId", serviceTypeId.toString(), "packageTypeId", packageTypeId.toString()));
        return detail(companyId, card);
    }

    // ---------------------------------------------------------------- charges

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public RateCardDetail addCharge(UUID cardId, ChargeInput input) {
        UUID companyId = requireCompany();
        TariffRateCard card = load(cardId, companyId);
        TariffChargeRule rule = TariffChargeRule.builder().rateCardId(card.getId()).build();
        applyCharge(companyId, card, rule, input);
        TariffChargeRule saved = charges.save(rule);
        auditService.record(AuditAction.TARIFF_CHARGE_SAVED, ENTITY, card.getId(),
                Map.of("code", card.getCode(), "charge", saved.getChargeCode(), "chargeId", saved.getId().toString()));
        return detail(companyId, card);
    }

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public RateCardDetail updateCharge(UUID cardId, UUID chargeId, ChargeInput input) {
        UUID companyId = requireCompany();
        TariffRateCard card = load(cardId, companyId);
        TariffChargeRule rule = loadCharge(companyId, card, chargeId);
        applyCharge(companyId, card, rule, input);
        TariffChargeRule saved = charges.save(rule);
        auditService.record(AuditAction.TARIFF_CHARGE_SAVED, ENTITY, card.getId(),
                Map.of("code", card.getCode(), "charge", saved.getChargeCode(), "chargeId", saved.getId().toString()));
        return detail(companyId, card);
    }

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public RateCardDetail deleteCharge(UUID cardId, UUID chargeId) {
        UUID companyId = requireCompany();
        TariffRateCard card = load(cardId, companyId);
        TariffChargeRule rule = loadCharge(companyId, card, chargeId);
        rule.softDelete(SecurityUtils.getCurrentUserId().orElse(null));
        charges.save(rule);
        auditService.record(AuditAction.TARIFF_CHARGE_DELETED, ENTITY, card.getId(),
                Map.of("code", card.getCode(), "charge", rule.getChargeCode(), "chargeId", rule.getId().toString()));
        return detail(companyId, card);
    }

    // ---------------------------------------------------------------- helpers

    private void apply(UUID companyId, TariffRateCard card, CardInput input) {
        if (input == null) {
            throw new BusinessRuleException("Rate card details are required.");
        }
        if (input.fromZoneId() != null) {
            TariffZone from = zones.findByIdWithinCompany(input.fromZoneId(), companyId)
                    .orElseThrow(() -> new BusinessRuleException("No such zone: " + input.fromZoneId()));
            if (!from.getZoneUsage().coversFrom()) {
                throw new BusinessRuleException("Zone " + from.getCode() + " is a to zone only; a rate card needs a from zone.");
            }
        }
        card.setCode(input.code());
        card.setName(input.name());
        card.setFromZoneId(input.fromZoneId());
        card.setEffectiveFrom(input.effectiveFrom());
        card.setEffectiveTo(input.effectiveTo());
        card.setGstPercent(input.gstPercent() == null ? new BigDecimal("18.00") : input.gstPercent());
        card.setVolumetricDivisor(input.volumetricDivisor());
        card.setTotalRounding(input.totalRounding() == null ? TotalRounding.NEAREST_RUPEE : input.totalRounding());
        card.setDescription(input.description());
        card.applyInvariants();
    }

    private void applyCharge(UUID companyId, TariffRateCard card, TariffChargeRule rule, ChargeInput input) {
        if (input == null) {
            throw new BusinessRuleException("Charge details are required.");
        }
        if (input.toZoneId() != null) {
            requireToZone(companyId, input.toZoneId());
        }
        if (input.serviceTypeId() != null) {
            requireServiceType(input.serviceTypeId());
        }
        if (input.packageTypeId() != null) {
            requirePackageType(input.packageTypeId());
        }
        rule.setChargeCode(input.code());
        rule.setChargeName(input.name());
        rule.setToZoneId(input.toZoneId());
        rule.setServiceTypeId(input.serviceTypeId());
        rule.setPackageTypeId(input.packageTypeId());
        rule.setAppliesWhen(input.appliesWhen());
        rule.setCalcMethod(input.calcMethod());
        rule.setChargeValue(input.value());
        rule.setMinAmount(input.minAmount());
        rule.setMaxAmount(input.maxAmount());
        rule.setTaxable(input.taxable() == null || input.taxable());
        rule.setDisplayOrder(input.displayOrder() == null ? 0 : input.displayOrder());
        rule.setStatus(input.status() == null ? TariffStatus.ACTIVE : input.status());
        rule.applyInvariants();
        for (TariffChargeRule other : charges.findByCard(companyId, card.getId())) {
            if (!other.getId().equals(rule.getId()) && other.sameTarget(rule)) {
                throw new DuplicateResourceException("TariffChargeRule", "charge",
                        rule.getChargeCode() + " with the same zone, service and package filters");
            }
        }
    }

    /** Refuses an active card of the same from zone whose dates meet this card's. */
    private void requireNoOverlap(UUID companyId, TariffRateCard card) {
        for (TariffRateCard other : cards.findByFromZone(companyId, card.getFromZoneId(), TariffStatus.ACTIVE)) {
            if (!other.getId().equals(card.getId()) && other.overlaps(card)) {
                throw new BusinessRuleException("Rate card %s is already active for this from zone from %s%s. Change the dates or deactivate it first."
                        .formatted(other.getCode(), other.getEffectiveFrom(),
                                other.getEffectiveTo() == null ? " with no end date" : " to " + other.getEffectiveTo()));
            }
        }
    }

    private TariffZone requireToZone(UUID companyId, UUID zoneId) {
        if (zoneId == null) {
            throw new BusinessRuleException("Choose the to zone.");
        }
        TariffZone zone = zones.findByIdWithinCompany(zoneId, companyId)
                .orElseThrow(() -> new BusinessRuleException("No such zone: " + zoneId));
        if (!zone.getZoneUsage().coversTo()) {
            throw new BusinessRuleException("Zone " + zone.getCode() + " is a from zone only; rates are set for to zones.");
        }
        return zone;
    }

    private String requireServiceType(UUID id) {
        if (id == null) {
            throw new BusinessRuleException("Choose the service type (mode).");
        }
        return lookup(id, x -> serviceTypeService.getById(x).getCode(), "service type");
    }

    private String requirePackageType(UUID id) {
        if (id == null) {
            throw new BusinessRuleException("Choose the package type (product).");
        }
        return lookup(id, x -> packageTypeService.getById(x).getCode(), "package type");
    }

    private RateCardDetail detail(UUID companyId, TariffRateCard card) {
        cards.flush();
        List<TariffRateSlab> cardSlabs = slabs.findByCard(companyId, card.getId());
        List<TariffChargeRule> cardCharges = charges.findByCard(companyId, card.getId());
        Set<UUID> zoneIds = new HashSet<>();
        Set<UUID> serviceIds = new HashSet<>();
        Set<UUID> packageIds = new HashSet<>();
        zoneIds.add(card.getFromZoneId());
        cardSlabs.forEach(s -> {
            zoneIds.add(s.getToZoneId());
            serviceIds.add(s.getServiceTypeId());
            packageIds.add(s.getPackageTypeId());
        });
        cardCharges.forEach(r -> {
            if (r.getToZoneId() != null) zoneIds.add(r.getToZoneId());
            if (r.getServiceTypeId() != null) serviceIds.add(r.getServiceTypeId());
            if (r.getPackageTypeId() != null) packageIds.add(r.getPackageTypeId());
        });
        Map<UUID, String> labels = new HashMap<>();
        zones.findAllWithinCompany(zoneIds, companyId).forEach(z -> labels.put(z.getId(), z.getCode()));
        serviceIds.forEach(id -> labels.put(id, tryLabel(id, x -> serviceTypeService.getById(x).getCode())));
        packageIds.forEach(id -> labels.put(id, tryLabel(id, x -> packageTypeService.getById(x).getCode())));
        labels.values().removeIf(Objects::isNull);
        return new RateCardDetail(card, cardSlabs, cardCharges, labels);
    }

    static String laneKey(TariffRateSlab s) {
        return s.getToZoneId() + ":" + s.getServiceTypeId() + ":" + s.getPackageTypeId();
    }

    private static String tryLabel(UUID id, Function<UUID, String> fn) {
        try {
            return fn.apply(id);
        } catch (ResourceNotFoundException e) {
            return null;
        }
    }

    private static <T> T lookup(UUID id, Function<UUID, T> fn, String label) {
        try {
            return fn.apply(id);
        } catch (ResourceNotFoundException e) {
            throw new BusinessRuleException("No such " + label + ": " + id);
        }
    }

    private TariffRateCard load(UUID id, UUID companyId) {
        return cards.findByIdWithinCompany(id, companyId).orElseThrow(() -> new ResourceNotFoundException(ENTITY, id));
    }

    private TariffChargeRule loadCharge(UUID companyId, TariffRateCard card, UUID chargeId) {
        return charges.findByIdWithinCompany(chargeId, companyId)
                .filter(r -> r.getRateCardId().equals(card.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("TariffChargeRule", chargeId));
    }

    private void requireCodeAvailable(UUID companyId, String code, UUID excludeId) {
        if (cards.existsByCode(companyId, code, excludeId)) {
            throw new DuplicateResourceException(ENTITY, "code", code);
        }
    }

    private UUID requireCompany() {
        return CompanyContext.getCompanyId().orElseThrow(() -> new BusinessRuleException(
                "No company is bound to this request. Rate cards belong to a company."));
    }

    private String actor() {
        return SecurityUtils.getCurrentUserId().map(UUID::toString).orElse("system");
    }
}
