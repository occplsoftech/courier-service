package com.courier.modules.pricing.domain;

import java.math.BigDecimal;

/**
 * Chargeable weight — {@code MAX(actualWeight, volumetricWeight)}: whichever is higher, the
 * actual weight or the L×W×H volumetric weight. No company-wide minimum is applied (a
 * rate card's own minimum, where it has one, is part of that tariff and applied there)
 * — the weight every downstream step (rate slab matching, freight) prices against. This
 * is the courier-industry meaning of the term; it is a different quantity from
 * {@code rate.application.RateCalculationResult#chargeableWeight}, which is the weight
 * <i>billed after</i> slab/overage arithmetic on top of this one.
 */
public final class ChargeableWeightCalculator {

    private ChargeableWeightCalculator() {
    }

    public static BigDecimal calculate(BigDecimal actualWeight, BigDecimal volumetricWeight) {
        return actualWeight.max(volumetricWeight);
    }
}
