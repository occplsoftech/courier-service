package com.courier.modules.platformbookings.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CarrierLabelTest {

    @Test
    void occplDirectAndAggregators() {
        assertThat(CarrierLabel.of(null, null, null)).isEqualTo("OCCPL Network");
        assertThat(CarrierLabel.of("OCCPL_NETWORK", "OCCPL Network", "OCCPL SURFACE")).isEqualTo("OCCPL Network");
        assertThat(CarrierLabel.of("DELHIVERY", "Delhivery", "Surface")).isEqualTo("Delhivery");
        assertThat(CarrierLabel.of("SHIPYAARI", "Shipyaari", "DELHIVERY Economy (Surface)")).isEqualTo("Delhivery via Shipyaari");
        assertThat(CarrierLabel.of("SHIPROCKET", "Shiprocket", "Bluedart Air")).isEqualTo("Blue Dart via Shiprocket");
        assertThat(CarrierLabel.of("SHIPROCKET", "Shiprocket", "Blue Dart Surface 2kg")).isEqualTo("Blue Dart via Shiprocket");
        assertThat(CarrierLabel.of("SHIPROCKET", "Shiprocket", "Xpressbees 1kg")).isEqualTo("Xpressbees via Shiprocket");
        assertThat(CarrierLabel.of("SHIPYAARI", "Shipyaari", "Mystery Express")).isEqualTo("Shipyaari");
        assertThat(CarrierLabel.of("BLUEDART", null, null)).isEqualTo("BLUEDART");
    }

    @Test
    void filterPatternMatchesTheSameServicesAsTheLabel() {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(CarrierLabel.patternFor("Blue Dart"));
        assertThat(p.matcher("bluedart air").find()).isTrue();
        assertThat(p.matcher("blue dart surface").find()).isTrue();
        assertThat(CarrierLabel.patternFor("Weird; DROP")).isEqualTo("weird drop");
    }
}
