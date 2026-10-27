package com.courier.modules.tariff.application;

import com.courier.modules.master.application.CityService;
import com.courier.modules.master.application.DistrictService;
import com.courier.modules.master.application.PincodeLocation;
import com.courier.modules.master.application.PincodeService;
import com.courier.modules.master.application.StateService;
import com.courier.modules.master.domain.City;
import com.courier.modules.master.domain.District;
import com.courier.modules.tariff.domain.LocationScope;
import com.courier.modules.tariff.domain.TariffZone;
import com.courier.modules.tariff.domain.TariffZoneLocation;
import com.courier.modules.tariff.domain.TariffZoneLocationRepository;
import com.courier.modules.tariff.domain.TariffZoneRepository;
import com.courier.modules.tariff.domain.TariffZoneStatus;
import com.courier.modules.tariff.domain.ZoneUsage;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Tariff zone use cases. Every location is checked (geography exists, the city is in the district, the place is not
 * already in a competing zone) before anything is written, so a refused request leaves no partial zone behind and no
 * dirty row is flushed while the global geography masters are being read under the platform company.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TariffZoneServiceImpl implements TariffZoneService {

    private static final String ENTITY = "TariffZone";
    private static final String WRITE = "hasRole('" + Roles.COMPANY_ADMIN + "')";
    private static final String READ = "isAuthenticated()";
    private static final java.util.regex.Pattern PINCODE = java.util.regex.Pattern.compile("^[1-9][0-9]{5}$");

    private final TariffZoneRepository zones;
    private final TariffZoneLocationRepository locations;
    private final StateService stateService;
    private final DistrictService districtService;
    private final CityService cityService;
    private final PincodeService pincodeService;
    private final AuditService auditService;

    // ------------------------------------------------------------------ zones

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public TariffZoneView create(ZoneInput input, List<LocationInput> locationInputs) {
        UUID companyId = requireCompany();
        TariffZone zone = TariffZone.builder().status(TariffZoneStatus.ACTIVE).build();
        apply(zone, input);
        requireCodeAvailable(companyId, zone.getCode(), null);
        List<TariffZoneLocation> prepared = prepare(companyId, zone, locationInputs);

        TariffZone saved = zones.save(zone);
        for (TariffZoneLocation location : prepared) {
            location.setZoneId(saved.getId());
            locations.save(location);
        }
        log.info("Tariff zone {} ({}) created with {} location(s) in company {} by {}",
                saved.getCode(), saved.getId(), prepared.size(), companyId, actor());
        auditService.record(AuditAction.TARIFF_ZONE_CREATED, ENTITY, saved.getId(),
                Map.of("code", saved.getCode(), "usage", saved.getZoneUsage().name(), "locations", String.valueOf(prepared.size())));
        return view(companyId, saved);
    }

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public TariffZoneView update(UUID zoneId, ZoneInput input, Long expectedVersion) {
        UUID companyId = requireCompany();
        TariffZone zone = load(zoneId, companyId);
        requireCurrentVersion(zone, expectedVersion);
        ZoneUsage before = zone.getZoneUsage();
        apply(zone, input);
        requireCodeAvailable(companyId, zone.getCode(), zone.getId());
        if (zone.getZoneUsage() != before) {
            // A wider usage can make this zone compete with others for places it already holds.
            for (TariffZoneLocation existing : locations.findByZone(companyId, zone.getId())) {
                requireNoClash(companyId, zone, existing, true);
            }
        }
        TariffZone saved = zones.save(zone);
        auditService.record(AuditAction.TARIFF_ZONE_UPDATED, ENTITY, saved.getId(),
                Map.of("code", saved.getCode(), "usage", saved.getZoneUsage().name()));
        return view(companyId, saved);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READ)
    public TariffZoneView getById(UUID zoneId) {
        UUID companyId = requireCompany();
        return view(companyId, load(zoneId, companyId));
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READ)
    public List<TariffZoneView> search(ZoneUsage usage, TariffZoneStatus status, String search) {
        UUID companyId = requireCompany();
        String pattern = search == null || search.isBlank() ? null : "%" + search.trim().toLowerCase() + "%";
        List<TariffZone> found = zones.search(companyId, usage, status, pattern);
        if (found.isEmpty()) {
            return List.of();
        }
        Set<UUID> ids = found.stream().map(TariffZone::getId).collect(Collectors.toSet());
        Map<UUID, List<TariffZoneLocation>> byZone = locations.findByZones(companyId, ids).stream()
                .collect(Collectors.groupingBy(TariffZoneLocation::getZoneId));
        Names names = new Names();
        return found.stream().map(z -> toView(z, byZone.getOrDefault(z.getId(), List.of()), names)).toList();
    }

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public TariffZoneView activate(UUID zoneId) {
        UUID companyId = requireCompany();
        TariffZone zone = load(zoneId, companyId);
        zone.activate();
        TariffZone saved = zones.save(zone);
        // Audit after save: recording switches the company binding, which would otherwise flush this dirty zone
        // under the wrong company and trip the isolation guard.
        auditService.record(AuditAction.TARIFF_ZONE_ACTIVATED, ENTITY, saved.getId(), Map.of("code", saved.getCode()));
        return view(companyId, saved);
    }

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public TariffZoneView deactivate(UUID zoneId) {
        UUID companyId = requireCompany();
        TariffZone zone = load(zoneId, companyId);
        zone.deactivate();
        TariffZone saved = zones.save(zone);
        auditService.record(AuditAction.TARIFF_ZONE_DEACTIVATED, ENTITY, saved.getId(), Map.of("code", saved.getCode()));
        return view(companyId, saved);
    }

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public void delete(UUID zoneId) {
        UUID companyId = requireCompany();
        TariffZone zone = load(zoneId, companyId);
        UUID actor = SecurityUtils.getCurrentUserId().orElse(null);
        locations.findByZone(companyId, zone.getId()).forEach(l -> {
            l.softDelete(actor);
            locations.save(l);
        });
        zone.softDelete(actor);
        zones.save(zone);
        log.warn("Tariff zone {} ({}) deleted in company {} by {}", zone.getCode(), zone.getId(), companyId, actor());
        auditService.record(AuditAction.TARIFF_ZONE_DELETED, ENTITY, zone.getId(), Map.of("code", zone.getCode()));
    }

    // ------------------------------------------------------------- locations

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public TariffZoneView addLocations(UUID zoneId, List<LocationInput> inputs) {
        UUID companyId = requireCompany();
        TariffZone zone = load(zoneId, companyId);
        if (inputs == null || inputs.isEmpty()) {
            throw new BusinessRuleException("Add at least one state, district, city or pincode.");
        }
        List<TariffZoneLocation> prepared = prepare(companyId, zone, inputs);
        prepared.forEach(locations::save);
        auditService.record(AuditAction.TARIFF_ZONE_LOCATION_ADDED, ENTITY, zone.getId(),
                Map.of("code", zone.getCode(), "added", String.valueOf(prepared.size())));
        return view(companyId, zone);
    }

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public TariffZoneView removeLocation(UUID zoneId, UUID locationId) {
        UUID companyId = requireCompany();
        TariffZone zone = load(zoneId, companyId);
        TariffZoneLocation location = locations.findByIdWithinCompany(locationId, companyId)
                .filter(l -> l.getZoneId().equals(zone.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("TariffZoneLocation", locationId));
        location.softDelete(SecurityUtils.getCurrentUserId().orElse(null));
        locations.save(location);
        auditService.record(AuditAction.TARIFF_ZONE_LOCATION_REMOVED, ENTITY, zone.getId(),
                Map.of("code", zone.getCode(), "locationId", locationId.toString()));
        return view(companyId, zone);
    }

    // --------------------------------------------------------------- resolve

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READ)
    public Optional<Resolution> resolve(String pincode, ZoneUsage direction) {
        UUID companyId = requireCompany();
        String code = pincode == null ? "" : pincode.trim();
        if (!PINCODE.matcher(code).matches()) {
            throw new BusinessRuleException("Enter a 6-digit pincode.");
        }
        if (direction != ZoneUsage.FROM && direction != ZoneUsage.TO) {
            throw new BusinessRuleException("Say whether the pincode is where the shipment starts (FROM) or is delivered (TO).");
        }
        PincodeLocation where = pincodeService.location(code)
                .orElseThrow(() -> new BusinessRuleException("Pincode " + code + " is not in the pincode master."));
        UUID stateId = where.districtId() == null ? null
                : tryGet(() -> districtService.getById(where.districtId()).getStateId());
        Names names = new Names();
        TariffZoneView.LocationView place = new TariffZoneView.LocationView(null, LocationScope.PINCODE, stateId,
                names.state(stateId), where.districtId(), names.district(where.districtId()), where.cityId(),
                names.city(where.cityId()), code);

        List<TariffZoneLocation> candidates = locations.findCandidates(companyId, code, where.cityId(), where.districtId(), stateId);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        Map<UUID, TariffZone> zoneById = zones.findAllWithinCompany(
                        candidates.stream().map(TariffZoneLocation::getZoneId).collect(Collectors.toSet()), companyId)
                .stream().collect(Collectors.toMap(TariffZone::getId, Function.identity()));
        return candidates.stream()
                .filter(l -> {
                    TariffZone z = zoneById.get(l.getZoneId());
                    return z != null && z.isActive()
                            && (direction == ZoneUsage.FROM ? z.getZoneUsage().coversFrom() : z.getZoneUsage().coversTo());
                })
                .min(Comparator.comparingInt(l -> l.getScope().rank()))
                .map(l -> new Resolution(zoneById.get(l.getZoneId()), toLocationView(l, names), place));
    }

    // ---------------------------------------------------------------- helpers

    private void apply(TariffZone zone, ZoneInput input) {
        if (input == null) {
            throw new BusinessRuleException("Zone details are required.");
        }
        zone.setCode(input.code());
        zone.setName(input.name());
        zone.setZoneUsage(input.usage());
        zone.setDescription(input.description());
        zone.setDisplayOrder(input.displayOrder() == null ? 0 : input.displayOrder());
        zone.applyInvariants();
    }

    /**
     * Turns inputs into unsaved locations for {@code zone}: checks the geography, fills in the ids above the scope,
     * refuses a place listed twice in the same request or already held by a competing zone.
     */
    private List<TariffZoneLocation> prepare(UUID companyId, TariffZone zone, List<LocationInput> inputs) {
        List<TariffZoneLocation> prepared = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (LocationInput input : inputs == null ? List.<LocationInput>of() : inputs) {
            TariffZoneLocation location = build(input);
            location.setZoneId(zone.getId());
            if (!seen.add(location.getScope() + ":" + location.placeKey())) {
                throw new BusinessRuleException("The same place is listed twice: " + describe(location) + ".");
            }
            requireNoClash(companyId, zone, location, false);
            prepared.add(location);
        }
        return prepared;
    }

    private TariffZoneLocation build(LocationInput input) {
        if (input == null || input.scope() == null) {
            throw new BusinessRuleException("Each location needs a scope: STATE, DISTRICT, CITY or PINCODE.");
        }
        TariffZoneLocation.TariffZoneLocationBuilder b = TariffZoneLocation.builder().scope(input.scope());
        switch (input.scope()) {
            case STATE -> {
                require(input.stateId(), "Choose a state.");
                lookup(input.stateId(), stateService::getById, "state");
                b.stateId(input.stateId());
            }
            case DISTRICT -> {
                require(input.districtId(), "Choose a district.");
                District d = lookup(input.districtId(), districtService::getById, "district");
                b.districtId(d.getId()).stateId(d.getStateId());
            }
            case CITY -> {
                require(input.cityId(), "Choose a city.");
                City c = lookup(input.cityId(), cityService::getById, "city");
                if (input.districtId() != null && !Objects.equals(input.districtId(), c.getDistrictId())) {
                    throw new BusinessRuleException("City %s is not in the district chosen.".formatted(c.getName()));
                }
                District d = c.getDistrictId() == null ? null : lookup(c.getDistrictId(), districtService::getById, "district");
                b.cityId(c.getId()).districtId(c.getDistrictId()).stateId(d == null ? null : d.getStateId());
            }
            case PINCODE -> {
                String code = input.pincode() == null ? "" : input.pincode().trim();
                if (!PINCODE.matcher(code).matches()) {
                    throw new BusinessRuleException("A pincode is 6 digits and does not start with 0: '" + input.pincode() + "'.");
                }
                PincodeLocation where = pincodeService.location(code)
                        .orElseThrow(() -> new BusinessRuleException("Pincode " + code + " is not in the pincode master."));
                UUID stateId = where.districtId() == null ? null
                        : tryGet(() -> districtService.getById(where.districtId()).getStateId());
                b.pincode(code).cityId(where.cityId()).districtId(where.districtId()).stateId(stateId);
            }
        }
        return b.build();
    }

    /**
     * Refuses a place already in this zone, or already in another zone whose usage competes with this one.
     * {@code recheck}: the location is one this zone already holds (its usage changed), so only other zones count.
     */
    private void requireNoClash(UUID companyId, TariffZone zone, TariffZoneLocation location, boolean recheck) {
        LocationScope scope = location.getScope();
        List<TariffZoneLocation> same = switch (scope) {
            case STATE -> locations.findSameState(companyId, scope, location.getStateId());
            case DISTRICT -> locations.findSameDistrict(companyId, scope, location.getDistrictId());
            case CITY -> locations.findSameCity(companyId, scope, location.getCityId());
            case PINCODE -> locations.findSamePincode(companyId, scope, location.getPincode());
        };
        for (TariffZoneLocation existing : same) {
            if (zone.getId() != null && existing.getZoneId().equals(zone.getId())) {
                if (recheck) {
                    continue;
                }
                throw new DuplicateResourceException("TariffZoneLocation", "location", describe(location) + " is already in this zone");
            }
            TariffZone other = zones.findByIdWithinCompany(existing.getZoneId(), companyId).orElse(null);
            if (other != null && other.getZoneUsage().overlaps(zone.getZoneUsage())) {
                throw new DuplicateResourceException("TariffZoneLocation", "location",
                        describe(location) + " is already in zone " + other.getCode() + " (" + other.getName() + ")");
            }
        }
    }

    private TariffZoneView view(UUID companyId, TariffZone zone) {
        // Geography lookups below run under the platform company (global masters); a dirty zone auto-flushed in that
        // window would trip the company-isolation guard. Flush first.
        zones.flush();
        return toView(zone, locations.findByZone(companyId, zone.getId()), new Names());
    }

    private TariffZoneView toView(TariffZone zone, List<TariffZoneLocation> zoneLocations, Names names) {
        List<TariffZoneView.LocationView> views = zoneLocations.stream()
                .map(l -> toLocationView(l, names))
                .sorted(Comparator.comparingInt((TariffZoneView.LocationView v) -> -v.scope().rank())
                        .thenComparing(v -> String.valueOf(v.label()), String::compareToIgnoreCase))
                .toList();
        return new TariffZoneView(zone, views);
    }

    private TariffZoneView.LocationView toLocationView(TariffZoneLocation l, Names names) {
        return new TariffZoneView.LocationView(l.getId(), l.getScope(), l.getStateId(), names.state(l.getStateId()),
                l.getDistrictId(), names.district(l.getDistrictId()), l.getCityId(), names.city(l.getCityId()), l.getPincode());
    }

    private String describe(TariffZoneLocation l) {
        Names names = new Names();
        return toLocationView(l, names).label();
    }

    /** Per-call memo of id -> display name, so a list does not look the same district up for every row. */
    private final class Names {
        private final Map<UUID, String> states = new HashMap<>();
        private final Map<UUID, String> districts = new HashMap<>();
        private final Map<UUID, String> cities = new HashMap<>();

        String state(UUID id) {
            return id == null ? null : states.computeIfAbsent(id, k -> tryGet(() -> stateService.getById(k).getName()));
        }

        String district(UUID id) {
            return id == null ? null : districts.computeIfAbsent(id, k -> tryGet(() -> districtService.getById(k).getName()));
        }

        String city(UUID id) {
            return id == null ? null : cities.computeIfAbsent(id, k -> tryGet(() -> cityService.getById(k).getName()));
        }
    }

    private static <T> T tryGet(Supplier<T> supplier) {
        try {
            return supplier.get();
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

    private static void require(Object value, String message) {
        if (value == null) {
            throw new BusinessRuleException(message);
        }
    }

    private TariffZone load(UUID id, UUID companyId) {
        return zones.findByIdWithinCompany(id, companyId).orElseThrow(() -> new ResourceNotFoundException(ENTITY, id));
    }

    private void requireCodeAvailable(UUID companyId, String code, UUID excludeId) {
        if (zones.existsByCode(companyId, code, excludeId)) {
            throw new DuplicateResourceException(ENTITY, "code", code);
        }
    }

    private void requireCurrentVersion(TariffZone zone, Long expectedVersion) {
        if (expectedVersion != null && !Objects.equals(zone.getVersion(), expectedVersion)) {
            throw new ObjectOptimisticLockingFailureException(TariffZone.class, zone.getId());
        }
    }

    private UUID requireCompany() {
        return CompanyContext.getCompanyId().orElseThrow(() -> new BusinessRuleException(
                "No company is bound to this request. Tariff zones belong to a company."));
    }

    private String actor() {
        return SecurityUtils.getCurrentUserId().map(UUID::toString).orElse("system");
    }
}
