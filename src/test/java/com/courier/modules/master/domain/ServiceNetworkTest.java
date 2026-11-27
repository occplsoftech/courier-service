package com.courier.modules.master.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Which pincode coverage a booking is checked against (V171, V185). */
class ServiceNetworkTest {

    @Test
    @DisplayName("parcels use the service's network, SURFACE when it has none; documents use DOCUMENT except on PREMIUM")
    void networkOfBooking() {
        assertThat(ServiceNetwork.of(false, null)).isEqualTo(ServiceNetwork.SURFACE);
        assertThat(ServiceNetwork.of(false, ServiceNetwork.AIR)).isEqualTo(ServiceNetwork.AIR);
        assertThat(ServiceNetwork.of(false, ServiceNetwork.AIR_CARGO)).isEqualTo(ServiceNetwork.AIR_CARGO);
        assertThat(ServiceNetwork.of(false, ServiceNetwork.SURFACE_CARGO)).isEqualTo(ServiceNetwork.SURFACE_CARGO);
        assertThat(ServiceNetwork.of(true, ServiceNetwork.SURFACE_CARGO)).isEqualTo(ServiceNetwork.DOCUMENT);
        assertThat(ServiceNetwork.of(true, ServiceNetwork.PREMIUM)).isEqualTo(ServiceNetwork.PREMIUM);
        assertThat(ServiceNetwork.of(false, ServiceNetwork.PREMIUM)).isEqualTo(ServiceNetwork.PREMIUM);
    }

    @Test
    @DisplayName("a pincode stores and returns cargo and premium coverage")
    void pincodeCoverage() {
        Pincode p = new Pincode();
        p.setCoverage(ServiceNetwork.AIR_CARGO, new NetworkCoverage(true, true, 96));
        p.setCoverage(ServiceNetwork.PREMIUM, new NetworkCoverage(false, false, null));
        assertThat(p.coverage(ServiceNetwork.AIR_CARGO)).isEqualTo(new NetworkCoverage(true, true, 96));
        assertThat(p.coverage(ServiceNetwork.PREMIUM).serviceableOr(true)).isFalse();
        assertThat(p.coverage(ServiceNetwork.SURFACE_CARGO)).isEqualTo(new NetworkCoverage(null, null, null));
    }
}
