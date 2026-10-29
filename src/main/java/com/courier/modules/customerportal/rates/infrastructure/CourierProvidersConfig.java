package com.courier.modules.customerportal.rates.infrastructure;

import com.courier.modules.customerportal.integrations.application.CourierCredentials;
import com.courier.modules.customerportal.rates.application.CustomerPortalProperties;
import com.courier.modules.customerportal.rates.domain.CourierProviderAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * External courier adapters. Each is always registered; without credentials it reports "not connected"
 * rather than disappearing, so the comparison page can say so. Credentials come from
 * {@code app.customer-portal.*} (environment variables, never the database or the browser).
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(CustomerPortalProperties.class)
public class CourierProvidersConfig {

    @Bean
    public CourierProviderAdapter shiprocketAdapter(RestClient.Builder builder, CustomerPortalProperties props,
                                                    CourierCredentials credentials) {
        log.info("Customer portal: Shiprocket {} (companies can also connect their own account)",
                props.getShiprocket().configured() ? "connected by the server settings" : "not set in the server settings");
        return new ShiprocketAdapter(withTimeouts(builder, props), props.getShiprocket(), credentials::shiprocket);
    }

    @Bean
    public CourierProviderAdapter delhiveryAdapter(RestClient.Builder builder, CustomerPortalProperties props,
                                                   CourierCredentials credentials) {
        log.info("Customer portal: Delhivery {} (companies can also connect their own account)",
                props.getDelhivery().configured() ? "connected by the server settings" : "not set in the server settings");
        return new DelhiveryAdapter(withTimeouts(builder, props), props.getDelhivery(), credentials::delhivery,
                props.getVolumetricDivisor());
    }

    @Bean
    public CourierProviderAdapter shipyaariAdapter(RestClient.Builder builder, CustomerPortalProperties props,
                                                   CourierCredentials credentials) {
        log.info("Customer portal: Shipyaari {} (companies can also connect their own account)",
                props.getShipyaari().configured() ? "connected by the server settings" : "not set in the server settings");
        return new ShipyaariAdapter(withTimeouts(builder, props), props.getShipyaari(), credentials::shipyaari);
    }

    @Bean
    public CourierProviderAdapter skartAdapter(RestClient.Builder builder, CustomerPortalProperties props,
                                               CourierCredentials credentials) {
        log.info("Customer portal: sKart international {} (companies can also connect their own account)",
                props.getSkart().configured() ? "connected by the server settings" : "not set in the server settings");
        return new SkartAdapter(withTimeouts(builder, props), props.getSkart(), credentials::skart);
    }

    @Bean
    public CourierProviderAdapter blueDartAdapter() {
        return new NotConfiguredAdapter("BLUEDART", "Blue Dart");
    }

    @Bean
    public CourierProviderAdapter ekartAdapter() {
        return new NotConfiguredAdapter("EKART", "Ekart");
    }

    /** Asks external providers in parallel; bounded so a burst of comparisons cannot spawn threads without limit. */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService courierRateExecutor() {
        return Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "courier-rates");
            t.setDaemon(true);
            return t;
        });
    }

    private static RestClient.Builder withTimeouts(RestClient.Builder builder, CustomerPortalProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int ms = (int) props.getProviderTimeout().toMillis();
        factory.setConnectTimeout(ms);
        factory.setReadTimeout(ms);
        return builder.clone().requestFactory(factory);
    }
}
