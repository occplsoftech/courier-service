package com.courier.shared.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * CORS settings, bound from {@code app.cors.*}.
 *
 * <p>Origins are an explicit allowlist per environment. A wildcard origin combined
 * with {@code allowCredentials} is rejected by the browser anyway, and a wildcard
 * without it still lets any site read responses — so production must list real hosts.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

    private List<String> allowedOrigins = List.of("http://localhost:3000", "http://localhost:5173");

    /**
     * Extra origins allowed on the public tracking API ({@code /api/v1/track/**}) and the public rates
     * ({@code /api/v1/public/rates}) only, e.g. the public website.
     * Kept apart from {@link #allowedOrigins} so such a site never gains access to the rest of the API.
     */
    private List<String> trackingOrigins = List.of();

    private boolean allowCredentials = false;
}
