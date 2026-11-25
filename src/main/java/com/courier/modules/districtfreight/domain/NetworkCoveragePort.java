package com.courier.modules.districtfreight.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * How the delivery network a booking runs on (documents, surface or air parcels, V171) covers the destination
 * pincode. Lets District Level Freight and customer zone pricing refuse a pincode the chosen service does not
 * deliver to, and charge ODA by that network, the same way the zone tariff does.
 *
 * <p>Owned by this module; implemented in {@code modules/master}.
 */
public interface NetworkCoveragePort {

    /**
     * {@code networkLabel} is "Document", "Surface" or "Air"; {@code oda} null when the pincode master has no
     * per-network ODA for it (the pincode-wide flag then decides). Empty when the pincode, service type or package
     * type is unknown: nothing to check.
     */
    record NetworkCoverageRef(String networkLabel, boolean serviceable, Boolean oda, Integer tatHours) {
    }

    Optional<NetworkCoverageRef> coverage(String pincodeCode, UUID serviceTypeId, UUID packageTypeId);
}
