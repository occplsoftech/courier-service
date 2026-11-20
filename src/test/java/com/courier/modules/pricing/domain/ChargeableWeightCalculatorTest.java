package com.courier.modules.pricing.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ChargeableWeightCalculatorTest {

    @Test
    @DisplayName("actual weight wins when heavier than volumetric")
    void actualWeightWinsWhenHeavier() {
        BigDecimal result = ChargeableWeightCalculator.calculate(
                new BigDecimal("5.000"), new BigDecimal("1.200"));

        assertThat(result).isEqualByComparingTo("5.000");
    }

    @Test
    @DisplayName("volumetric weight wins when the parcel is bulky but light")
    void volumetricWeightWinsWhenBulkier() {
        BigDecimal result = ChargeableWeightCalculator.calculate(
                new BigDecimal("1.000"), new BigDecimal("4.500"));

        assertThat(result).isEqualByComparingTo("4.500");
    }

    @Test
    @DisplayName("equal weights: either is chargeable")
    void equalWeights() {
        BigDecimal result = ChargeableWeightCalculator.calculate(
                new BigDecimal("3.000"), new BigDecimal("3.000"));

        assertThat(result).isEqualByComparingTo("3.000");
    }

    @Test
    @DisplayName("light parcel: no company minimum, prices on its own weight")
    void noCompanyMinimum() {
        BigDecimal result = ChargeableWeightCalculator.calculate(
                new BigDecimal("0.500"), new BigDecimal("0.200"));

        assertThat(result).isEqualByComparingTo("0.500");
    }
}
