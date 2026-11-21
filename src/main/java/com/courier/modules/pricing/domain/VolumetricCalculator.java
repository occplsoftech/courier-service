package com.courier.modules.pricing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Volumetric ("dimensional") weight: {@code (length x width x height) / divisor}, the
 * industry proxy for how much space a light-but-bulky parcel occupies on a vehicle.
 * Dimensions are in centimetres, the divisor in cm3-per-kg
 * ({@link com.courier.modules.pricing.application.PricingProperties}'s deployment default
 * is 5000, the road-freight convention most Indian couriers use).
 */
public final class VolumetricCalculator {

    private VolumetricCalculator() {
    }

    /** The service type's own divisor when it has one (Air and Surface commonly differ),
     *  else the deployment default. */
    public static BigDecimal divisor(Integer serviceTypeDivisor, BigDecimal fallback) {
        return serviceTypeDivisor != null && serviceTypeDivisor > 0
                ? BigDecimal.valueOf(serviceTypeDivisor) : fallback;
    }

    /**
     * Zero when any dimension is missing — a shipment with no captured dimensions is
     * priced on actual weight alone, not refused, since dimensions are optional input.
     */
    public static BigDecimal calculate(BigDecimal length, BigDecimal width, BigDecimal height,
                                       BigDecimal divisor) {
        if (length == null || width == null || height == null) {
            return BigDecimal.ZERO.setScale(WeightCalculator.WEIGHT_SCALE, RoundingMode.HALF_UP);
        }
        return length.multiply(width).multiply(height)
                .divide(divisor, WeightCalculator.WEIGHT_SCALE, RoundingMode.HALF_UP);
    }
}
