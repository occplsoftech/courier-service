package com.courier.modules.customerpricing.application;

import com.courier.modules.customer.application.CustomerService;
import com.courier.modules.customerpricing.domain.PricingZone;
import com.courier.modules.customerpricing.domain.PricingZoneRepository;
import com.courier.modules.customerpricing.domain.ZoneRate;
import com.courier.modules.customerpricing.domain.ZoneRateRepository;
import com.courier.modules.customerpricing.domain.ZoneRateSlab;
import com.courier.modules.customerpricing.domain.ZoneRateSlabRepository;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.audit.domain.AuditAction;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-zone rate cards. One rate per zone; {@link #save} replaces header and slabs in one
 * transaction. Slabs are half-open {@code [min, max)}, must not overlap, and only the
 * last may be open-ended.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZoneRateServiceImpl implements ZoneRateService {

    private static final String ENTITY = "CustomerRate";
    private static final String WRITE = "hasRole('" + Roles.COMPANY_ADMIN + "')";
    private static final String READ = "isAuthenticated()";
    private static final int MONEY_SCALE = 2;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final ZoneRateRepository rates;
    private final ZoneRateSlabRepository slabs;
    private final PricingZoneRepository zones;
    private final PricingZoneService zoneService;
    private final CustomerService customerService;
    private final com.courier.modules.master.domain.ServiceTypeRepository serviceTypes;
    private final AuditService auditService;

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public ZoneRateView save(UUID zoneId, UUID serviceTypeId, SaveCommand c) {
        UUID companyId = requireCompany();
        PricingZone zone = loadZone(zoneId, companyId);
        requireServiceType(serviceTypeId, companyId);
        List<SlabInput> inputs = c.slabs() == null ? List.of() : c.slabs();
        if (inputs.isEmpty()) {
            throw new BusinessRuleException("A rate needs at least one per-kg weight slab.");
        }

        Optional<ZoneRate> existing = rates.findByZoneAndServiceType(companyId, zone.getId(), serviceTypeId);
        ZoneRate rate = existing.orElseGet(() -> ZoneRate.builder()
                .zoneId(zone.getId()).customerId(zone.getCustomerId()).serviceTypeId(serviceTypeId).build());
        if (existing.isPresent() && c.expectedVersion() != null
                && !Objects.equals(rate.getVersion(), c.expectedVersion())) {
            throw new ObjectOptimisticLockingFailureException(ZoneRate.class, rate.getId());
        }
        rate.setMinChargeableWeight(orZero(c.minChargeableWeight()));
        rate.setDocketCharge(orZero(c.docketCharge()));
        rate.setMathadiCharge(orZero(c.mathadiCharge()));
        rate.setPackCharge(orZero(c.packCharge()));
        rate.setOdaCharge(orZero(c.odaCharge()));
        rate.setFuelSurchargePercent(orZero(c.fuelSurchargePercent()));
        rate.setFovPercent(orZero(c.fovPercent()));
        rate.setEffectiveFrom(c.effectiveFrom());
        rate.setEffectiveTo(c.effectiveTo());
        rate.applyInvariants();

        List<ZoneRateSlab> newSlabs = inputs.stream()
                .map(i -> ZoneRateSlab.builder().minWeight(i.minWeight()).maxWeight(i.maxWeight())
                        .ratePerKg(i.ratePerKg()).build())
                .sorted(Comparator.comparing(s -> s.getMinWeight() == null ? BigDecimal.ZERO : s.getMinWeight()))
                .toList();
        newSlabs.forEach(ZoneRateSlab::applyInvariants);
        requireValidSlabs(newSlabs);

        ZoneRate saved = rates.save(rate);
        UUID actor = SecurityUtils.getCurrentUserId().orElse(null);
        slabs.findByRate(companyId, saved.getId()).forEach(s -> {
            s.softDelete(actor);
            slabs.save(s);
        });
        for (ZoneRateSlab slab : newSlabs) {
            slab.setRateId(saved.getId());
            slabs.save(slab);
        }
        log.info("Customer rate for zone {} saved in company {} by {} ({} slab(s))",
                zone.getId(), companyId, actor(), newSlabs.size());
        auditService.record(AuditAction.CUSTOMER_RATE_SAVED, ENTITY, saved.getId(),
                Map.of("zoneId", zone.getId().toString(), "slabs", newSlabs.size(),
                        "serviceType", serviceTypeId == null ? "ALL" : serviceTypeId.toString()));
        return view(companyId, zone, saved);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READ)
    public Optional<ZoneRateView> getByZone(UUID zoneId, UUID serviceTypeId) {
        UUID companyId = requireCompany();
        PricingZone zone = loadZone(zoneId, companyId);
        return rates.findByZoneAndServiceType(companyId, zone.getId(), serviceTypeId).map(r -> view(companyId, zone, r));
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READ)
    public List<ZoneRateView> cardsOfZone(UUID zoneId) {
        UUID companyId = requireCompany();
        PricingZone zone = loadZone(zoneId, companyId);
        return rates.findAllByZone(companyId, zone.getId()).stream().map(r -> view(companyId, zone, r)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READ)
    public List<ZoneRateView> search(UUID customerId) {
        UUID companyId = requireCompany();
        return zones.search(companyId, customerId, null, null).stream()
                .flatMap(z -> rates.findAllByZone(companyId, z.getId()).stream().map(r -> view(companyId, z, r)))
                .toList();
    }

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public ZoneRateView activate(UUID zoneId, UUID serviceTypeId) {
        UUID companyId = requireCompany();
        PricingZone zone = loadZone(zoneId, companyId);
        ZoneRate rate = loadRate(zone, serviceTypeId, companyId);
        rate.activate();
        ZoneRate saved = rates.save(rate);
        // Audit after save — see PricingZoneServiceImpl.activate for why the order matters.
        auditService.record(AuditAction.CUSTOMER_RATE_ACTIVATED, ENTITY, saved.getId(),
                Map.of("zoneId", zone.getId().toString()));
        return view(companyId, zone, saved);
    }

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public ZoneRateView deactivate(UUID zoneId, UUID serviceTypeId) {
        UUID companyId = requireCompany();
        PricingZone zone = loadZone(zoneId, companyId);
        ZoneRate rate = loadRate(zone, serviceTypeId, companyId);
        rate.deactivate();
        ZoneRate saved = rates.save(rate);
        // Audit after save — see PricingZoneServiceImpl.activate for why the order matters.
        auditService.record(AuditAction.CUSTOMER_RATE_DEACTIVATED, ENTITY, saved.getId(),
                Map.of("zoneId", zone.getId().toString()));
        return view(companyId, zone, saved);
    }

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public void delete(UUID zoneId, UUID serviceTypeId) {
        UUID companyId = requireCompany();
        PricingZone zone = loadZone(zoneId, companyId);
        ZoneRate rate = loadRate(zone, serviceTypeId, companyId);
        UUID actor = SecurityUtils.getCurrentUserId().orElse(null);
        slabs.findByRate(companyId, rate.getId()).forEach(s -> {
            s.softDelete(actor);
            slabs.save(s);
        });
        rate.softDelete(actor);
        rates.save(rate);
        log.warn("Customer rate of zone {} deleted in company {} by {}", zone.getId(), companyId, actor());
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READ)
    public Quote calculate(QuoteCommand q) {
        UUID companyId = requireCompany();
        requireWeight(q);
        PricingZone zone = resolveZone(q);
        LocalDate date = q.bookingDate() == null ? LocalDate.now() : q.bookingDate();
        // The service type's own card, else the zone's "all services" card.
        ZoneRate rate = rates.findByZoneAndServiceType(companyId, zone.getId(), q.serviceTypeId())
                .filter(r -> r.isActive() && r.coversDate(date))
                .or(() -> q.serviceTypeId() == null ? Optional.empty()
                        : rates.findByZoneAndServiceType(companyId, zone.getId(), null).filter(r -> r.isActive() && r.coversDate(date)))
                .orElseThrow(() -> new BusinessRuleException(
                        "Zone '%s' has no active rate effective on %s.".formatted(zone.getZoneName(), date)));
        return price(companyId, zone, rate, q, true);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READ)
    public List<ServiceQuote> calculateEach(QuoteCommand q) {
        UUID companyId = requireCompany();
        requireWeight(q);
        PricingZone zone;
        try {
            zone = resolveZone(q);
        } catch (BusinessRuleException noZone) {
            return List.of();
        }
        LocalDate date = q.bookingDate() == null ? LocalDate.now() : q.bookingDate();
        List<ServiceQuote> out = new java.util.ArrayList<>();
        for (ZoneRate rate : rates.findAllByZone(companyId, zone.getId())) {
            if (!rate.isActive() || !rate.coversDate(date)) {
                continue;
            }
            Quote quote = price(companyId, zone, rate, q, false);
            if (quote == null) {
                continue; // no slab covers this weight on that card
            }
            var type = rate.getServiceTypeId() == null ? java.util.Optional.<com.courier.modules.master.domain.ServiceType>empty()
                    : serviceTypes.findByIdWithinCompany(rate.getServiceTypeId(), companyId);
            out.add(new ServiceQuote(rate.getServiceTypeId(), type.map(com.courier.modules.master.domain.ServiceType::getCode).orElse(null),
                    type.map(com.courier.modules.master.domain.ServiceType::getName).orElse(null), quote));
        }
        return out;
    }

    private static void requireWeight(QuoteCommand q) {
        if (q.actualWeight() == null || q.actualWeight().signum() <= 0) {
            throw new BusinessRuleException("Actual weight must be greater than zero.");
        }
    }

    private PricingZone resolveZone(QuoteCommand q) {
        return zoneService.resolve(q.customerId(), q.districtId(), q.cityId())
                .orElseThrow(() -> new BusinessRuleException("No active pricing zone of this customer covers that destination."))
                .zone();
    }

    /** The price from one card; when no slab covers the weight, throws if {@code strict}, else null. */
    private Quote price(UUID companyId, PricingZone zone, ZoneRate rate, QuoteCommand q, boolean strict) {
        BigDecimal chargeable = q.actualWeight().max(rate.getMinChargeableWeight());
        ZoneRateSlab slab = slabs.findByRate(companyId, rate.getId()).stream()
                .filter(s -> s.covers(chargeable))
                .findFirst()
                .orElse(null);
        if (slab == null) {
            if (strict) {
                throw new BusinessRuleException("No weight slab of zone '%s' covers %s kg.".formatted(zone.getZoneName(),
                        chargeable.stripTrailingZeros().toPlainString()));
            }
            return null;
        }
        BigDecimal freight = money(chargeable.multiply(slab.getRatePerKg()));
        BigDecimal oda = q.odaApplicable() ? rate.getOdaCharge() : BigDecimal.ZERO;
        BigDecimal fuel = money(freight.multiply(rate.getFuelSurchargePercent()).divide(HUNDRED, 6, RoundingMode.HALF_UP));
        BigDecimal declared = q.declaredValue() == null ? BigDecimal.ZERO : q.declaredValue();
        BigDecimal fov = money(declared.multiply(rate.getFovPercent()).divide(HUNDRED, 6, RoundingMode.HALF_UP));
        BigDecimal total = freight.add(rate.getDocketCharge()).add(rate.getMathadiCharge())
                .add(rate.getPackCharge()).add(oda).add(fuel).add(fov);
        return new Quote(zone.getId(), zone.getZoneName(), q.actualWeight(), chargeable, slab.getRatePerKg(),
                freight, rate.getDocketCharge(), rate.getMathadiCharge(), rate.getPackCharge(), oda, fuel, fov,
                money(total));
    }

    // ------------------------------------------------------------------ helpers

    private void requireValidSlabs(List<ZoneRateSlab> sorted) {
        for (int i = 0; i < sorted.size(); i++) {
            ZoneRateSlab s = sorted.get(i);
            if (s.getMaxWeight() == null && i < sorted.size() - 1) {
                throw new BusinessRuleException("Only the last weight slab may have no upper limit.");
            }
            if (i > 0) {
                ZoneRateSlab prev = sorted.get(i - 1);
                if (s.getMinWeight().compareTo(prev.getMaxWeight()) < 0) {
                    throw new BusinessRuleException("Weight slabs overlap at %s kg."
                            .formatted(s.getMinWeight().stripTrailingZeros().toPlainString()));
                }
            }
        }
    }

    private ZoneRateView view(UUID companyId, PricingZone zone, ZoneRate rate) {
        // customerService.getById is a company read, but keep the same flush-first rule as
        // PricingZoneServiceImpl.view so a dirty rate is never flushed under another binding.
        rates.flush();
        String customerName = null;
        try {
            customerName = customerService.getById(zone.getCustomerId()).displayName();
        } catch (ResourceNotFoundException ignored) {
            // customer soft-deleted: the rate stays readable, just unnamed
        }
        var type = rate.getServiceTypeId() == null ? java.util.Optional.<com.courier.modules.master.domain.ServiceType>empty()
                : serviceTypes.findByIdWithinCompany(rate.getServiceTypeId(), companyId);
        return new ZoneRateView(rate, zone, customerName, slabs.findByRate(companyId, rate.getId()),
                type.map(com.courier.modules.master.domain.ServiceType::getCode).orElse(null),
                type.map(com.courier.modules.master.domain.ServiceType::getName).orElse(null));
    }

    private PricingZone loadZone(UUID id, UUID companyId) {
        return zones.findByIdWithinCompany(id, companyId)
                .orElseThrow(() -> new ResourceNotFoundException("PricingZone", id));
    }

    private ZoneRate loadRate(PricingZone zone, UUID serviceTypeId, UUID companyId) {
        return rates.findByZoneAndServiceType(companyId, zone.getId(), serviceTypeId)
                .orElseThrow(() -> new ResourceNotFoundException(ENTITY, zone.getId()));
    }

    private void requireServiceType(UUID serviceTypeId, UUID companyId) {
        if (serviceTypeId != null && serviceTypes.findByIdWithinCompany(serviceTypeId, companyId).filter(t -> t.isActive()).isEmpty()) {
            throw new BusinessRuleException("That service type does not exist or is not active.");
        }
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private UUID requireCompany() {
        return CompanyContext.getCompanyId().orElseThrow(() -> new BusinessRuleException(
                "No company is bound to this request. Customer rates belong to a company."));
    }

    private String actor() {
        return SecurityUtils.getCurrentUserId().map(UUID::toString).orElse("system");
    }
}
