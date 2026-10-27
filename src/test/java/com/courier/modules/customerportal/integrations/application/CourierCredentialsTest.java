package com.courier.modules.customerportal.integrations.application;

import com.courier.modules.customerportal.rates.application.CustomerPortalProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class CourierCredentialsTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    private final CourierIntegrationFixtures fx = new CourierIntegrationFixtures();
    private final CustomerPortalProperties env = new CustomerPortalProperties();
    private CourierCredentials credentials;

    @BeforeEach
    void setUp() {
        env.getShiprocket().setEnabled(true);
        env.getShiprocket().setEmail("env@server.test");
        env.getShiprocket().setPassword("env-pass");
        env.getShiprocket().setBaseUrl("https://sr.env");
        credentials = new CourierCredentials(fx.repository, fx.codec, env);
    }

    @Test
    @DisplayName("a company with no saved account uses the server's settings")
    void environmentFallback() {
        assertThat(credentials.shiprocket(COMPANY)).isSameAs(env.getShiprocket());
        assertThat(credentials.shiprocket(null)).isSameAs(env.getShiprocket());
    }

    @Test
    @DisplayName("a company's own saved account is used for that company only, with the server's base URL")
    void ownAccount() {
        fx.saved(COMPANY, "SHIPROCKET", true, Map.of("email", "own@shop.test", "pickupLocation", "PUNE-1"),
                Map.of("password", "own-pass"));

        CustomerPortalProperties.Shiprocket own = credentials.shiprocket(COMPANY);

        assertThat(own.configured()).isTrue();
        assertThat(own.getEmail()).isEqualTo("own@shop.test");
        assertThat(own.getPassword()).isEqualTo("own-pass");
        assertThat(own.getPickupLocation()).isEqualTo("PUNE-1");
        assertThat(own.getBaseUrl()).isEqualTo("https://sr.env");
        assertThat(credentials.shiprocket(OTHER)).isSameAs(env.getShiprocket());
    }

    @Test
    @DisplayName("Shipyaari: a company's own account is used for that company only; others use the server's settings")
    void shipyaariOwnAccount() {
        env.getShipyaari().setBaseUrl("https://sy.env");
        fx.saved(COMPANY, "SHIPYAARI", true, Map.of("email", "own@shop.test"), Map.of("password", "own-pass"));

        CustomerPortalProperties.Shipyaari own = credentials.shipyaari(COMPANY);

        assertThat(own.configured()).isTrue();
        assertThat(own.getEmail()).isEqualTo("own@shop.test");
        assertThat(own.getPassword()).isEqualTo("own-pass");
        assertThat(own.getBaseUrl()).as("a company cannot redirect the server's calls").isEqualTo("https://sy.env");
        assertThat(credentials.shipyaari(OTHER)).isSameAs(env.getShipyaari());
        assertThat(credentials.shipyaari(null)).isSameAs(env.getShipyaari());
    }

    @Test
    @DisplayName("Shipyaari is in the catalog with an e-mail and a secret password, and is switched off by default")
    void shipyaariCatalog() {
        CourierCatalog.Provider p = CourierCatalog.find("shipyaari").orElseThrow();

        assertThat(p.available()).isTrue();
        assertThat(p.field("email").orElseThrow().secret()).isFalse();
        assertThat(p.field("password").orElseThrow().secret()).isTrue();
        assertThat(new CustomerPortalProperties().getShipyaari().configured()).isFalse();
    }

    @Test
    @DisplayName("a company that disabled a courier does not fall back to the server's account")
    void disabledStaysOff() {
        fx.saved(COMPANY, "SHIPROCKET", false, Map.of("email", "own@shop.test"), Map.of("password", "own-pass"));

        assertThat(credentials.shiprocket(COMPANY).configured()).isFalse();
    }

    @Test
    @DisplayName("a secret that no longer decrypts means 'not connected', never an exception")
    void unreadableSecret() {
        fx.saved(COMPANY, "DELHIVERY", true, Map.of("pickupLocation", "PUNE-1"), Map.of("token", "tok"));
        CourierCredentials other = new CourierCredentials(fx.repository,
                new CourierIntegrationFixtures().codec /* a different key */, env);

        assertThat(other.delhivery(COMPANY).configured()).isFalse();
    }

    @Test
    @DisplayName("a database failure means 'not connected', never an exception")
    void databaseDown() {
        when(fx.repository.findByProvider(any(), any())).thenThrow(new DataAccessResourceFailureException("down"));

        assertThat(credentials.delhivery(COMPANY).configured()).isFalse();
    }
}
