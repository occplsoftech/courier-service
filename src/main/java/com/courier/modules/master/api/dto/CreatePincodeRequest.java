package com.courier.modules.master.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Body of {@code POST /api/v1/master/pincodes}.
 *
 * <p>{@code code} is the postal code itself — digits only, unlike every other master list
 * — and {@code name} is the post office or locality label. One pincode belongs to exactly
 * one area, and exists once per company.
 *
 * <p>The three availability flags default to true when omitted. Setting
 * {@code serviceable} false forces all three false: a pincode nobody delivers to cannot
 * offer COD.
 */
@Schema(name = "CreatePincodeRequest", description = "New pincode within an area")
public record CreatePincodeRequest(
        @NotBlank @Pattern(regexp = "^[0-9]{4,10}$", message = "must be 4 to 10 digits")
        @Schema(example = "411038") String code,
        @NotBlank @Size(max = 150) @Schema(example = "Kothrud H.O.") String name,
        @Size(max = 500) String description,
        @PositiveOrZero Integer displayOrder,
        @NotNull UUID areaId,
        Boolean serviceable,
        Boolean codAvailable,
        Boolean prepaidAvailable,
        Boolean pickupAvailable,
        @Size(max = 20) @Schema(example = "LOCAL") String zone,
        @Schema(description = "Out-of-Delivery-Area — still served, priced differently.")
        Boolean odaApplicable,
        @Schema(description = "Regional zone (pricing region) id") UUID regionalZoneId,
        @Valid @Schema(description = "Document network coverage") NetworkCoverageDto doc,
        @Valid @Schema(description = "Surface network coverage") NetworkCoverageDto surface,
        @Valid @Schema(description = "Air network coverage") NetworkCoverageDto air,
        @Valid @Schema(description = "Air cargo network coverage; left out = unchanged") NetworkCoverageDto airCargo,
        @Valid @Schema(description = "Surface cargo network coverage; left out = unchanged") NetworkCoverageDto surfaceCargo,
        @Valid @Schema(description = "Premium network coverage; left out = unchanged") NetworkCoverageDto premium
) {
}
