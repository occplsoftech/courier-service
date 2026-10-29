package com.courier.modules.customerportal.integrations.application;

import com.courier.modules.customerportal.integrations.domain.CourierIntegration;
import com.courier.modules.customerportal.integrations.domain.CourierIntegrationRepository;
import com.courier.modules.customerportal.rates.application.CustomerPortalProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Which credentials the courier adapters use for a company. No authorization here: callers are the adapters, which get the
 * company from the request they are serving, never from the browser.
 *
 * <p>A company's own saved integration wins — including "disabled", which switches that courier off for the company even
 * when the server has environment settings. A company with no saved integration uses the server's environment settings
 * ({@code app.customer-portal.*}). The base URL always comes from the environment so no company can redirect the server's
 * outbound calls. An unreadable stored secret counts as "not connected" and is logged, never thrown.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CourierCredentials {

    private final CourierIntegrationRepository repository;
    private final CourierSecretsCodec codec;
    private final CustomerPortalProperties properties;

    public CustomerPortalProperties.Shiprocket shiprocket(UUID companyId) {
        CustomerPortalProperties.Shiprocket env = properties.getShiprocket();
        Resolved row = resolved(companyId, CourierCatalog.SHIPROCKET);
        if (row.useEnvironment()) {
            return env;
        }
        CustomerPortalProperties.Shiprocket c = new CustomerPortalProperties.Shiprocket();
        c.setBaseUrl(env.getBaseUrl());
        c.setEnabled(row.enabled());
        c.setEmail(row.value("email"));
        c.setPassword(row.value("password"));
        c.setPickupLocation(row.value("pickupLocation"));
        return c;
    }

    public CustomerPortalProperties.Delhivery delhivery(UUID companyId) {
        CustomerPortalProperties.Delhivery env = properties.getDelhivery();
        Resolved row = resolved(companyId, CourierCatalog.DELHIVERY);
        if (row.useEnvironment()) {
            return env;
        }
        CustomerPortalProperties.Delhivery c = new CustomerPortalProperties.Delhivery();
        c.setBaseUrl(env.getBaseUrl());
        c.setEnabled(row.enabled());
        c.setToken(row.value("token"));
        c.setPickupLocation(row.value("pickupLocation"));
        return c;
    }

    public CustomerPortalProperties.Shipyaari shipyaari(UUID companyId) {
        CustomerPortalProperties.Shipyaari env = properties.getShipyaari();
        Resolved row = resolved(companyId, CourierCatalog.SHIPYAARI);
        if (row.useEnvironment()) {
            return env;
        }
        CustomerPortalProperties.Shipyaari c = new CustomerPortalProperties.Shipyaari();
        c.setBaseUrl(env.getBaseUrl());
        c.setEnabled(row.enabled());
        c.setEmail(row.value("email"));
        c.setPassword(row.value("password"));
        return c;
    }

    public CustomerPortalProperties.Skart skart(UUID companyId) {
        CustomerPortalProperties.Skart env = properties.getSkart();
        Resolved row = resolved(companyId, CourierCatalog.SKART);
        if (row.useEnvironment()) {
            return env;
        }
        CustomerPortalProperties.Skart c = new CustomerPortalProperties.Skart();
        c.setBaseUrl(env.getBaseUrl());
        c.setEnabled(row.enabled());
        c.setUserName(row.value("userName"));
        c.setPassword(row.value("password"));
        return c;
    }

    private Resolved resolved(UUID companyId, String code) {
        if (companyId == null) {
            return Resolved.ENVIRONMENT;
        }
        try {
            Optional<CourierIntegration> row = repository.findByProvider(companyId, code);
            if (row.isEmpty()) {
                return Resolved.ENVIRONMENT;
            }
            CourierIntegration i = row.get();
            Map<String, String> secrets = i.getSecretsEncrypted() == null ? Map.of() : codec.decryptSecrets(i.getSecretsEncrypted());
            return new Resolved(false, i.isEnabled(), codec.readSettings(i.getSettingsJson()), secrets);
        } catch (DataAccessException | IllegalStateException e) {
            log.warn("Courier integration {} of company {} cannot be read ({}); treating it as not connected",
                    code, companyId, e.getClass().getSimpleName());
            return Resolved.UNUSABLE;
        }
    }

    /** {@code environment}: the company has no saved integration. {@link #UNUSABLE}: it has one that cannot be read. */
    private record Resolved(boolean environment, boolean enabled, Map<String, String> settings, Map<String, String> secrets) {
        static final Resolved ENVIRONMENT = new Resolved(true, false, Map.of(), Map.of());
        static final Resolved UNUSABLE = new Resolved(false, false, Map.of(), Map.of());

        boolean useEnvironment() {
            return environment;
        }

        String value(String key) {
            String v = settings.get(key);
            return v != null ? v : secrets.get(key);
        }
    }
}
