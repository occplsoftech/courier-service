package com.courier.modules.tariff.application;

import com.courier.modules.tariff.domain.LocationScope;
import com.courier.modules.tariff.domain.TariffZone;

import java.util.List;
import java.util.UUID;

/** A zone with its locations resolved to display names. */
public record TariffZoneView(TariffZone zone, List<LocationView> locations) {

    public record LocationView(UUID id, LocationScope scope, UUID stateId, String stateName, UUID districtId,
                               String districtName, UUID cityId, String cityName, String pincode,
                               UUID regionalZoneId, String regionalZoneName) {

        public LocationView(UUID id, LocationScope scope, UUID stateId, String stateName, UUID districtId,
                            String districtName, UUID cityId, String cityName, String pincode) {
            this(id, scope, stateId, stateName, districtId, districtName, cityId, cityName, pincode, null, null);
        }

        /** "Mumbai, Mumbai district, Maharashtra" style label, most specific first. */
        public String label() {
            return switch (scope) {
                case STATE -> stateName;
                case DISTRICT -> districtName + " district, " + stateName;
                case CITY -> cityName + ", " + districtName + " district, " + stateName;
                case PINCODE -> pincode + (cityName != null ? " (" + cityName + ")" : "");
                case REGIONAL_ZONE -> (regionalZoneName != null ? regionalZoneName : "Regional zone") + " (regional zone)";
            };
        }
    }
}
