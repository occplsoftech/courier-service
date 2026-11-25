package com.courier.modules.tariff.application;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Display names of global places, many at a time. Implemented in {@code modules/master}. */
public interface PlaceNamePort {

    Map<UUID, String> stateNames(Collection<UUID> ids);

    Map<UUID, String> districtNames(Collection<UUID> ids);

    Map<UUID, String> cityNames(Collection<UUID> ids);

    Map<UUID, String> regionalZoneNames(Collection<UUID> ids);
}
