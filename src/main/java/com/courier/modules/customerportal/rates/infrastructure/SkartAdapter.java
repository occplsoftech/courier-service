package com.courier.modules.customerportal.rates.infrastructure;

import com.courier.modules.customerportal.rates.application.CustomerPortalProperties;
import com.courier.modules.customerportal.rates.domain.ChargeComponent;
import com.courier.modules.customerportal.rates.domain.ChargeLine;
import com.courier.modules.customerportal.rates.domain.ConnectionTest;
import com.courier.modules.customerportal.rates.domain.CourierProviderAdapter;
import com.courier.modules.customerportal.rates.domain.InternationalRateProvider;
import com.courier.modules.customerportal.rates.domain.ProviderRate;
import com.courier.modules.customerportal.rates.domain.ProviderResult;
import com.courier.modules.customerportal.rates.domain.RateRequest;
import com.courier.modules.customerportal.rates.domain.ShipmentRef;
import com.courier.modules.customerportal.rates.domain.TrackingEvent;
import com.courier.modules.customerportal.rates.domain.TrackingResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * sKart international ({@code devapiv2.skart-express.com/api/v1/booking}, OpenAPI "sKart APIs" 1.0.0): one franchisee
 * account prices and books DHL, FedEx, UPS, Aramex, Skynet and sKart's own services abroad. The account's user name and
 * password travel in every request body (or query, for GETs); there is no token.
 *
 * <p>Built against the documentation and sKart's development system (answers recorded 2026-10-29):
 * <ul>
 *   <li>{@code POST /rate-calculator} (booking_type 1): {@code data[]} of products, each with {@code product_name},
 *       {@code parent_vendor}, {@code tat_days}, {@code charges[]} (pre-tax {@code charge_amount} plus
 *       {@code igst/cgst/sgst_amount}) and {@code grand_total_with_gst}. The charge lines here add up to that total.</li>
 *   <li>{@code GET /country/{name}} (blank name: all), {@code POST /track-shipment} ({@code track_data[]}, mostly oldest first).</li>
 * </ul>
 * International only: the domestic comparison does not ask it ({@link #domestic()}). Booking is not built yet - its
 * answer is not documented, and DHL bookings need an OTP sent to the sender's mobile.
 */
@Slf4j
public class SkartAdapter implements CourierProviderAdapter, InternationalRateProvider {

    public static final String CODE = "SKART";
    private static final Duration COUNTRIES_KEPT = Duration.ofHours(12);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient http;
    private final CustomerPortalProperties.Skart env;
    private final Function<UUID, CustomerPortalProperties.Skart> settings;
    private final Map<String, CachedCountries> countries = new ConcurrentHashMap<>();

    private record CachedCountries(List<Country> list, Instant until) {
    }

    public SkartAdapter(RestClient.Builder builder, CustomerPortalProperties.Skart env,
                        Function<UUID, CustomerPortalProperties.Skart> settings) {
        this.env = env;
        this.settings = settings;
        this.http = builder.baseUrl(env.getBaseUrl()).build();
    }

    @Override public String code() { return CODE; }
    @Override public String displayName() { return "sKart International"; }
    @Override public boolean external() { return true; }
    @Override public boolean domestic() { return false; }
    @Override public boolean configured() { return env.configured(); }
    @Override public boolean configured(UUID companyId) { return settings.apply(companyId).configured(); }

    /** Never asked: sKart is priced through {@link #internationalRates}. */
    @Override
    public ProviderResult getRates(RateRequest request) {
        return ProviderResult.unavailable(CODE, displayName(), "sKart prices international shipments only.");
    }

    // ------------------------------------------------------------------ rates

    @Override
    public ProviderResult internationalRates(Request r) {
        CustomerPortalProperties.Skart config = settings.apply(r.companyId());
        if (!config.configured()) {
            return ProviderResult.unavailable(CODE, displayName(), displayName() + " is not connected yet.");
        }
        Map<String, Object> body = credentials(config);
        body.put("booking_type", 1);
        body.put("origin_pincode", r.originPincode());
        body.put("destination_country", r.destinationCountry());
        body.put("destination_pincode", r.destinationPostcode() == null ? "" : r.destinationPostcode());
        body.put("shipment_type", switch (r.kind()) { case PARCEL -> 1; case DOCUMENT -> 2; case COMMERCIAL -> 4; });
        body.put("weight", r.weightKg());
        if (r.kind() != Kind.DOCUMENT) {
            body.put("quantity", Math.max(1, r.pieces()));
            body.put("length", r.maxLengthCm() == null ? 1 : r.maxLengthCm().setScale(0, java.math.RoundingMode.CEILING).intValue());
        }
        try {
            JsonNode answer = post("/rate-calculator", body);
            return ProviderResult.available(CODE, displayName(), rates(answer));
        } catch (RestClientResponseException e) {
            return ProviderResult.unavailable(CODE, displayName(), refusal(e, "sKart could not price this shipment."));
        } catch (RestClientException e) {
            log.warn("sKart rate call failed: {}", e.getClass().getSimpleName());
            return ProviderResult.unavailable(CODE, displayName(), "sKart could not be reached. Please try again.");
        }
    }

    /** sKart's products as rates: pre-tax charge lines (zero lines dropped) plus one tax line for their IGST/CGST/SGST. */
    static List<ProviderRate> rates(JsonNode answer) {
        List<ProviderRate> out = new ArrayList<>();
        for (JsonNode p : answer.path("data")) {
            String product = text(p, "product_name");
            if (product == null) continue;
            List<ChargeLine> lines = new ArrayList<>();
            BigDecimal tax = BigDecimal.ZERO;
            for (JsonNode c : p.path("charges")) {
                BigDecimal amount = num(c.path("charge_amount"));
                String name = text(c, "charge_name");
                if (amount.signum() != 0) {
                    lines.add(new ChargeLine(text(c, "charge_id"), tidy(name), component(name), amount));
                }
                tax = tax.add(num(c.path("igst_amount"))).add(num(c.path("cgst_amount"))).add(num(c.path("sgst_amount")));
            }
            if (lines.isEmpty()) continue;
            if (tax.signum() != 0) {
                lines.add(new ChargeLine("GST", "GST", ChargeComponent.TAX, tax));
            }
            int tat = p.path("tat_days").asInt(0);
            String vendor = text(p, "parent_vendor");
            String name = vendor == null || product.toUpperCase(Locale.ROOT).startsWith(vendor.toUpperCase(Locale.ROOT).split(" ")[0])
                    ? product : product + " (" + vendor + ")";
            out.add(new ProviderRate(product, name, "AIR", tat > 0 ? tat : null, tat > 0 ? tat : null, null,
                    List.copyOf(lines), null, null));
        }
        return out;
    }

    private static ChargeComponent component(String name) {
        String n = name == null ? "" : name.toUpperCase(Locale.ROOT);
        if (n.contains("FREIGHT")) return ChargeComponent.FREIGHT;
        if (n.contains("FUEL")) return ChargeComponent.FUEL_SURCHARGE;
        if (n.contains("REMOTE") || n.contains("OUTER") || n.contains("ODA")) return ChargeComponent.REMOTE_AREA;
        return ChargeComponent.OTHER;
    }

    // ------------------------------------------------------------------ countries

    @Override
    public List<Country> countries(UUID companyId) {
        CustomerPortalProperties.Skart config = settings.apply(companyId);
        if (!config.configured()) {
            return List.of();
        }
        String key = config.getBaseUrl() + "|" + config.getUserName();
        CachedCountries cached = countries.get(key);
        if (cached != null && cached.until().isAfter(Instant.now())) {
            return cached.list();
        }
        try {
            JsonNode answer = get("/country/", config);
            List<Country> list = new ArrayList<>();
            for (JsonNode c : answer.path("data")) {
                if (c.path("is_active").asInt(1) != 1 || text(c, "country_name") == null) continue;
                list.add(new Country(c.path("country_id").asInt(), text(c, "country_code"), text(c, "country_name").trim(),
                        text(c, "region") == null ? null : text(c, "region").trim(), c.path("pincode_avail").asInt(0) == 1));
            }
            list.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
            List<Country> frozen = Collections.unmodifiableList(list);
            countries.put(key, new CachedCountries(frozen, Instant.now().plus(COUNTRIES_KEPT)));
            return frozen;
        } catch (RestClientException e) {
            log.warn("sKart country list failed: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    // ------------------------------------------------------------------ connection test, tracking

    /** One cheap authenticated read (the country INDIA): 200 means sKart accepts the user name and password. */
    @Override
    public ConnectionTest testConnection(UUID companyId) {
        CustomerPortalProperties.Skart config = settings.apply(companyId);
        if (!config.configured()) {
            return ConnectionTest.failed("sKart is switched off or its user name or password is missing.");
        }
        try {
            get("/country/INDIA", config);
            return ConnectionTest.ok("Connected. sKart accepted the user name and password.");
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            return ConnectionTest.failed(status == 400 || status == 401 || status == 403
                    ? "sKart did not accept the user name and password (live and development accounts differ)."
                    : "sKart answered with an error (HTTP " + status + "). Please try again later.");
        } catch (RestClientException e) {
            return ConnectionTest.failed("sKart could not be reached. Please try again later.");
        }
    }

    /** {@code POST /track-shipment}: {@code track_data[]} returned newest first by time. */
    @Override
    public TrackingResult track(ShipmentRef ref) {
        CustomerPortalProperties.Skart config = settings.apply(ref.companyId());
        if (!config.configured()) {
            return TrackingResult.unavailable("sKart is not connected, so tracking is not available.");
        }
        if (ref.awbNumber() == null || ref.awbNumber().isBlank()) {
            return TrackingResult.unavailable("Tracking starts once sKart issues the AWB number.");
        }
        Map<String, Object> body = credentials(config);
        body.put("awb", ref.awbNumber());
        try {
            return tracking(post("/track-shipment", body), ref.awbNumber());
        } catch (RestClientResponseException e) {
            return TrackingResult.unavailable(refusal(e, "sKart has no tracking for this shipment yet."));
        } catch (RestClientException e) {
            return TrackingResult.unavailable("sKart could not be reached. Please try again later.");
        }
    }

    static TrackingResult tracking(JsonNode answer, String awb) {
        JsonNode data = answer.path("data");
        List<TrackingEvent> events = new ArrayList<>();
        for (JsonNode t : data.path("track_data")) {
            String status = text(t, "status");
            String desc = text(t, "update_description");
            events.add(new TrackingEvent(ProviderTimes.parse(text(t, "date")), status, text(t, "update_location"),
                    desc == null || desc.equals(status) ? null : desc));
        }
        if (events.isEmpty()) {
            return TrackingResult.unavailable("sKart has no tracking updates for this shipment yet.");
        }
        // Mostly oldest first, but not always ("Shipment Booked" arrives last): newest first by time, undated ones last.
        Collections.reverse(events);
        events.sort(java.util.Comparator.comparing(TrackingEvent::at, java.util.Comparator.nullsFirst(java.util.Comparator.<Instant>naturalOrder()))
                .reversed());
        TrackingEvent latest = events.get(0);
        return TrackingResult.of(latest.status(), awb, latest.location(), events);
    }

    // ------------------------------------------------------------------ http

    private JsonNode post(String path, Map<String, Object> body) {
        String raw = http.post().uri(path).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(String.class);
        return read(raw);
    }

    private JsonNode get(String path, CustomerPortalProperties.Skart config) {
        String raw = http.get().uri(u -> u.path(path).queryParam("user_name", config.getUserName())
                        .queryParam("password", config.getPassword()).build())
                .accept(MediaType.APPLICATION_JSON).retrieve().body(String.class);
        return read(raw);
    }

    private static JsonNode read(String raw) {
        try {
            return JSON.readTree(raw == null ? "{}" : raw);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RestClientException("sKart's answer could not be read");
        }
    }

    private static Map<String, Object> credentials(CustomerPortalProperties.Skart config) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_name", config.getUserName());
        body.put("password", config.getPassword());
        return body;
    }

    /** sKart's own message for a refusal ("Invalid country name passed"), never the request (it holds the password). */
    private static String refusal(RestClientResponseException e, String fallback) {
        try {
            String msg = text(JSON.readTree(e.getResponseBodyAsString()), "message");
            if (msg != null && msg.length() <= 200) {
                return "sKart: " + msg;
            }
        } catch (Exception ignored) {
            // not JSON: use the fallback
        }
        return fallback;
    }

    private static String tidy(String s) {
        return s == null ? null : s.trim().replaceAll("\\s+", " ");
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }

    private static BigDecimal num(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(v.asText().trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
