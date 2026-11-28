package com.courier.modules.master.api;

import com.courier.modules.master.api.dto.CreatePincodeRequest;
import com.courier.modules.master.api.dto.PincodeResponse;
import com.courier.modules.master.api.dto.UpdatePincodeRequest;
import com.courier.modules.master.application.MasterNameResolver;
import com.courier.modules.master.application.command.PincodeCommand;
import com.courier.modules.master.domain.Area;
import com.courier.modules.master.domain.AreaRepository;
import com.courier.modules.master.domain.City;
import com.courier.modules.master.domain.CityRepository;
import com.courier.modules.master.domain.District;
import com.courier.modules.master.domain.DistrictRepository;
import com.courier.modules.master.domain.GlobalMasters;
import com.courier.modules.master.domain.MasterDataCriteria;
import com.courier.modules.master.domain.MasterDataSpecifications;
import com.courier.modules.master.domain.Pincode;
import com.courier.modules.master.domain.StateRepository;
import com.courier.modules.master.domain.RegionalZoneRepository;
import com.courier.modules.master.domain.NetworkCoverage;
import com.courier.modules.master.domain.ServiceNetwork;
import com.courier.modules.master.api.dto.NetworkCoverageDto;
import com.courier.shared.api.PageResponse;
import com.courier.shared.company.CompanyContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Wire contract to application/domain types for pincodes, including the Area -> City ->
 * District -> State chain a list row displays and a District/State filter resolves through
 * — {@code master_pincodes} itself only names the Area.
 */
@Component
@RequiredArgsConstructor
public class PincodeMasterMapper {

    private final MasterNameResolver names;
    private final AreaRepository areas;
    private final CityRepository cities;
    private final DistrictRepository districts;
    private final StateRepository states;
    private final RegionalZoneRepository regionalZones;

    public PincodeCommand toCommand(CreatePincodeRequest r) {
        return new PincodeCommand(r.code(), r.name(), r.description(), r.displayOrder(),
                r.areaId(), r.serviceable(), r.codAvailable(), r.prepaidAvailable(),
                r.pickupAvailable(), r.zone(), r.odaApplicable(), r.regionalZoneId(),
                coverage(r.doc()), coverage(r.surface()), coverage(r.air()),
                coverage(r.airCargo()), coverage(r.surfaceCargo()), coverage(r.premium()), null);
    }

    public PincodeCommand toCommand(UpdatePincodeRequest r) {
        return new PincodeCommand(null, r.name(), r.description(), r.displayOrder(),
                r.areaId(), r.serviceable(), r.codAvailable(), r.prepaidAvailable(),
                r.pickupAvailable(), r.zone(), r.odaApplicable(), r.regionalZoneId(),
                coverage(r.doc()), coverage(r.surface()), coverage(r.air()),
                coverage(r.airCargo()), coverage(r.surfaceCargo()), coverage(r.premium()), r.version());
    }

    private static NetworkCoverage coverage(NetworkCoverageDto d) {
        return d == null ? null : new NetworkCoverage(d.serviceable(), d.oda(), d.tatHours());
    }

    private static NetworkCoverageDto dto(NetworkCoverage c) {
        return new NetworkCoverageDto(c.serviceable(), c.oda(), c.tatHours());
    }

    public PincodeResponse toResponse(Pincode p) {
        return toResponse(p, loadGeography(List.of(p)));
    }

    public PageResponse<PincodeResponse> toPage(Page<Pincode> page) {
        Geography geo = loadGeography(page.getContent());
        return PageResponse.from(page, pincode -> toResponse(pincode, geo));
    }

    private PincodeResponse toResponse(Pincode p, Geography geo) {
        // Null-safe lookups: a pincode may have no regional zone (V187 cleared them) and the name maps do not take null keys.
        UUID cityId = get(geo.areaToCity, p.getAreaId());
        UUID districtId = get(geo.cityToDistrict, cityId);
        UUID stateId = get(geo.districtToState, districtId);
        return new PincodeResponse(p.getId(), p.getCompanyId(), p.getCode(), p.getName(),
                p.getDescription(), p.getStatus(), p.getDisplayOrder(),
                p.getAreaId(), get(geo.areaNames, p.getAreaId()),
                p.isServiceable(), p.isCodAvailable(), p.isPrepaidAvailable(),
                p.isPickupAvailable(), p.getZone(), p.isOdaApplicable(),
                districtId, get(geo.districtNames, districtId),
                stateId, get(geo.stateNames, stateId),
                p.getRegionalZoneId(), get(geo.regionalZoneNames, p.getRegionalZoneId()),
                dto(p.coverage(ServiceNetwork.DOCUMENT)), dto(p.coverage(ServiceNetwork.SURFACE)),
                dto(p.coverage(ServiceNetwork.AIR)), dto(p.coverage(ServiceNetwork.AIR_CARGO)),
                dto(p.coverage(ServiceNetwork.SURFACE_CARGO)), dto(p.coverage(ServiceNetwork.PREMIUM)),
                p.getCreatedBy(), p.getCreatedAt(), p.getUpdatedBy(), p.getUpdatedAt(), p.getVersion());
    }

    private static <V> V get(Map<UUID, V> map, UUID key) {
        return key == null ? null : map.get(key);
    }

    /**
     * Resolves the query-param spelling of a District/State filter down to the set of
     * Area ids it covers, for {@link MasterDataCriteria#with}. {@code null} when neither
     * is given — the caller's own {@code areaId} filter, if any, then stands alone.
     *
     * <p>District takes precedence over State when both are given (State would only widen
     * a District already scoped to it). An empty result correctly matches nothing, same as
     * any other resolved-but-empty scope.
     */
    public Set<UUID> resolveAreaIdsForGeography(UUID districtId, UUID stateId) {
        if (districtId == null && stateId == null) {
            return null;
        }
        return CompanyContext.runAs(GlobalMasters.PLATFORM_COMPANY_ID, () -> {
            UUID companyId = GlobalMasters.PLATFORM_COMPANY_ID;
            Set<UUID> cityIds;
            if (districtId != null) {
                cityIds = cities.findByCompanyIdAndDistrictIdOrderByDisplayOrderAscNameAsc(companyId, districtId)
                        .stream().map(City::getId).collect(Collectors.toSet());
            } else {
                Set<UUID> districtIds = districts
                        .findByCompanyIdAndStateIdOrderByDisplayOrderAscNameAsc(companyId, stateId)
                        .stream().map(District::getId).collect(Collectors.toSet());
                cityIds = districtIds.isEmpty() ? Set.of()
                        : cities.findByCompanyIdAndDistrictIdIn(companyId, districtIds)
                                .stream().map(City::getId).collect(Collectors.toSet());
            }
            if (cityIds.isEmpty()) {
                return Set.of();
            }
            return areas.findByCompanyIdAndCityIdIn(companyId, cityIds)
                    .stream().map(Area::getId).collect(Collectors.toSet());
        });
    }

    /** One query per level (never per row) for the page's Area -> City -> District -> State chain. */
    private Geography loadGeography(List<Pincode> pincodes) {
        List<UUID> areaIds = pincodes.stream().map(Pincode::getAreaId).toList();
        Map<UUID, String> areaNames = names.globalNamesById(areas, areaIds);
        Map<UUID, String> regionalZoneNames = names.globalNamesById(regionalZones,
                pincodes.stream().map(Pincode::getRegionalZoneId).filter(java.util.Objects::nonNull).toList());

        return CompanyContext.runAs(GlobalMasters.PLATFORM_COMPANY_ID, () -> {
            UUID companyId = GlobalMasters.PLATFORM_COMPANY_ID;

            MasterDataCriteria areaCriteria = MasterDataCriteria.none()
                    .withCompanyId(companyId).withIds(new HashSet<>(areaIds));
            Map<UUID, UUID> areaToCity = new HashMap<>();
            Set<UUID> cityIds = new HashSet<>();
            areas.findAll(MasterDataSpecifications.matching(areaCriteria)).forEach(a -> {
                areaToCity.put(a.getId(), a.getCityId());
                if (a.getCityId() != null) cityIds.add(a.getCityId());
            });

            MasterDataCriteria cityCriteria = MasterDataCriteria.none()
                    .withCompanyId(companyId).withIds(cityIds);
            Map<UUID, UUID> cityToDistrict = new HashMap<>();
            Set<UUID> districtIds = new HashSet<>();
            cities.findAll(MasterDataSpecifications.matching(cityCriteria)).forEach(c -> {
                cityToDistrict.put(c.getId(), c.getDistrictId());
                if (c.getDistrictId() != null) districtIds.add(c.getDistrictId());
            });
            Map<UUID, String> districtNames = names.globalNamesById(districts, districtIds);

            MasterDataCriteria districtCriteria = MasterDataCriteria.none()
                    .withCompanyId(companyId).withIds(districtIds);
            Map<UUID, UUID> districtToState = new HashMap<>();
            Set<UUID> stateIds = new HashSet<>();
            districts.findAll(MasterDataSpecifications.matching(districtCriteria)).forEach(d -> {
                districtToState.put(d.getId(), d.getStateId());
                if (d.getStateId() != null) stateIds.add(d.getStateId());
            });
            Map<UUID, String> stateNames = names.globalNamesById(states, stateIds);

            return new Geography(areaNames, areaToCity, cityToDistrict, districtToState,
                    districtNames, stateNames, regionalZoneNames);
        });
    }

    private record Geography(
            Map<UUID, String> areaNames,
            Map<UUID, UUID> areaToCity,
            Map<UUID, UUID> cityToDistrict,
            Map<UUID, UUID> districtToState,
            Map<UUID, String> districtNames,
            Map<UUID, String> stateNames,
            Map<UUID, String> regionalZoneNames) {
    }
}
