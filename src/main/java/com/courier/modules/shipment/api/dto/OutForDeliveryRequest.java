package com.courier.modules.shipment.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record OutForDeliveryRequest(
        @NotEmpty List<UUID> shipmentIds,
        /** The staff delivery boy; or leave it out and give coLoaderId. Exactly one of the two. */
        UUID deliveryUserId,
        /** Optional — the delivery boy's own vehicle for this run. Validated active here
         *  (the controller, not ShipmentService — see its class doc for why). */
        UUID vehicleId,
        @DecimalMin(value = "0") BigDecimal fuelCost,
        @DecimalMin(value = "0") BigDecimal deliveryCharge,
        /** A co-loader (Masters → Co-Loaders) delivers it instead of a staff delivery boy. */
        UUID coLoaderId
) {
    public OutForDeliveryRequest(List<UUID> shipmentIds, UUID deliveryUserId, UUID vehicleId, BigDecimal fuelCost, BigDecimal deliveryCharge) {
        this(shipmentIds, deliveryUserId, vehicleId, fuelCost, deliveryCharge, null);
    }
}
