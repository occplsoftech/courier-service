package com.courier.modules.shipment.api.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record DrsDetailResponse(
        UUID deliveryUserId, UUID deliveryBranchId, LocalDate runDate,
        String drsNumber, List<DrsShipmentRowResponse> shipments,
        /** The run's vehicle, when one was given at DRS time. */
        UUID vehicleId
) {
}
