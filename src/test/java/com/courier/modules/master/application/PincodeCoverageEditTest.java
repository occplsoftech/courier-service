package com.courier.modules.master.application;

import com.courier.modules.master.application.command.PincodeCommand;
import com.courier.modules.master.domain.NetworkCoverage;
import com.courier.modules.master.domain.Pincode;
import com.courier.modules.master.domain.ServiceNetwork;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PincodeCoverageEditTest {

    private static PincodeCommand edit(NetworkCoverage airCargo, NetworkCoverage surfaceCargo, NetworkCoverage premium) {
        return new PincodeCommand(null, "Baramati", null, 0, UUID.randomUUID(), true, true, true, true, "WEST", false, null,
                null, null, null, airCargo, surfaceCargo, premium, 0L);
    }

    @Test
    @DisplayName("the pincode edit turns Premium on (413102 Baramati) with its ODA and TAT")
    void premiumOn() {
        Pincode p = new Pincode();
        p.setCoverage(ServiceNetwork.PREMIUM, new NetworkCoverage(false, null, null));

        PincodeServiceImpl.applyCargoAndPremium(p, edit(null, null, new NetworkCoverage(true, false, 24)));

        assertThat(p.coverage(ServiceNetwork.PREMIUM)).isEqualTo(new NetworkCoverage(true, false, 24));
    }

    @Test
    @DisplayName("a client that leaves the cargo / premium coverage out keeps what the pincode has")
    void leftOutKeepsCoverage() {
        Pincode p = new Pincode();
        p.setCoverage(ServiceNetwork.AIR_CARGO, new NetworkCoverage(true, true, 72));
        p.setCoverage(ServiceNetwork.SURFACE_CARGO, new NetworkCoverage(true, false, 120));
        p.setCoverage(ServiceNetwork.PREMIUM, new NetworkCoverage(true, false, 24));

        PincodeServiceImpl.applyCargoAndPremium(p, edit(null, null, null));

        assertThat(p.coverage(ServiceNetwork.AIR_CARGO)).isEqualTo(new NetworkCoverage(true, true, 72));
        assertThat(p.coverage(ServiceNetwork.SURFACE_CARGO)).isEqualTo(new NetworkCoverage(true, false, 120));
        assertThat(p.coverage(ServiceNetwork.PREMIUM)).isEqualTo(new NetworkCoverage(true, false, 24));
    }

    @Test
    @DisplayName("turning Premium off clears its ODA and TAT")
    void premiumOff() {
        Pincode p = new Pincode();
        p.setCoverage(ServiceNetwork.PREMIUM, new NetworkCoverage(true, false, 24));

        PincodeServiceImpl.applyCargoAndPremium(p, edit(null, null, new NetworkCoverage(false, null, null)));

        assertThat(p.coverage(ServiceNetwork.PREMIUM).serviceable()).isFalse();
    }
}
