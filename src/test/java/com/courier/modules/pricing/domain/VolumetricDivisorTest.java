package com.courier.modules.pricing.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class VolumetricDivisorTest {

    private static final BigDecimal DEFAULT = new BigDecimal(5000);

    @Test
    void usesTheServiceTypesDivisorWhenSet() {
        assertThat(VolumetricCalculator.divisor(4000, DEFAULT)).isEqualByComparingTo("4000");
        BigDecimal surface = VolumetricCalculator.calculate(new BigDecimal("40"), new BigDecimal("40"),
                new BigDecimal("40"), VolumetricCalculator.divisor(4000, DEFAULT));
        assertThat(surface).isEqualByComparingTo("16.000");
    }

    @Test
    void fallsBackToTheDefaultWhenTheServiceHasNone() {
        assertThat(VolumetricCalculator.divisor(null, DEFAULT)).isEqualByComparingTo("5000");
        assertThat(VolumetricCalculator.divisor(0, DEFAULT)).isEqualByComparingTo("5000");
    }
}
