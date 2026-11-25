package com.courier.modules.districtfreight.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(name = "FreightCalculationResponse", description = "District Level Freight's own breakdown for one booking")
public record FreightCalculationResponse(
        UUID matchedFreightId,
        UUID bookingBranchId, String bookingBranchCode, String bookingBranchName,
        UUID districtId, String districtCode, String districtName,
        String destinationPincode,
        @Schema(description = "The destination pincode/area's resolved city — shown at "
                + "booking instead of a Delivery Branch picker")
        String destinationCityName,
        BigDecimal chargeableWeight,
        String weightSlabLabel,
        BigDecimal ratePerKg,
        BigDecimal baseFreight,
        boolean odaApplicable,
        BigDecimal odaCharge,
        BigDecimal totalFreight,
        @Schema(description = "Set when a Business Customer pricing zone priced this booking instead "
                + "of District Level Freight; weightSlabLabel then names the zone")
        String customerZoneName,
        @Schema(description = "Zone rate's docket + mathadi + pack + fuel + FOV — the minimum Other "
                + "Charges for the booking. Zero when District Level Freight priced it")
        BigDecimal customerExtraCharges,
        @Schema(description = "Destination pincode's delivery time in hours for the booking's service "
                + "(document, surface or air network), when the pincode master has it")
        Integer tatHours
) {
}
