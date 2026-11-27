package com.courier.modules.master.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Result of {@code GET /api/v1/global-masters/pincodes/{code}/geo}. Read-only: names
 * already on file for this pincode's Area link, no postal-directory call and nothing
 * created. {@code matched=false} means no pincode with this code is on file for the
 * company — not an error, same meaning as {@link PincodeAreaLookupResponse#matched()}.
 */
@Schema(name = "PincodeGeoResponse", description = "Area/City/District already on file for a pincode")
public record PincodeGeoResponse(boolean matched, String areaName, String cityName, String districtName, String stateName) {

    public static PincodeGeoResponse notFound() {
        return new PincodeGeoResponse(false, null, null, null, null);
    }
}
