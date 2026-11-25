package com.courier.modules.master.application.command;

import com.courier.modules.master.domain.NetworkCoverage;

import java.util.UUID;

/**
 * Input to create or update a pincode. See {@link CountryCommand} for the code/version rule.
 *
 * <p>{@code code} is the postal code itself; {@code name} is the post office label.
 */
public record PincodeCommand(
        String code,
        String name,
        String description,
        Integer displayOrder,
        UUID areaId,
        Boolean serviceable,
        Boolean codAvailable,
        Boolean prepaidAvailable,
        Boolean pickupAvailable,
        String zone,
        Boolean odaApplicable,
        UUID regionalZoneId,
        NetworkCoverage doc,
        NetworkCoverage surface,
        NetworkCoverage air,
        Long expectedVersion
) {
    /** Without the V171 fields: no regional zone and no per-network coverage. */
    public PincodeCommand(String code, String name, String description, Integer displayOrder, UUID areaId,
                          Boolean serviceable, Boolean codAvailable, Boolean prepaidAvailable, Boolean pickupAvailable,
                          String zone, Boolean odaApplicable, Long expectedVersion) {
        this(code, name, description, displayOrder, areaId, serviceable, codAvailable, prepaidAvailable,
                pickupAvailable, zone, odaApplicable, null, null, null, null, expectedVersion);
    }
}
