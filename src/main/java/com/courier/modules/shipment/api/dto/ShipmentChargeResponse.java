package com.courier.modules.shipment.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** The Pricing Engine's own charge breakup, persisted at booking time. See {@code GET /api/v1/shipments/{id}/charges}. */
@Schema(name = "ShipmentChargeResponse", description = "Freight through Net Amount, plus the matched lane/rate")
public record ShipmentChargeResponse(
        UUID shipmentId,
        BigDecimal freight, BigDecimal fuelCharge, BigDecimal handlingCharge, BigDecimal odaCharge,
        BigDecimal insuranceCharge,
        @Schema(description = "Sum of ACTIVE charge-module rows (e.g. \"Hamali\") matched "
                + "to this shipment's service type and weight/distance. GST-inclusive, "
                + "unlike appointmentDeliveryCharge.")
        BigDecimal applicableCharges,
        @Schema(description = "applicableCharges broken out by the charge module's own name "
                + "(e.g. \"Hamali\", \"Fuel Surcharge\") — resolved live off this shipment's "
                + "own booking/delivery branch, chargeable weight and freight, not persisted, "
                + "so a since-renamed or reconfigured charge still shows correctly. Empty when "
                + "no charge is configured for this shipment's service type.")
        List<ApplicableChargeLine> applicableChargeLines,
        BigDecimal gstAmount, BigDecimal discountAmount,
        BigDecimal roundOff, BigDecimal otherCharges,
        @Schema(description = "Manual, typed at booking time when appointmentDelivery is "
                + "checked. Deliberately GST-free — added straight into netAmount, never "
                + "folded into gstAmount.")
        BigDecimal appointmentDeliveryCharge,
        @Schema(description = "Manual, typed at booking time when deliveryType is DOOR. "
                + "Taxed with GST (folded into gstAmount), unlike appointmentDeliveryCharge. "
                + "Zero when deliveryType is OFFICE.")
        BigDecimal doorDeliveryCharge,
        @Schema(description = "Company-configured flat charge added by the backend on To-Pay "
                + "bookings. Taxed with GST (folded into gstAmount). Zero otherwise.")
        BigDecimal toPayCharge,
        BigDecimal commissionOnBasicFreight, BigDecimal branchCommissionOnOtherAmount,
        BigDecimal companyCommissionOnBasicFreight, BigDecimal totalCommission, BigDecimal netAmount,
        UUID matchedRouteId, String matchedRouteCode,
        UUID matchedRateId, String matchedRateCode,
        @Schema(description = "The Freight Factor grid cell's own factor, or an accepted "
                + "override of it — null unless this shipment priced through the Freight "
                + "Factor fallback (matchedRouteId/matchedRateId both null).")
        BigDecimal appliedFreightFactor,
        @Schema(description = "Cash on Delivery: 2% of the COD collection amount, at least 100. Taxed with GST "
                + "(folded into gstAmount). Zero otherwise.")
        BigDecimal codCharge
) {
    @Schema(name = "ApplicableChargeLine")
    public record ApplicableChargeLine(String chargeName, BigDecimal amount) {
    }
}
