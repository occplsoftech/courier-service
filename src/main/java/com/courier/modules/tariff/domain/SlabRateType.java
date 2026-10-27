package com.courier.modules.tariff.domain;

/**
 * How a weight slab prices. {@code FIXED}: the slab's rate is the freight. {@code PER_EXTRA_KG}: the rate of the fixed slab
 * below it, plus {@code rate} for each step (normally 1 kg) or part of a step above that fixed slab's upper weight.
 */
public enum SlabRateType {
    FIXED,
    PER_EXTRA_KG
}
