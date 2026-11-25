package com.courier.shared.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The public website may call the tracking API, and nothing else. */
class TrackingCorsTest {

    private static final String APP = "https://app.example";
    private static final String WEBSITE = "https://pro.occpllogistics.com";

    private CorsConfigurationSource source;

    @BeforeEach
    void setUp() {
        CorsProperties properties = new CorsProperties();
        properties.setAllowedOrigins(List.of(APP));
        properties.setTrackingOrigins(List.of(WEBSITE));
        SecurityConfig config = Mockito.mock(SecurityConfig.class, Answers.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(config, "corsProperties", properties);
        source = config.corsConfigurationSource();
    }

    private CorsConfiguration cors(String path) {
        return source.getCorsConfiguration(new MockHttpServletRequest("POST", path));
    }

    @Test
    void websiteMayCallTracking() {
        assertThat(cors("/api/v1/track/X/verify").checkOrigin(WEBSITE)).isEqualTo(WEBSITE);
        assertThat(cors("/api/v1/track/X").checkOrigin(WEBSITE)).isEqualTo(WEBSITE);
    }

    @Test
    void websiteMayCheckRatesButNotSendOrders() {
        assertThat(cors("/api/v1/public/rates").checkOrigin(WEBSITE)).isEqualTo(WEBSITE);
        assertThat(cors("/api/v1/public/orders").checkOrigin(WEBSITE)).isNull();
    }

    @Test
    void websiteMayNotCallAnythingElse() {
        assertThat(cors("/api/v1/auth/login").checkOrigin(WEBSITE)).isNull();
    }

    @Test
    void appOriginKeepsFullAccessAndStrangersAreRefused() {
        assertThat(cors("/api/v1/auth/login").checkOrigin(APP)).isEqualTo(APP);
        assertThat(cors("/api/v1/track/X").checkOrigin(APP)).isEqualTo(APP);
        assertThat(cors("/api/v1/track/X").checkOrigin("https://evil.example")).isNull();
    }
}
