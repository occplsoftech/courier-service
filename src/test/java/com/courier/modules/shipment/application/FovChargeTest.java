package com.courier.modules.shipment.application;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class FovChargeTest {

    @Test
    void zeroPointTwoPercentOfTheValueButNeverBelowOneHundred() {
        assertThat(ShipmentServiceImpl.fov(new BigDecimal("10000"))).isEqualByComparingTo("100");   // 0.2% = 20
        assertThat(ShipmentServiceImpl.fov(new BigDecimal("50000"))).isEqualByComparingTo("100");   // 0.2% = 100
        assertThat(ShipmentServiceImpl.fov(new BigDecimal("125000"))).isEqualByComparingTo("250");
        assertThat(ShipmentServiceImpl.fov(null)).isEqualByComparingTo("100");
    }

    @Test
    void theInvoiceValueElseTheDeclaredValue() {
        assertThat(ShipmentServiceImpl.fovValue(new BigDecimal("90000"), new BigDecimal("5000"))).isEqualByComparingTo("90000");
        assertThat(ShipmentServiceImpl.fovValue(null, new BigDecimal("5000"))).isEqualByComparingTo("5000");
        assertThat(ShipmentServiceImpl.fovValue(BigDecimal.ZERO, new BigDecimal("5000"))).isEqualByComparingTo("5000");
    }
}
