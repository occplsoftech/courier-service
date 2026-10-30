package com.courier.modules.customerpricing.application;

import com.courier.modules.customer.application.CustomerService;
import com.courier.modules.customer.domain.Customer;
import com.courier.modules.customer.domain.CustomerType;
import com.courier.modules.customerpricing.domain.PricingZone;
import com.courier.modules.customerpricing.domain.PricingZoneRepository;
import com.courier.modules.customerpricing.domain.ZoneLocation;
import com.courier.modules.customerpricing.domain.ZoneLocationRepository;
import com.courier.modules.customerpricing.domain.ZoneRate;
import com.courier.modules.customerpricing.domain.ZoneRateRepository;
import com.courier.modules.customerpricing.domain.ZoneRateSlab;
import com.courier.modules.customerpricing.domain.ZoneRateSlabRepository;
import com.courier.modules.customerpricing.domain.ZoneStatus;
import com.courier.modules.master.application.CityService;
import com.courier.modules.master.application.DistrictService;
import com.courier.modules.master.domain.City;
import com.courier.modules.master.domain.District;
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

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Pricing zone use cases. A zone belongs to one customer; its name is unique among that
 * customer's live zones, and no district/city may sit in two zones of the same customer
 * (a destination must price one way only).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PricingZoneServiceImpl implements PricingZoneService {

    private static final String ENTITY = "PricingZone";
    private static final String WRITE = "hasRole('" + Roles.COMPANY_ADMIN + "')";
    private static final String READ = "isAuthenticated()";

    private final com.courier.modules.customer.application.CustomerPortalLookup customerLookup;
    private final PricingZoneRepository zones;
    private final ZoneLocationRepository locations;
    private final ZoneRateRepository rates;
    private final ZoneRateSlabRepository slabs;
    private final CustomerService customerService;
    private final DistrictService districtService;
    private final CityService cityService;
    private final AuditService auditService;

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public PricingZoneView create(UUID customerId, String zoneName, String description,
                                  List<LocationInput> locationInputs) {
        UUID companyId = requireCompany();
        Customer customer = customerService.getById(customerId);
        if (customer.getCustomerType() != CustomerType.BUSINESS) {
            throw new BusinessRuleException("Pricing zones are for business customers; %s is an individual customer."
                    .formatted(customer.displayName()));
        }

        PricingZone zone = PricingZone.builder()
                .customerId(customer.getId()).zoneName(zoneName).description(description)
                .status(ZoneStatus.ACTIVE).build();
        zone.applyInvariants();
        requireNameAvailable(companyId, customer.getId(), zone.getZoneName(), null);
        PricingZone saved = zones.save(zone);

        List<LocationInput> inputs = locationInputs == null ? List.of() : locationInputs;
        for (LocationInput input : inputs) {
            insertLocation(companyId, saved, input);
        }
        log.info("Pricing zone {} ({}) created for customer {} in company {} by {}",
                saved.getZoneName(), saved.getId(), customer.getCustomerCode(), companyId, actor());
        auditService.record(AuditAction.PRICING_ZONE_CREATED, ENTITY, saved.getId(),
                Map.of("customerId", customer.getId().toString(), "zoneName", saved.getZoneName()));
        return view(companyId, saved);
    }

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public PricingZoneView update(UUID zoneId, String zoneName, String description, Long expectedVersion) {
        UUID companyId = requireCompany();
        PricingZone zone = load(zoneId, companyId);
        requireCurrentVersion(zone, expectedVersion);
        zone.setZoneName(zoneName);
        zone.setDescription(description);
        zone.applyInvariants();
        requireNameAvailable(companyId, zone.getCustomerId(), zone.getZoneName(), zone.getId());
        PricingZone saved = zones.save(zone);
        auditService.record(AuditAction.PRICING_ZONE_UPDATED, ENTITY, saved.getId(),
                Map.of("zoneName", saved.getZoneName()));
        return view(companyId, saved);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READ)
    public PricingZoneView getById(UUID zoneId) {
        UUID companyId = requireCompany();
        return view(companyId, load(zoneId, companyId));
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READ)
    public List<PricingZoneView> search(UUID customerId, ZoneStatus status, String search) {
        UUID companyId = requireCompany();
        String pattern = search == null || search.isBlank() ? null : "%" + search.trim().toLowerCase() + "%";
        List<PricingZone> found = zones.search(companyId, customerId, status, pattern);
        if (found.isEmpty()) {
            return List.of();
        }
        Set<UUID> ids = found.stream().map(PricingZone::getId).collect(Collectors.toSet());
        Map<UUID, List<ZoneLocation>> byZone = locations.findByZones(companyId, ids).stream()
                .collect(Collectors.groupingBy(ZoneLocation::getZoneId));
        Set<UUID> withRate = rates.findByZones(companyId, ids).stream()
                .map(ZoneRate::getZoneId).collect(Collectors.toSet());
        Names names = new Names();
        return found.stream()
                .map(z -> toView(z, byZone.getOrDefault(z.getId(), List.of()), withRate.contains(z.getId()), names))
                .toList();
    }

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public PricingZoneView addLocation(UUID zoneId, LocationInput input) {
        UUID companyId = requireCompany();
        PricingZone zone = load(zoneId, companyId);
        insertLocation(companyId, zone, input);
        return view(companyId, zone);
    }

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public PricingZoneView removeLocation(UUID zoneId, UUID locationId) {
        UUID companyId = requireCompany();
        PricingZone zone = load(zoneId, companyId);
        ZoneLocation location = locations.findByIdWithinCompany(locationId, companyId)
                .filter(l -> l.getZoneId().equals(zone.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("ZoneLocation", locationId));
        location.softDelete(SecurityUtils.getCurrentUserId().orElse(null));
        locations.save(location);
        auditService.record(AuditAction.PRICING_ZONE_LOCATION_REMOVED, ENTITY, zone.getId(),
                Map.of("locationId", locationId.toString()));
        return view(companyId, zone);
    }

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public PricingZoneView activate(UUID zoneId) {
        UUID companyId = requireCompany();
        PricingZone zone = load(zoneId, companyId);
        zone.activate();
        PricingZone saved = zones.save(zone);
        // Audit after save: recording switches the company binding, which would otherwise
        // flush this dirty zone under the wrong company and trip the isolation guard.
        auditService.record(AuditAction.PRICING_ZONE_ACTIVATED, ENTITY, saved.getId(),
                Map.of("zoneName", saved.getZoneName()));
        return view(companyId, saved);
    }

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public PricingZoneView deactivate(UUID zoneId) {
        UUID companyId = requireCompany();
        PricingZone zone = load(zoneId, companyId);
        zone.deactivate();
        PricingZone saved = zones.save(zone);
        // Audit after save: recording switches the company binding, which would otherwise
        // flush this dirty zone under the wrong company and trip the isolation guard.
        auditService.record(AuditAction.PRICING_ZONE_DEACTIVATED, ENTITY, saved.getId(),
                Map.of("zoneName", saved.getZoneName()));
        return view(companyId, saved);
    }

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public void delete(UUID zoneId) {
        UUID companyId = requireCompany();
        PricingZone zone = load(zoneId, companyId);
        UUID actor = SecurityUtils.getCurrentUserId().orElse(null);
        locations.findByZone(companyId, zone.getId()).forEach(l -> {
            l.softDelete(actor);
            locations.save(l);
        });
        rates.findAllByZone(companyId, zone.getId()).forEach(r -> {
            slabs.findByRate(companyId, r.getId()).forEach(s -> {
                s.softDelete(actor);
                slabs.save(s);
            });
            r.softDelete(actor);
            rates.save(r);
        });
        zone.softDelete(actor);
        zones.save(zone);
        log.warn("Pricing zone {} deleted in company {} by {}", zone.getId(), companyId, actor());
        auditService.record(AuditAction.PRICING_ZONE_DELETED, ENTITY, zone.getId(),
                Map.of("zoneName", zone.getZoneName()));
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READ)
    public Optional<PricingZoneView> resolve(UUID customerId, UUID districtId, UUID cityId) {
        UUID companyId = requireCompany();
        if (customerId == null || districtId == null) {
            throw new BusinessRuleException("A customer and a destination district are required.");
        }
        if (!customerLookup.usesBusinessRates(companyId, customerId)) {
            return Optional.empty(); // priced at the company's rates
        }
        List<ZoneLocation> candidates = locations.findByCustomerAndDistrict(companyId, customerId, districtId);
        Optional<ZoneLocation> match = candidates.stream()
                .filter(l -> cityId != null && Objects.equals(l.getCityId(), cityId))
                .findFirst();
        if (match.isEmpty()) {
            match = candidates.stream().filter(l -> l.getCityId() == null).findFirst();
        }
        return match
                .flatMap(l -> zones.findByIdWithinCompany(l.getZoneId(), companyId))
                .filter(PricingZone::isActive)
                .map(z -> view(companyId, z));
    }

    // ------------------------------------------------------------------ helpers

    private void insertLocation(UUID companyId, PricingZone zone, LocationInput input) {
        if (input == null || input.districtId() == null) {
            throw new BusinessRuleException("A zone location needs a district.");
        }
        District district = lookup(input.districtId(), districtService::getById, "district");
        if (input.cityId() != null) {
            City city = lookup(input.cityId(), cityService::getById, "city");
            if (!Objects.equals(city.getDistrictId(), district.getId())) {
                throw new BusinessRuleException(
                        "City %s is not in district %s.".formatted(city.getName(), district.getName()));
            }
        }
        for (ZoneLocation existing : locations.findByCustomerAndDistrict(companyId, zone.getCustomerId(), input.districtId())) {
            if (existing.overlaps(input.districtId(), input.cityId())) {
                boolean sameZone = existing.getZoneId().equals(zone.getId());
                String where = input.cityId() == null ? district.getName() : "that city in " + district.getName();
                throw new DuplicateResourceException(ENTITY + "Location", "location", where
                        + (sameZone ? " is already in this zone" : " is already in another zone of this customer"));
            }
        }
        ZoneLocation saved = locations.save(ZoneLocation.builder()
                .zoneId(zone.getId()).customerId(zone.getCustomerId())
                .districtId(input.districtId()).cityId(input.cityId()).build());
        auditService.record(AuditAction.PRICING_ZONE_LOCATION_ADDED, ENTITY, zone.getId(),
                Map.of("locationId", saved.getId().toString(), "districtId", input.districtId().toString()));
    }

    private PricingZoneView view(UUID companyId, PricingZone zone) {
        // Geography lookups below run under the platform company (global masters); a dirty
        // zone auto-flushed in that window would trip the company-isolation guard. Flush first.
        zones.flush();
        return toView(zone, locations.findByZone(companyId, zone.getId()),
                !rates.findAllByZone(companyId, zone.getId()).isEmpty(), new Names());
    }

    private PricingZoneView toView(PricingZone zone, List<ZoneLocation> zoneLocations, boolean hasRate, Names names) {
        List<PricingZoneView.LocationView> locationViews = zoneLocations.stream()
                .map(l -> new PricingZoneView.LocationView(l.getId(), l.getDistrictId(),
                        names.district(l.getDistrictId()), l.getCityId(),
                        l.getCityId() == null ? null : names.city(l.getCityId())))
                .sorted(java.util.Comparator
                        .comparing(PricingZoneView.LocationView::districtName, java.util.Comparator.nullsLast(String::compareToIgnoreCase))
                        .thenComparing(v -> v.cityName() == null ? "" : v.cityName(), String::compareToIgnoreCase))
                .toList();
        return new PricingZoneView(zone, names.customer(zone.getCustomerId()), locationViews, hasRate);
    }

    /** Per-call memo of id -> display name so a list view does not look the same district up per row. */
    private final class Names {
        private final Map<UUID, String> districts = new HashMap<>();
        private final Map<UUID, String> cities = new HashMap<>();
        private final Map<UUID, String> customers = new HashMap<>();

        String district(UUID id) {
            return districts.computeIfAbsent(id, k -> tryName(() -> districtService.getById(k).getName()));
        }

        String city(UUID id) {
            return cities.computeIfAbsent(id, k -> tryName(() -> cityService.getById(k).getName()));
        }

        String customer(UUID id) {
            return customers.computeIfAbsent(id, k -> tryName(() -> customerService.getById(k).displayName()));
        }

        private String tryName(java.util.function.Supplier<String> supplier) {
            try {
                return supplier.get();
            } catch (ResourceNotFoundException e) {
                return null;
            }
        }
    }

    private <T> T lookup(UUID id, java.util.function.Function<UUID, T> fn, String label) {
        try {
            return fn.apply(id);
        } catch (ResourceNotFoundException e) {
            throw new BusinessRuleException("No such " + label + ": " + id);
        }
    }

    private PricingZone load(UUID id, UUID companyId) {
        return zones.findByIdWithinCompany(id, companyId)
                .orElseThrow(() -> new ResourceNotFoundException(ENTITY, id));
    }

    private void requireNameAvailable(UUID companyId, UUID customerId, String name, UUID excludeId) {
        if (zones.existsByName(companyId, customerId, name, excludeId)) {
            throw new DuplicateResourceException(ENTITY, "zoneName", name);
        }
    }

    private void requireCurrentVersion(PricingZone zone, Long expectedVersion) {
        if (expectedVersion != null && !Objects.equals(zone.getVersion(), expectedVersion)) {
            throw new ObjectOptimisticLockingFailureException(PricingZone.class, zone.getId());
        }
    }

    private UUID requireCompany() {
        return CompanyContext.getCompanyId().orElseThrow(() -> new BusinessRuleException(
                "No company is bound to this request. Pricing zones belong to a company."));
    }

    private String actor() {
        return SecurityUtils.getCurrentUserId().map(UUID::toString).orElse("system");
    }
}
