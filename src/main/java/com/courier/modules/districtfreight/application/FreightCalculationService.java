package com.courier.modules.districtfreight.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The single freight-calculation entry point District Level Freight exposes — used by
 * both {@code DistrictLevelFreightController}'s live-preview endpoint and {@code
 * ShipmentServiceImpl}'s authoritative booking-time calculation, so the rate-card lookup
 * itself lives in exactly one place.
 *
 * <p>Calculation sequence: Destination Pincode -&gt; Pincode Coverage -&gt; Destination
 * District -&gt; From Station -&gt; District Level Freight Rate -&gt; Applicable Weight
 * Slab -&gt; Base Freight -&gt; ODA check -&gt; Total Freight. The COMPLETE weight prices
 * at exactly one slab's rate, never a progressive split across slabs.
 */
public interface FreightCalculationService {

    /**
     * @param bookingBranchId  the "From Station" — a company branch
     * @param destinationPincode the destination pincode typed/selected at booking
     * @param destinationAreaId the specific Area of that pincode the operator picked from
     *        its Area dropdown, if any — resolves District/ODA off that exact
     *        {@code master_pincode_areas} link instead of the pincode's single legacy
     *        {@code area_id}. Null falls back to the legacy pincode-wide resolution.
     * @param chargeableWeight the shipment's already-computed chargeable weight, in KG
     * @throws com.courier.shared.exception.BusinessRuleException weight is not positive,
     *         weight exceeds the configured maximum (2000 KG), the destination pincode is
     *         not on file / not serviceable / has no resolvable district, or no District
     *         Level Freight configuration exists for this From Station + District
     */
    default FreightCalculationResult calculate(UUID bookingBranchId, String destinationPincode,
                                               UUID destinationAreaId, BigDecimal chargeableWeight) {
        return calculate(bookingBranchId, destinationPincode, destinationAreaId, chargeableWeight, null, null);
    }

    /**
     * As above, for a booking made for a Business Customer: when that customer has an ACTIVE
     * pricing zone covering the destination (city match beating whole-district), freight and
     * ODA come from the zone's rate card instead of District Level Freight, and the result
     * carries the zone's docket/mathadi/pack/fuel/FOV lines as {@code customerExtraCharges}.
     * A customer with no zone for that destination silently falls through to District Level
     * Freight; a zone that covers it but has no usable rate/slab fails with its own message.
     *
     * @param businessCustomerId null for an ordinary booking
     * @param declaredValue      basis of the zone rate's FOV %; null counts as zero
     */
    default FreightCalculationResult calculate(UUID bookingBranchId, String destinationPincode, UUID destinationAreaId,
                                               BigDecimal chargeableWeight, UUID businessCustomerId,
                                               BigDecimal declaredValue) {
        return calculate(bookingBranchId, destinationPincode, destinationAreaId, chargeableWeight, businessCustomerId,
                declaredValue, null);
    }

    /**
     * As above, with what the company's zone tariff needs. When the booking branch's pincode is in a tariff from zone
     * that has an active rate card on the booking date, that card prices the freight (a Business Customer's own zone
     * still wins) and its fuel, hamali, docket… lines come back as {@code customerExtraCharges}. Without
     * {@code tariff} (or without its service and package type) the zone tariff is not consulted.
     */
    FreightCalculationResult calculate(UUID bookingBranchId, String destinationPincode, UUID destinationAreaId,
                                        BigDecimal chargeableWeight, UUID businessCustomerId,
                                        BigDecimal declaredValue, TariffInput tariff);

    /** The shipment details only the zone tariff prices by. */
    record TariffInput(UUID serviceTypeId, UUID packageTypeId, Integer pieces, LocalDate bookingDate) {
    }
}
