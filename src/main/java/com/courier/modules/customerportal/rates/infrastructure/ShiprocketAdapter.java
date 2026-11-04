package com.courier.modules.customerportal.rates.infrastructure;

import com.courier.modules.customerportal.rates.application.CustomerPortalProperties;
import com.courier.modules.customerportal.rates.domain.BookingOutcome;
import com.courier.modules.customerportal.rates.domain.BookingRequest;
import com.courier.modules.customerportal.rates.domain.CancelOutcome;
import com.courier.modules.customerportal.rates.domain.ChargeComponent;
import com.courier.modules.customerportal.rates.domain.ChargeLine;
import com.courier.modules.customerportal.rates.domain.ConnectionTest;
import com.courier.modules.customerportal.rates.domain.CourierProviderAdapter;
import com.courier.modules.customerportal.rates.domain.ProviderRate;
import com.courier.modules.customerportal.rates.domain.ProviderResult;
import com.courier.modules.customerportal.rates.domain.LabelResult;
import com.courier.modules.customerportal.rates.domain.RateRequest;
import com.courier.modules.customerportal.rates.domain.ShipmentRef;
import com.courier.modules.customerportal.rates.domain.TrackingEvent;
import com.courier.modules.customerportal.rates.domain.TrackingResult;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Shiprocket (aggregator): one call returns every courier Shiprocket can use for the lane, with rates.
 *
 * <p>API as documented by Shiprocket ({@code POST /v1/external/auth/login}, {@code GET
 * /v1/external/courier/serviceability/}). Field names are read defensively; <b>verify against the
 * account's own responses before enabling</b> (see MEMORY/modules/customer-portal.md). Shiprocket's
 * quoted amount is shown as Shiprocket returns it; no tax line is invented when it does not split one out.
 */
@Slf4j
public class ShiprocketAdapter implements CourierProviderAdapter {

    public static final String CODE = "SHIPROCKET";
    private static final Duration TOKEN_LIFETIME = Duration.ofDays(9); // Shiprocket tokens last 10 days

    private final RestClient http;
    /** The server's own settings (also the source of the base URL); {@link #settings} gives a company's. */
    private final CustomerPortalProperties.Shiprocket env;
    private final Function<UUID, CustomerPortalProperties.Shiprocket> settings;
    /** One login per distinct account (companies using the same account share it), keyed by a hash of its credentials. */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private record Session(String token, Instant expires) {
    }

    /** Every company uses the same settings. */
    public ShiprocketAdapter(RestClient.Builder builder, CustomerPortalProperties.Shiprocket config) {
        this(builder, config, companyId -> config);
    }

    /** {@code settings} answers the credentials of one company (its own saved account, else the server's). */
    public ShiprocketAdapter(RestClient.Builder builder, CustomerPortalProperties.Shiprocket env,
                             Function<UUID, CustomerPortalProperties.Shiprocket> settings) {
        this.env = env;
        this.settings = settings;
        this.http = builder.baseUrl(env.getBaseUrl()).build();
    }

    @Override public String code() { return CODE; }
    @Override public String displayName() { return "Shiprocket"; }
    @Override public boolean external() { return true; }
    @Override public boolean configured() { return env.configured(); }
    @Override public boolean configured(UUID companyId) { return settings.apply(companyId).configured(); }

    @Override
    public ProviderResult getRates(RateRequest r) {
        if (!configured(r.companyId())) {
            return ProviderResult.unavailable(CODE, displayName(), "Shiprocket is not connected yet.");
        }
        try {
            JsonNode body = http.get()
                    .uri(u -> u.path("/v1/external/courier/serviceability/")
                            .queryParam("pickup_postcode", r.pickupPincode())
                            .queryParam("delivery_postcode", r.deliveryPincode())
                            .queryParam("weight", r.actualWeightKg().toPlainString())
                            .queryParam("length", r.lengthCm().toPlainString())
                            .queryParam("breadth", r.breadthCm().toPlainString())
                            .queryParam("height", r.heightCm().toPlainString())
                            .queryParam("declared_value", r.declaredValue().toPlainString())
                            .queryParam("cod", r.cod() ? 1 : 0)
                            .build())
                    .header("Authorization", "Bearer " + token(r.companyId()))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
            return ProviderResult.available(CODE, displayName(), parse(body, r));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                sessions.remove(fingerprint(r.companyId())); // forces a fresh login next time
            }
            log.warn("Shiprocket rates failed: HTTP {}", e.getStatusCode().value());
            return ProviderResult.unavailable(CODE, displayName(), "Shiprocket did not return rates for this shipment.");
        } catch (RestClientException | IllegalStateException e) {
            log.warn("Shiprocket rates failed: {}", e.getClass().getSimpleName());
            return ProviderResult.unavailable(CODE, displayName(), "Shiprocket could not be reached.");
        }
    }

    /**
     * Two calls: create the order ({@code order_id} = our booking reference), then assign the chosen courier
     * and get the AWB. A failure after the order exists is UNKNOWN with the Shiprocket shipment id, so
     * operations can finish or cancel it in the Shiprocket panel — it is never created twice by us.
     */
    @Override
    public BookingOutcome book(BookingRequest b) {
        CustomerPortalProperties.Shiprocket config = settings.apply(b.companyId());
        if (!config.bookable()) {
            return BookingOutcome.rejected("NOT_CONFIGURED", "Shiprocket bookings are not set up yet.");
        }
        String shipmentId;
        try {
            JsonNode order = http.post().uri("/v1/external/orders/create/adhoc")
                    .header("Authorization", "Bearer " + token(b.companyId()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(orderBody(b, config.getPickupLocation()))
                    .retrieve()
                    .body(JsonNode.class);
            shipmentId = order == null ? null : order.path("shipment_id").asText(null);
            if (shipmentId == null || shipmentId.isBlank()) {
                return BookingOutcome.unknown(null, "Shiprocket did not confirm the order. Our team will check it.");
            }
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status >= 400 && status < 500) {
                return BookingOutcome.rejected("PROVIDER_REJECTED", "Shiprocket refused the order. Please check the details.");
            }
            return BookingOutcome.unknown(null, "Shiprocket answered with an error. Our team will check whether the order was created.");
        } catch (ResourceAccessException e) {
            return BookingOutcome.unknown(null, "Shiprocket did not answer in time. Our team will check whether the order was created.");
        } catch (IllegalStateException e) {
            // Login failed: the order request was never sent.
            return BookingOutcome.rejected("PROVIDER_UNAVAILABLE", "Shiprocket could not be reached. Nothing was booked.");
        } catch (RestClientException e) {
            return BookingOutcome.unknown(null, "Shiprocket's answer could not be read. Our team will check whether the order was created.");
        }
        try {
            JsonNode awb = http.post().uri("/v1/external/courier/assign/awb")
                    .header("Authorization", "Bearer " + token(b.companyId()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("shipment_id", shipmentId, "courier_id", b.serviceCode()))
                    .retrieve()
                    .body(JsonNode.class);
            String code = awb == null ? null : awb.path("response").path("data").path("awb_code").asText(null);
            if (code == null || code.isBlank()) {
                return BookingOutcome.unknown(shipmentId, "The order was created but no tracking number was assigned yet. Our team will complete it.");
            }
            return BookingOutcome.booked(shipmentId, code, null, "Booked with Shiprocket.");
        } catch (RestClientException | IllegalStateException e) {
            return BookingOutcome.unknown(shipmentId, "The order was created but the courier could not be assigned yet. Our team will complete it.");
        }
    }

    /** {@code GET /v1/external/courier/track/awb/{awb}}: Shiprocket's activities for the AWB, newest first. */
    /**
     * Cancels the Shiprocket order. The order is looked up by the reference we sent ({@code channel_order_id}) because its id is
     * not the shipment id we may have kept; an exact match is required, so another order is never touched.
     */
    @Override
    public CancelOutcome cancelOrder(java.util.UUID companyId, String orderRef, String reference) {
        if (!configured(companyId)) {
            return CancelOutcome.failed("Shiprocket is not connected, so the order cannot be cancelled from here.");
        }
        if (reference == null || reference.isBlank()) {
            return CancelOutcome.failed("There is no order reference to look up at Shiprocket.");
        }
        String[] step = {"order lookup"};
        try {
            JsonNode found = http.get().uri(u -> u.path("/v1/external/orders").queryParam("search", reference).queryParam("per_page", 25).build())
                    .header("Authorization", "Bearer " + token(companyId))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode match = null;
            for (JsonNode o : found == null ? List.<JsonNode>of() : found.path("data")) {
                if (reference.equals(o.path("channel_order_id").asText(null))) {
                    match = o;
                    break;
                }
            }
            if (match == null) {
                return CancelOutcome.notFound("Shiprocket has no order " + reference + ".");
            }
            String status = match.path("status").asText("");
            if (status.toUpperCase(java.util.Locale.ROOT).startsWith("CANCEL")) {
                return CancelOutcome.cancelled("The order was already cancelled at Shiprocket.");
            }
            String awb = null;
            for (JsonNode sh : match.path("shipments")) {
                String a = sh.path("awb").asText(null);
                if (a != null && !a.isBlank()) {
                    awb = a;
                    break;
                }
            }
            step[0] = "cancel";
            if (awb != null) {
                http.post().uri("/v1/external/orders/cancel/shipment/awbs")
                        .header("Authorization", "Bearer " + token(companyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("awbs", List.of(awb)))
                        .retrieve().toBodilessEntity();
            } else {
                http.post().uri("/v1/external/orders/cancel")
                        .header("Authorization", "Bearer " + token(companyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("ids", List.of(match.path("id").asLong())))
                        .retrieve().toBodilessEntity();
            }
            return CancelOutcome.cancelled("The order was cancelled at Shiprocket.");
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                sessions.remove(fingerprint(companyId));
            }
            log.warn("Shiprocket cancel failed: HTTP {} on {}", e.getStatusCode().value(), step[0]);
            return CancelOutcome.failed("Shiprocket refused the " + step[0] + " (HTTP " + e.getStatusCode().value()
                    + "): the saved Shiprocket API user may not be allowed to do this. Cancel it in the Shiprocket panel, then choose \"I already cancelled it\".");
        } catch (RestClientException | IllegalStateException e) {
            log.warn("Shiprocket cancel failed: {}", e.getClass().getSimpleName());
            return CancelOutcome.failed("Shiprocket could not be reached, so the order was not cancelled.");
        }
    }

    @Override
    public TrackingResult track(ShipmentRef ref) {
        if (!configured(ref.companyId())) {
            return TrackingResult.unavailable("Shiprocket is not connected, so tracking is not available.");
        }
        if (ref.awbNumber() == null || ref.awbNumber().isBlank()) {
            return TrackingResult.unavailable("No tracking number has been assigned yet.");
        }
        try {
            JsonNode body = http.get().uri("/v1/external/courier/track/awb/{awb}", ref.awbNumber())
                    .header("Authorization", "Bearer " + token(ref.companyId()))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
            return parseTracking(body, ref.awbNumber());
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                sessions.remove(fingerprint(ref.companyId()));
            }
            log.warn("Shiprocket tracking failed: HTTP {}", e.getStatusCode().value());
            return TrackingResult.unavailable("Shiprocket did not return tracking right now. Please try again later.");
        } catch (RestClientException | IllegalStateException e) {
            log.warn("Shiprocket tracking failed: {}", e.getClass().getSimpleName());
            return TrackingResult.unavailable("Shiprocket could not be reached. Please try again later.");
        }
    }

    static TrackingResult parseTracking(JsonNode body, String awb) {
        JsonNode data = body == null ? null : body.path("tracking_data");
        if (data == null || data.isMissingNode()) {
            return TrackingResult.unavailable("Shiprocket did not return tracking right now. Please try again later.");
        }
        List<TrackingEvent> events = new ArrayList<>();
        for (JsonNode a : data.path("shipment_track_activities")) {
            String status = text(a, "activity");
            events.add(new TrackingEvent(ProviderTimes.parse(text(a, "date")),
                    status != null ? status : ProviderTimes.label(text(a, "sr-status-label")), text(a, "location"), null));
        }
        JsonNode track = data.path("shipment_track").path(0);
        String status = text(track, "current_status");
        if (events.isEmpty() && status == null) {
            return TrackingResult.unavailable("No tracking updates from the courier yet.");
        }
        events.sort(Comparator.comparing(TrackingEvent::at, Comparator.nullsFirst(Comparator.<Instant>naturalOrder())).reversed());
        return TrackingResult.of(status != null ? status : events.get(0).status(), awb,
                events.isEmpty() ? null : events.get(0).location(), events);
    }

    /** {@code POST /v1/external/courier/generate/label}: Shiprocket answers with a PDF URL it hosts. */
    @Override
    public LabelResult label(ShipmentRef ref) {
        if (!configured(ref.companyId())) {
            return LabelResult.notAvailable("Shiprocket is not connected, so labels are not available.");
        }
        if (ref.providerOrderRef() == null || ref.awbNumber() == null) {
            return LabelResult.notAvailable("The label is available once a tracking number is assigned.");
        }
        try {
            JsonNode body = http.post().uri("/v1/external/courier/generate/label")
                    .header("Authorization", "Bearer " + token(ref.companyId()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("shipment_id", List.of(ref.providerOrderRef())))
                    .retrieve()
                    .body(JsonNode.class);
            String url = body == null ? null : text(body, "label_url");
            return url != null ? LabelResult.at(url)
                    : LabelResult.notAvailable("Shiprocket has not generated the label yet. Please try again shortly.");
        } catch (RestClientException | IllegalStateException e) {
            log.warn("Shiprocket label failed: {}", e.getClass().getSimpleName());
            return LabelResult.notAvailable("Shiprocket could not provide the label right now. Please try again later.");
        }
    }

    @Override
    public Set<String> labelHosts() {
        return Set.of(".amazonaws.com", ".shiprocket.in", ".shiprocket.co");
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }

    static Map<String, Object> orderBody(BookingRequest b, String pickupLocation) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("order_id", b.reference());
        m.put("order_date", LocalDateTime.now(ZoneId.of("Asia/Kolkata")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        m.put("pickup_location", pickupLocation);
        m.put("billing_customer_name", b.delivery().name());
        m.put("billing_last_name", "");
        m.put("billing_address", b.delivery().address());
        m.put("billing_city", b.delivery().city());
        m.put("billing_pincode", b.delivery().pincode());
        m.put("billing_state", b.delivery().state());
        m.put("billing_country", "India");
        m.put("billing_phone", b.delivery().phone());
        m.put("shipping_is_billing", true);
        m.put("order_items", List.of(Map.of("name", b.contents() == null ? "Goods" : b.contents(), "sku", b.reference(),
                "units", b.rate().packageCount(), "selling_price", b.rate().declaredValue()
                        .divide(BigDecimal.valueOf(b.rate().packageCount()), 2, RoundingMode.HALF_UP))));
        m.put("payment_method", b.rate().cod() ? "COD" : "Prepaid");
        m.put("sub_total", b.rate().declaredValue());
        m.put("length", b.rate().lengthCm());
        m.put("breadth", b.rate().breadthCm());
        m.put("height", b.rate().heightCm());
        m.put("weight", b.rate().actualWeightKg());
        m.put("comment", "Pickup contact: " + b.pickup().name() + ", " + b.pickup().phone() + ", " + b.pickup().address()
                + ", " + b.pickup().pincode());
        return m;
    }

    static List<ProviderRate> parse(JsonNode body, RateRequest r) {
        List<ProviderRate> rates = new ArrayList<>();
        JsonNode list = body == null ? null : body.path("data").path("available_courier_companies");
        if (list == null || !list.isArray()) {
            return rates;
        }
        for (JsonNode c : list) {
            BigDecimal total = decimal(c, "rate");
            if (total == null || total.signum() <= 0) {
                continue; // a service without a price is not offered
            }
            BigDecimal freight = decimal(c, "freight_charge");
            BigDecimal cod = r.cod() ? decimal(c, "cod_charges") : null;
            List<ChargeLine> lines = new ArrayList<>();
            BigDecimal accounted = BigDecimal.ZERO;
            if (freight != null && freight.signum() > 0 && freight.compareTo(total) <= 0) {
                lines.add(new ChargeLine("FREIGHT", "Freight", ChargeComponent.FREIGHT, freight));
                accounted = accounted.add(freight);
            }
            if (cod != null && cod.signum() > 0 && accounted.add(cod).compareTo(total) <= 0) {
                lines.add(new ChargeLine("COD", "COD charge", ChargeComponent.COD, cod));
                accounted = accounted.add(cod);
            }
            BigDecimal rest = total.subtract(accounted);
            if (lines.isEmpty()) {
                lines.add(new ChargeLine("FREIGHT", "Freight (as quoted)", ChargeComponent.FREIGHT, total));
            } else if (rest.signum() > 0) {
                lines.add(new ChargeLine("OTHER", "Other charges (as quoted)", ChargeComponent.OTHER, rest));
            }
            Integer days = integer(c, "estimated_delivery_days");
            String mode = c.has("is_surface") ? (c.path("is_surface").asBoolean() ? "SURFACE" : "AIR") : null;
            rates.add(new ProviderRate(c.path("courier_company_id").asText(null), c.path("courier_name").asText("Courier"),
                    mode, days, days, kg(c, "charge_weight"), lines, null, null));
        }
        return rates;
    }

    private String fingerprint(UUID companyId) {
        CustomerPortalProperties.Shiprocket config = settings.apply(companyId);
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((config.getEmail() + "\n" + config.getPassword()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A login for the company's current account; a changed e-mail or password is a different account and logs in again. */
    private synchronized String token(UUID companyId) {
        CustomerPortalProperties.Shiprocket config = settings.apply(companyId);
        String fingerprint = fingerprint(companyId);
        Session current = sessions.get(fingerprint);
        if (current != null && Instant.now().isBefore(current.expires())) {
            return current.token();
        }
        JsonNode login = http.post().uri("/v1/external/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", config.getEmail(), "password", config.getPassword()))
                .retrieve()
                .body(JsonNode.class);
        String fresh = login == null ? null : login.path("token").asText(null);
        if (fresh == null || fresh.isBlank()) {
            throw new IllegalStateException("Shiprocket login returned no token");
        }
        sessions.put(fingerprint, new Session(fresh, Instant.now().plus(TOKEN_LIFETIME)));
        return fresh;
    }

    /** A fresh login with the company's credentials, then (best effort) a check that the pickup location exists. */
    @Override
    public ConnectionTest testConnection(UUID companyId) {
        CustomerPortalProperties.Shiprocket config = settings.apply(companyId);
        if (!config.configured()) {
            return ConnectionTest.failed("Shiprocket is switched off or its e-mail and password are missing.");
        }
        String bearer;
        try {
            sessions.remove(fingerprint(companyId));
            bearer = token(companyId);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            return ConnectionTest.failed(status >= 400 && status < 500
                    ? "Shiprocket did not accept the e-mail and password. Check that they are those of an API user."
                    : "Shiprocket answered with an error. Please try again later.");
        } catch (RestClientException | IllegalStateException e) {
            return ConnectionTest.failed("Shiprocket could not be reached. Please try again later.");
        }
        String wanted = config.getPickupLocation();
        if (wanted == null || wanted.isBlank()) {
            return ConnectionTest.ok("Login accepted. Add a pickup location to allow bookings.");
        }
        try {
            JsonNode body = http.get().uri("/v1/external/settings/company/pickup")
                    .header("Authorization", "Bearer " + bearer)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode list = body == null ? null : body.path("data").path("shipping_address");
            if (list != null && list.isArray() && list.size() > 0) {
                boolean found = false;
                for (JsonNode a : list) {
                    if (wanted.equalsIgnoreCase(a.path("pickup_location").asText(""))) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return ConnectionTest.failed("Login accepted, but no pickup location named '" + wanted
                            + "' is registered in Shiprocket. Use the name exactly as shown there.");
                }
            }
        } catch (RestClientException e) {
            return ConnectionTest.ok("Login accepted. The pickup location could not be checked and will be verified when you book.");
        }
        return ConnectionTest.ok("Connected. Shiprocket accepted the login and the pickup location.");
    }

    private static BigDecimal decimal(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) return null;
        try {
            return new BigDecimal(v.asText()).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer integer(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        try {
            return Integer.valueOf(v.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal kg(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) return null;
        try {
            return new BigDecimal(v.asText()).setScale(3, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
