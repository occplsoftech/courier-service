package com.courier.modules.master.application;

import com.courier.modules.master.domain.NetworkCoverage;
import com.courier.modules.master.domain.ServiceNetwork;

import java.util.UUID;

/**
 * Geography ids behind a pincode's primary Area; any may be null when the chain is incomplete. Also its
 * regional zone and how each delivery network covers it (V171; {@link NetworkCoverage#NONE} when unknown).
 */
public record PincodeLocation(UUID pincodeId, UUID areaId, UUID cityId, UUID districtId,
                              boolean serviceable, boolean codAvailable, boolean odaApplicable,
                              UUID regionalZoneId, NetworkCoverage doc, NetworkCoverage surface,
                              NetworkCoverage air) {

    public PincodeLocation {
        doc = NetworkCoverage.orNone(doc);
        surface = NetworkCoverage.orNone(surface);
        air = NetworkCoverage.orNone(air);
    }

    /** Without the V171 data: no regional zone, no per-network coverage. */
    public PincodeLocation(UUID pincodeId, UUID areaId, UUID cityId, UUID districtId,
                           boolean serviceable, boolean codAvailable, boolean odaApplicable) {
        this(pincodeId, areaId, cityId, districtId, serviceable, codAvailable, odaApplicable, null, null, null, null);
    }

    public NetworkCoverage coverage(ServiceNetwork network) {
        return switch (network) {
            case DOCUMENT -> doc;
            case SURFACE -> surface;
            case AIR -> air;
        };
    }

    /** Whether {@code network} delivers here: its own flag when known, else the pincode's. */
    public boolean serviceableBy(ServiceNetwork network) {
        return serviceable && coverage(network).serviceableOr(true);
    }

    /** Whether {@code network} delivers here as ODA: its own flag when known, else the pincode's. */
    public boolean odaBy(ServiceNetwork network) {
        return coverage(network).odaOr(odaApplicable);
    }
}
