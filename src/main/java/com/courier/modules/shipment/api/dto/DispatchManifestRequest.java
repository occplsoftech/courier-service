package com.courier.modules.shipment.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record DispatchManifestRequest(
        @NotNull UUID manifestId,
        @io.swagger.v3.oas.annotations.media.Schema(description = "Optional when the trip goes with a co-loader") UUID vehicleId,
        @io.swagger.v3.oas.annotations.media.Schema(description = "Optional when the trip goes with a co-loader") UUID driverUserId,
        Instant departureTime,
        @DecimalMin(value = "0") BigDecimal fuelCost,
        @DecimalMin(value = "0") BigDecimal driverAdvance,
        @DecimalMin(value = "0") BigDecimal tollAmount,
        @DecimalMin(value = "0") BigDecimal otherAmount,
        @io.swagger.v3.oas.annotations.media.Schema(description = "Co-loader the parcels are handed to (Masters → Co-Loaders)") UUID coLoaderId,
        @jakarta.validation.constraints.Size(max = 60) String forwardingNumber,
        @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(9999) Integer bagsCount,
        @io.swagger.v3.oas.annotations.media.Schema(description = "With Forwarder No.: shipment id → the co-loader's number for that AWB")
        java.util.Map<UUID, @jakarta.validation.constraints.Size(max = 60) String> forwarderNumbers
) {
    public DispatchManifestRequest(UUID manifestId, UUID vehicleId, UUID driverUserId, Instant departureTime, BigDecimal fuelCost,
                                   BigDecimal driverAdvance, BigDecimal tollAmount, BigDecimal otherAmount) {
        this(manifestId, vehicleId, driverUserId, departureTime, fuelCost, driverAdvance, tollAmount, otherAmount, null, null, null, null);
    }
}
