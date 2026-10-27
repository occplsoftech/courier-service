package com.courier.modules.tariff.application;

import com.courier.modules.tariff.domain.LocationScope;
import com.courier.modules.tariff.domain.TariffZone;
import com.courier.modules.tariff.domain.TariffZoneStatus;
import com.courier.modules.tariff.domain.ZoneUsage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The company's tariff zones and the places that belong to them (Phase 1 of the zone tariff). {@code COMPANY_ADMIN}
 * writes; any signed-in user of the company reads, because booking has to find the zone of a pincode.
 *
 * <p>A place may sit in only one zone of each direction: two zones that can both be asked "where is this delivered"
 * (TO or BOTH) cannot share a place, and the same for "where does it start" (FROM or BOTH). When a pincode matches
 * several locations, the most specific one decides: pincode, then city, then district, then state.
 */
public interface TariffZoneService {

    /** @param locations may be empty; all are checked before anything is saved. */
    TariffZoneView create(ZoneInput zone, List<LocationInput> locations);

    TariffZoneView update(UUID zoneId, ZoneInput zone, Long expectedVersion);

    TariffZoneView getById(UUID zoneId);

    /** All filters optional. Not paged: a company has tens of zones, not thousands. */
    List<TariffZoneView> search(ZoneUsage usage, TariffZoneStatus status, String search);

    /** Adds every location or none: one clash refuses the whole list. */
    TariffZoneView addLocations(UUID zoneId, List<LocationInput> locations);

    TariffZoneView removeLocation(UUID zoneId, UUID locationId);

    TariffZoneView activate(UUID zoneId);

    TariffZoneView deactivate(UUID zoneId);

    /** Soft-deletes the zone and its locations. */
    void delete(UUID zoneId);

    /**
     * The ACTIVE zone a pincode belongs to for one direction, and the location that decided it. Empty when no zone
     * covers it. {@code direction} is FROM or TO.
     */
    Optional<Resolution> resolve(String pincode, ZoneUsage direction);

    record ZoneInput(String code, String name, ZoneUsage usage, String description, Integer displayOrder) {
    }

    /**
     * One place to add. Only the id for the scope is needed (STATE: stateId, DISTRICT: districtId, CITY: cityId,
     * PINCODE: pincode); the rest is filled in from the geography masters.
     */
    record LocationInput(LocationScope scope, UUID stateId, UUID districtId, UUID cityId, String pincode) {
    }

    /** {@code place} describes the pincode itself (its city, district and state); {@code matchedBy} the rule that won. */
    record Resolution(TariffZone zone, TariffZoneView.LocationView matchedBy, TariffZoneView.LocationView place) {
    }
}
