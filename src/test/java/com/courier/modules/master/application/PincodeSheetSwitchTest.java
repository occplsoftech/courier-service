package com.courier.modules.master.application;

import com.courier.modules.master.domain.NetworkCoverage;
import com.courier.modules.master.domain.ServiceNetwork;
import com.courier.shared.exception.BusinessRuleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Pincode Master Sheet's Premium / Air Cargo / Surface Cargo switches. */
class PincodeSheetSwitchTest {

    @Test
    @DisplayName("switching on keeps the network's ODA and TAT; an unset ODA reads as not ODA")
    void onKeepsOdaAndTat() {
        assertThat(PincodeServiceImpl.switched(new NetworkCoverage(false, true, 48), true)).isEqualTo(new NetworkCoverage(true, true, 48));
        assertThat(PincodeServiceImpl.switched(new NetworkCoverage(null, null, null), true)).isEqualTo(new NetworkCoverage(true, false, null));
    }

    @Test
    @DisplayName("switching off changes only on/off")
    void offOnlyFlag() {
        assertThat(PincodeServiceImpl.switched(new NetworkCoverage(true, false, 24), false)).isEqualTo(new NetworkCoverage(false, false, 24));
    }

    @Test
    @DisplayName("only Premium, Air Cargo and Surface Cargo can be switched from the sheet")
    void onlyThreeNetworks() {
        for (ServiceNetwork n : new ServiceNetwork[]{ServiceNetwork.PREMIUM, ServiceNetwork.AIR_CARGO, ServiceNetwork.SURFACE_CARGO}) {
            assertThatCode(() -> PincodeServiceImpl.requireSwitchable(n)).doesNotThrowAnyException();
        }
        for (ServiceNetwork n : new ServiceNetwork[]{ServiceNetwork.DOCUMENT, ServiceNetwork.SURFACE, ServiceNetwork.AIR, null}) {
            assertThatThrownBy(() -> PincodeServiceImpl.requireSwitchable(n)).isInstanceOf(BusinessRuleException.class);
        }
    }
}
