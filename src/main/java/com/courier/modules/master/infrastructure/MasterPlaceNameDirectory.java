package com.courier.modules.master.infrastructure;

import com.courier.modules.master.application.MasterNameResolver;
import com.courier.modules.master.domain.CityRepository;
import com.courier.modules.master.domain.DistrictRepository;
import com.courier.modules.master.domain.RegionalZoneRepository;
import com.courier.modules.master.domain.StateRepository;
import com.courier.modules.tariff.application.PlaceNamePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Batch name lookups of global places, one query per kind, for Zone Tariff's zone pages. */
@Component
@RequiredArgsConstructor
public class MasterPlaceNameDirectory implements PlaceNamePort {

    private final MasterNameResolver names;
    private final StateRepository states;
    private final DistrictRepository districts;
    private final CityRepository cities;
    private final RegionalZoneRepository regionalZones;

    @Override
    public Map<UUID, String> stateNames(Collection<UUID> ids) {
        return names.globalNamesById(states, ids);
    }

    @Override
    public Map<UUID, String> districtNames(Collection<UUID> ids) {
        return names.globalNamesById(districts, ids);
    }

    @Override
    public Map<UUID, String> cityNames(Collection<UUID> ids) {
        return names.globalNamesById(cities, ids);
    }

    @Override
    public Map<UUID, String> regionalZoneNames(Collection<UUID> ids) {
        return names.globalNamesById(regionalZones, ids);
    }
}
