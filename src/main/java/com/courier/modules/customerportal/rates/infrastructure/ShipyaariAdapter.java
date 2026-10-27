package com.courier.modules.customerportal.rates.infrastructure;

import com.courier.modules.customerportal.rates.application.CustomerPortalProperties;
import com.courier.modules.customerportal.rates.domain.BookingOutcome;
import com.courier.modules.customerportal.rates.domain.BookingRequest;
import com.courier.modules.customerportal.rates.domain.ChargeComponent;
import com.courier.modules.customerportal.rates.domain.ChargeLine;
import com.courier.modules.customerportal.rates.domain.ConnectionTest;
import com.courier.modules.customerportal.rates.domain.CourierProviderAdapter;
import com.courier.modules.customerportal.rates.domain.LabelResult;
import com.courier.modules.customerportal.rates.domain.ProviderRate;
import com.courier.modules.customerportal.rates.domain.ProviderResult;
import com.courier.modules.customerportal.rates.domain.RateRequest;
import com.courier.modules.customerportal.rates.domain.ShipmentRef;
import com.courier.modules.customerportal.rates.domain.TrackingEvent;
import com.courier.modules.customerportal.rates.domain.TrackingResult;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Shipyaari (aggregator, "Blaze" API): one serviceability call prices every courier Shipyaari can use for the lane, and one
 * order call books the chosen one.
 *
 * <p>Built from Shipyaari's published API collection ({@code docs.shipyaari.com}): {@code POST /api/v1/seller/signIn} (e-mail and
 * password, answers a JWT used as {@code Authorization: Bearer}), {@code POST /api/v1/order/checkServiceabilityV2},
 * {@code POST /api/v1/order/placeOrderApiV3}, {@code GET /api/v1/tracking/getTracking}, {@code POST /api/v1/labels/fetchLabels}.
 * <b>Not yet exercised against a live Shipyaari account</b>: verify on the staging account before enabling (see
 * MEMORY/modules/customer-portal.md) — in particular how an order names its courier service, and the label response.
 *
 * <p>Unlike Shiprocket and Delhivery there is no pre-registered pickup location: Shipyaari takes the pickup address with every
 * order, so the customer's own pickup details go straight in. Shipyaari debits its own wallet when it accepts an order; a
 * refusal for that reason is logged for operations and shown to the customer as a general "try again later".
 */
@Slf4j
public class ShipyaariAdapter implements CourierProviderAdapter {

    public static final String CODE = "SHIPYAARI";
    /** The sample token in Shipyaari's documentation lives 24 hours; renewing earlier avoids using one that just expired. */
    private static final Duration TOKEN_LIFETIME = Duration.ofHours(20);
    /** A rate's {@code serviceCode} carries what the order needs to name the service again: partner, service and mode. */
    static final String SEP = "|";

    private final RestClient http;
    private final CustomerPortalProperties.Shipyaari env;
    private final Function<UUID, CustomerPortalProperties.Shipyaari> settings;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private record Session(String token, Instant expires) {
    }

    /** A login Shipyaari did not accept (as opposed to being unreachable). */
    static final class LoginRefused extends IllegalStateException {
        LoginRefused(String message) {
            super(message);
        }
    }

    public ShipyaariAdapter(RestClient.Builder builder, CustomerPortalProperties.Shipyaari config) {
        this(builder, config, companyId -> config);
    }

    public ShipyaariAdapter(RestClient.Builder builder, CustomerPortalProperties.Shipyaari env,
                            Function<UUID, CustomerPortalProperties.Shipyaari> settings) {
        this.env = env;
        this.settings = settings;
        this.http = builder.baseUrl(env.getBaseUrl()).build();
    }

    @Override public String code() { return CODE; }
    @Override public String displayName() { return "Shipyaari"; }
    @Override public boolean external() { return true; }
    @Override public boolean configured() { return env.configured(); }
    @Override public boolean configured(UUID companyId) { return settings.apply(companyId).configured(); }

    // ---------------------------------------------------------------- rates

    @Override
    public ProviderResult getRates(RateRequest r) {
        if (!configured(r.companyId())) {
            return ProviderResult.unavailable(CODE, displayName(), "Shipyaari is not connected yet.");
        }
        Map<String, Object> dimension = new LinkedHashMap<>();
        dimension.put("length", r.lengthCm());
        dimension.put("width", r.breadthCm());
        dimension.put("height", r.heightCm());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pickupPincode", pincode(r.pickupPincode()));
        body.put("deliveryPincode", pincode(r.deliveryPincode()));
        body.put("invoiceValue", r.declaredValue());
        body.put("paymentMode", r.cod() ? "COD" : "PREPAID");
        body.put("weight", r.actualWeightKg());
        body.put("orderType", "B2C");
        body.put("dimension", dimension);
        try {
            JsonNode answer = http.post().uri("/api/v1/order/checkServiceabilityV2")
                    .header("Authorization", "Bearer " + token(r.companyId()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            return ProviderResult.available(CODE, displayName(), parse(answer, r));
        } catch (RestClientResponseException e) {
            forgetLoginOnAuthFailure(e, r.companyId());
            log.warn("Shipyaari rates failed: HTTP {}", e.getStatusCode().value());
            return ProviderResult.unavailable(CODE, displayName(), "Shipyaari did not return rates for this shipment.");
        } catch (RestClientException | IllegalStateException e) {
            log.warn("Shipyaari rates failed: {}", e.getClass().getSimpleName());
            return ProviderResult.unavailable(CODE, displayName(), "Shipyaari could not be reached.");
        }
    }

    static List<ProviderRate> parse(JsonNode body, RateRequest r) {
        List<ProviderRate> rates = new ArrayList<>();
        JsonNode list = body == null ? null : body.path("data");
        if (list == null || !list.isArray()) {
            return rates;
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode s : list) {
            BigDecimal total = decimal(s, "total");
            String partner = text(s, "partnerName");
            String service = text(s, "companyServiceName");
            String mode = text(s, "serviceMode");
            if (total == null || total.signum() <= 0 || partner == null || service == null) {
                continue; // a service without a price or a name cannot be booked
            }
            String code = String.join(SEP, partner, service, mode == null ? "" : mode);
            if (code.length() > 80 || !seen.add(code)) {
                continue; // does not fit the stored service code, or the same service listed twice
            }
            List<ChargeLine> lines = new ArrayList<>();
            BigDecimal accounted = BigDecimal.ZERO;
            accounted = line(lines, accounted, total, "FREIGHT", "Freight", ChargeComponent.FREIGHT, decimal(s, "base"));
            accounted = line(lines, accounted, total, "ADDITIONAL", "Additional weight charge", ChargeComponent.FREIGHT, decimal(s, "add"));
            BigDecimal variable = sum(decimal(s, "variables"), decimal(s, "variableServices"));
            accounted = line(lines, accounted, total, "VARIABLE", "Variable charges", ChargeComponent.OTHER, variable);
            if (r.cod()) {
                accounted = line(lines, accounted, total, "COD", "COD charge", ChargeComponent.COD, decimal(s, "cod"));
            }
            accounted = line(lines, accounted, total, "INSURANCE", "Insurance", ChargeComponent.INSURANCE, decimal(s, "insurance"));
            accounted = line(lines, accounted, total, "TAX", "Tax", ChargeComponent.TAX, decimal(s, "tax"));
            BigDecimal rest = total.subtract(accounted);
            if (lines.isEmpty()) {
                lines.add(new ChargeLine("FREIGHT", "Freight (as quoted)", ChargeComponent.FREIGHT, total));
            } else if (rest.signum() > 0) {
                lines.add(new ChargeLine("OTHER", "Other charges (as quoted)", ChargeComponent.OTHER, rest));
            }
            Integer days = integer(s, "EDT");
            rates.add(new ProviderRate(code, partner + " " + readable(service) + (mode == null ? "" : " (" + readable(mode) + ")"),
                    mode, days, days, kg(s, "appliedWeight"), lines, text(s, "partnerServiceId"), null));
        }
        return rates;
    }

    /** Adds a charge line when it is positive and keeps the lines from adding up to more than the total. */
    private static BigDecimal line(List<ChargeLine> lines, BigDecimal accounted, BigDecimal total, String code, String label,
                                   ChargeComponent component, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || accounted.add(amount).compareTo(total) > 0) {
            return accounted;
        }
        lines.add(new ChargeLine(code, label, component, amount));
        return accounted.add(amount);
    }

    // -------------------------------------------------------------- booking

    /**
     * One call: {@code placeOrderApiV3} creates the order and assigns the courier and AWB together. A refusal in the 4xx range
     * or an answer of {@code success:false} means nothing was booked (REJECTED); anything else in doubt is UNKNOWN with the
     * Shipyaari order id when we have it, so operations can check the Shipyaari panel — we never create the order twice.
     */
    @Override
    public BookingOutcome book(BookingRequest b) {
        CustomerPortalProperties.Shipyaari config = settings.apply(b.companyId());
        if (!config.bookable()) {
            return BookingOutcome.rejected("NOT_CONFIGURED", "Shipyaari bookings are not set up yet.");
        }
        Map<String, Object> order;
        try {
            order = orderBody(b);
        } catch (IllegalArgumentException e) {
            return BookingOutcome.rejected("INVALID_DETAILS", e.getMessage());
        }
        JsonNode answer;
        try {
            answer = http.post().uri("/api/v1/order/placeOrderApiV3")
                    .header("Authorization", "Bearer " + token(b.companyId()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(order)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            forgetLoginOnAuthFailure(e, b.companyId());
            log.warn("Shipyaari refused booking {}: HTTP {} {}", b.reference(), status, providerMessage(e));
            if (status >= 400 && status < 500) {
                return BookingOutcome.rejected("PROVIDER_REJECTED", "Shipyaari could not accept the order. Please check the details or try again later.");
            }
            return BookingOutcome.unknown(null, "Shipyaari answered with an error. Our team will check whether the order was created.");
        } catch (ResourceAccessException e) {
            return BookingOutcome.unknown(null, "Shipyaari did not answer in time. Our team will check whether the order was created.");
        } catch (IllegalStateException e) {
            // Login failed: the order request was never sent.
            return BookingOutcome.rejected("PROVIDER_UNAVAILABLE", "Shipyaari could not be reached. Nothing was booked.");
        } catch (RestClientException e) {
            return BookingOutcome.unknown(null, "Shipyaari's answer could not be read. Our team will check whether the order was created.");
        }
        if (answer == null) {
            return BookingOutcome.unknown(null, "Shipyaari did not confirm the order. Our team will check it.");
        }
        if (!answer.path("success").asBoolean(false)) {
            log.warn("Shipyaari declined booking {}: {}", b.reference(), text(answer, "message"));
            return BookingOutcome.rejected("PROVIDER_REJECTED", "Shipyaari could not accept the order. Please check the details or try again later.");
        }
        JsonNode created = answer.path("data").path(0);
        String orderId = text(created, "orderId");
        JsonNode awb = created.path("awbs").path(0);
        String awbNumber = text(awb.path("tracking"), "awb");
        if (awbNumber == null) {
            return BookingOutcome.unknown(orderId, "The order was created but no tracking number was assigned yet. Our team will complete it.");
        }
        String bookedPartner = text(awb.path("charges"), "partnerName");
        String wantedPartner = partOf(b.serviceCode(), 0);
        if (bookedPartner != null && wantedPartner != null && !bookedPartner.equalsIgnoreCase(wantedPartner)) {
            // Shipyaari chose a different courier than the one quoted: the booking stands, operations are told.
            log.warn("Shipyaari booked {} for {} although {} was quoted", bookedPartner, b.reference(), wantedPartner);
        }
        return BookingOutcome.booked(orderId, awbNumber, text(awb.path("tracking"), "label"),
                "Booked with Shipyaari" + (bookedPartner == null ? "." : " (" + bookedPartner + ")."));
    }

    static Map<String, Object> orderBody(BookingRequest b) {
        String pickupMobile = mobile(b.pickup().phone(), "pickup");
        String deliveryMobile = mobile(b.delivery().phone(), "delivery");
        int packages = Math.max(1, b.rate().packageCount());
        BigDecimal declared = b.rate().declaredValue();
        BigDecimal perBox = declared.divide(BigDecimal.valueOf(packages), 2, RoundingMode.DOWN);
        BigDecimal perBoxWeight = b.rate().actualWeightKg().divide(BigDecimal.valueOf(packages), 3, RoundingMode.HALF_UP);
        List<Map<String, Object>> boxes = new ArrayList<>();
        for (int i = 0; i < packages; i++) {
            // The last box carries the rounding remainder so the boxes add up to exactly the declared value.
            BigDecimal value = i == packages - 1 ? declared.subtract(perBox.multiply(BigDecimal.valueOf(packages - 1L))) : perBox;
            boxes.add(box(b, i + 1, perBoxWeight, value));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pickupDetails", party(b.pickup(), pickupMobile));
        m.put("deliveryDetails", party(b.delivery(), deliveryMobile));
        m.put("boxInfo", boxes);
        m.put("orderType", "B2C");
        m.put("transit", "FORWARD");
        String partner = partOf(b.serviceCode(), 0);
        String service = partOf(b.serviceCode(), 1);
        String mode = partOf(b.serviceCode(), 2);
        if (partner != null) m.put("courierPartner", partner);
        if (service != null) m.put("courierPartnerServices", service);
        if (mode != null) m.put("serviceMode", mode);
        m.put("servicePriority", "cheapest");
        m.put("source", "API");
        m.put("orderId", b.reference());
        return m;
    }

    private static Map<String, Object> party(BookingRequest.Party p, String mobile) {
        Map<String, Object> contact = new LinkedHashMap<>();
        contact.put("name", p.name());
        contact.put("mobileNo", Long.valueOf(mobile));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fullAddress", fullAddress(p));
        m.put("pincode", pincode(p.pincode()));
        m.put("contact", contact);
        return m;
    }

    private static Map<String, Object> box(BookingRequest b, int number, BigDecimal weight, BigDecimal value) {
        String contents = b.contents() == null || b.contents().isBlank() ? "Goods" : b.contents();
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", contents);
        product.put("sku", b.reference());
        product.put("qty", 1);
        product.put("unitPrice", value);
        product.put("sellingPrice", value);
        product.put("totalPrice", value);
        product.put("weightUnit", "kg");
        product.put("deadWeight", weight);
        product.put("length", b.rate().lengthCm());
        product.put("breadth", b.rate().breadthCm());
        product.put("height", b.rate().heightCm());
        product.put("measureUnit", "cm");
        Map<String, Object> cod = new LinkedHashMap<>();
        cod.put("isCod", b.rate().cod());
        cod.put("collectableAmount", b.rate().cod() ? value : BigDecimal.ZERO);
        cod.put("invoiceValue", value);
        Map<String, Object> box = new LinkedHashMap<>();
        box.put("name", "box_" + number);
        box.put("type", "parcel");
        box.put("weightUnit", "kg");
        box.put("deadWeight", weight);
        box.put("length", b.rate().lengthCm());
        box.put("breadth", b.rate().breadthCm());
        box.put("height", b.rate().heightCm());
        box.put("qty", 1);
        box.put("measureUnit", "cm");
        box.put("products", List.of(product));
        box.put("codInfo", cod);
        box.put("podInfo", Map.of("isPod", false));
        box.put("insurance", false);
        return box;
    }

    // ------------------------------------------------------------- tracking

    /** {@code GET /api/v1/tracking/getTracking?trackingNo=}: the current status and every scan, newest first. */
    @Override
    public TrackingResult track(ShipmentRef ref) {
        if (!configured(ref.companyId())) {
            return TrackingResult.unavailable("Shipyaari is not connected, so tracking is not available.");
        }
        if (ref.awbNumber() == null || ref.awbNumber().isBlank()) {
            return TrackingResult.unavailable("No tracking number has been assigned yet.");
        }
        try {
            JsonNode body = http.get().uri(u -> u.path("/api/v1/tracking/getTracking").queryParam("trackingNo", ref.awbNumber()).build())
                    .header("Authorization", "Bearer " + token(ref.companyId()))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
            return parseTracking(body, ref.awbNumber());
        } catch (RestClientResponseException e) {
            forgetLoginOnAuthFailure(e, ref.companyId());
            log.warn("Shipyaari tracking failed: HTTP {}", e.getStatusCode().value());
            return TrackingResult.unavailable("Shipyaari did not return tracking right now. Please try again later.");
        } catch (RestClientException | IllegalStateException e) {
            log.warn("Shipyaari tracking failed: {}", e.getClass().getSimpleName());
            return TrackingResult.unavailable("Shipyaari could not be reached. Please try again later.");
        }
    }

    static TrackingResult parseTracking(JsonNode body, String awb) {
        JsonNode info = body == null ? null : body.path("data").path(0).path("trackingInfo").path(0);
        if (info == null || info.isMissingNode() || info.isNull()) {
            return TrackingResult.unavailable("Shipyaari did not return tracking right now. Please try again later.");
        }
        List<TrackingEvent> events = new ArrayList<>();
        for (JsonNode group : info.path("processedLog")) {
            for (JsonNode scan : group.path("Scans")) {
                Long millis = scan.path("time").isNumber() ? scan.path("time").asLong() : null;
                events.add(new TrackingEvent(millis == null ? null : Instant.ofEpochMilli(millis),
                        readable(text(scan, "status")), text(scan, "location"), text(scan, "message")));
            }
        }
        String status = readable(text(info, "currentStatus"));
        if (events.isEmpty() && status == null) {
            return TrackingResult.unavailable("No tracking updates from the courier yet.");
        }
        events.sort(Comparator.comparing(TrackingEvent::at, Comparator.nullsFirst(Comparator.<Instant>naturalOrder())).reversed());
        return TrackingResult.of(status != null ? status : events.get(0).status(), awb,
                events.isEmpty() ? null : events.get(0).location(), events);
    }

    // ---------------------------------------------------------------- label

    /**
     * {@code POST /api/v1/labels/fetchLabels}: Shipyaari documents that it "displays the URL of the generated label" but publishes
     * no example answer, so the first web address found in the answer is used. An answer that is not JSON (a PDF sent directly) is
     * reported as not available rather than guessed at.
     */
    @Override
    public LabelResult label(ShipmentRef ref) {
        if (!configured(ref.companyId())) {
            return LabelResult.notAvailable("Shipyaari is not connected, so labels are not available.");
        }
        if (ref.awbNumber() == null || ref.awbNumber().isBlank()) {
            return LabelResult.notAvailable("The label is available once a tracking number is assigned.");
        }
        try {
            JsonNode body = http.post().uri("/api/v1/labels/fetchLabels")
                    .header("Authorization", "Bearer " + token(ref.companyId()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("awbs", List.of(ref.awbNumber()), "source", "API"))
                    .retrieve()
                    .body(JsonNode.class);
            String url = firstUrl(body);
            return url != null ? LabelResult.at(url)
                    : LabelResult.notAvailable("Shipyaari has not generated the label yet. Please try again shortly.");
        } catch (RestClientException | IllegalStateException e) {
            log.warn("Shipyaari label failed: {}", e.getClass().getSimpleName());
            return LabelResult.notAvailable("Shipyaari could not provide the label right now. Please try again later.");
        }
    }

    @Override
    public Set<String> labelHosts() {
        return Set.of(".shipyaari.com", ".amazonaws.com");
    }

    static String firstUrl(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isTextual()) {
            String s = n.asText().trim();
            return s.startsWith("https://") ? s : null;
        }
        for (JsonNode child : n) {
            String found = firstUrl(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // ------------------------------------------------------- login / test

    private String fingerprint(UUID companyId) {
        CustomerPortalProperties.Shipyaari config = settings.apply(companyId);
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((config.getEmail() + "\n" + config.getPassword()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void forgetLoginOnAuthFailure(RestClientResponseException e, UUID companyId) {
        int status = e.getStatusCode().value();
        if (status == 401 || status == 403) {
            sessions.remove(fingerprint(companyId)); // forces a fresh login next time
        }
    }

    /** A login for the company's current account; a changed e-mail or password is a different account and logs in again. */
    private synchronized String token(UUID companyId) {
        CustomerPortalProperties.Shipyaari config = settings.apply(companyId);
        String fingerprint = fingerprint(companyId);
        Session current = sessions.get(fingerprint);
        if (current != null && Instant.now().isBefore(current.expires())) {
            return current.token();
        }
        JsonNode login = http.post().uri("/api/v1/seller/signIn")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", config.getEmail(), "password", config.getPassword()))
                .retrieve()
                .body(JsonNode.class);
        if (login != null && login.has("success") && !login.path("success").asBoolean(false)) {
            throw new LoginRefused("Shipyaari did not accept the login");
        }
        String fresh = login == null ? null : text(login.path("data").path(0), "token");
        if (fresh == null) {
            throw new IllegalStateException("Shipyaari login returned no token");
        }
        sessions.put(fingerprint, new Session(fresh, Instant.now().plus(TOKEN_LIFETIME)));
        return fresh;
    }

    /** A fresh login with the company's credentials. Nothing is created or charged. */
    @Override
    public ConnectionTest testConnection(UUID companyId) {
        CustomerPortalProperties.Shipyaari config = settings.apply(companyId);
        if (!config.configured()) {
            return ConnectionTest.failed("Shipyaari is switched off or its e-mail and password are missing.");
        }
        try {
            sessions.remove(fingerprint(companyId));
            token(companyId);
            return ConnectionTest.ok("Connected. Shipyaari accepted the login.");
        } catch (LoginRefused e) {
            return ConnectionTest.failed("Shipyaari did not accept the e-mail and password.");
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            return ConnectionTest.failed(status >= 400 && status < 500
                    ? "Shipyaari did not accept the e-mail and password."
                    : "Shipyaari answered with an error. Please try again later.");
        } catch (RestClientException | IllegalStateException e) {
            return ConnectionTest.failed("Shipyaari could not be reached. Please try again later.");
        }
    }

    // -------------------------------------------------------------- helpers

    private static String providerMessage(RestClientResponseException e) {
        try {
            String body = e.getResponseBodyAsString();
            int i = body.indexOf("\"message\"");
            return i < 0 ? "" : body.substring(i, Math.min(body.length(), i + 140));
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String partOf(String serviceCode, int index) {
        if (serviceCode == null) {
            return null;
        }
        String[] parts = serviceCode.split("\\" + SEP, -1);
        return index < parts.length && !parts[index].isBlank() ? parts[index] : null;
    }

    private static String fullAddress(BookingRequest.Party p) {
        List<String> parts = new ArrayList<>();
        for (String s : new String[]{p.address(), p.city(), p.state()}) {
            if (s != null && !s.isBlank()) {
                parts.add(s.trim());
            }
        }
        return String.join(", ", parts) + (p.pincode() == null ? "" : " " + p.pincode());
    }

    /** The ten-digit mobile number Shipyaari wants; a country prefix or separators are dropped. */
    private static String mobile(String phone, String who) {
        String digits = phone == null ? "" : phone.replaceAll("\\D", "");
        if (digits.length() > 10) {
            digits = digits.substring(digits.length() - 10);
        }
        if (digits.length() != 10) {
            throw new IllegalArgumentException("The " + who + " mobile number must be 10 digits for Shipyaari.");
        }
        return digits;
    }

    private static long pincode(String pincode) {
        try {
            return Long.parseLong(pincode.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The pincode " + pincode + " is not valid.");
        }
    }

    /** "ECONOMY" -> "Economy". */
    private static String readable(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String s = code.trim().replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static BigDecimal sum(BigDecimal a, BigDecimal b) {
        if (a == null) return b;
        return b == null ? a : a.add(b);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
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
