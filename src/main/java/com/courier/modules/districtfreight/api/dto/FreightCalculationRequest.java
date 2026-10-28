package com.courier.modules.districtfreight.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Schema(name = "FreightCalculationRequest", description = "From Station + destination pincode (+ optional Area) + chargeable weight")
public record FreightCalculationRequest(
        @NotNull UUID bookingBranchId,
        @NotBlank String destinationPincode,
        @Schema(description = "The specific Area of destinationPincode the operator picked, if any. "
                + "Resolves District/ODA off that exact pincode-area link rather than the pincode's legacy single area.")
        UUID destinationAreaId,
        @NotNull @DecimalMin(value = "0.001", message = "Weight must be greater than zero") BigDecimal chargeableWeight,
        @Schema(description = "A Business Customer — when it has an ACTIVE pricing zone covering the "
                + "destination, freight is priced from that zone's rate card instead.")
        UUID businessCustomerId,
        @Schema(description = "Declared value — basis of the zone rate's FOV %. Used only with businessCustomerId.")
        @DecimalMin("0") BigDecimal declaredValue,
        @Schema(description = "Service type and package type of the booking. With both set, a company zone tariff that "
                + "covers the booking branch's pincode prices the freight (after a Business Customer's own zone).")
        UUID serviceTypeId,
        UUID packageTypeId,
        @Schema(description = "Pieces, for per-piece tariff charges. Default 1.")
        @Positive Integer numberOfPackages,
        @Schema(description = "Picks the rate card valid on that date. Default today.")
        LocalDate bookingDate
) {
}
