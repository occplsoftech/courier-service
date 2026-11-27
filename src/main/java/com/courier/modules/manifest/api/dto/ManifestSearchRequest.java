package com.courier.modules.manifest.api.dto;

import com.courier.modules.manifest.domain.ManifestStatus;
import com.courier.modules.shipment.domain.FlowType;

import java.util.UUID;

public record ManifestSearchRequest(
        ManifestStatus status,
        UUID bookingBranchId,
        UUID deliveryBranchId,
        String search,
        FlowType flowType,
        @io.swagger.v3.oas.annotations.media.Schema(description = "true: dispatched or received manifests (not CREATED)") Boolean sent
) {

    public static ManifestSearchRequest empty() {
        return new ManifestSearchRequest(null, null, null, null, null, null);
    }
}
