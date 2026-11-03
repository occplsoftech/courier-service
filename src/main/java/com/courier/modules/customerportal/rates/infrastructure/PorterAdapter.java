package com.courier.modules.customerportal.rates.infrastructure;

import com.courier.modules.company.application.geocoding.GeocodingPort;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Porter (porter.in): same-city deliveries by two-wheeler, from the "Porter API Contract" 1.0 (hosts {@code pfe-apigw.porter.in}
 * live and {@code pfe-apigw-uat.porter.in} test; {@code x-api-key} header):
 * <ul>
 *   <li>{@code POST /v1/get_quote}: pickup and drop as latitude/longitude plus a customer name and mobile; {@code vehicles[]}
 *       with {@code fare.minor_amount} in paise, {@code capacity} and {@code size}. Only the 2-wheeler can be booked by API,
 *       so only it is offered.</li>
 *   <li>{@code POST /v1/orders/create}: {@code request_id} (≤ 32 characters) and both addresses with latitude/longitude and
 *       contact; answers {@code order_id} (the CRN) and {@code tracking_url}. Pickup and drop must be in one city.</li>
 *   <li>{@code GET /v1.1/orders/{crn}}: status open, accepted, live, ended or cancelled, the rider and the timings
 *       (epoch seconds). Porter allows one call a minute per order, so answers are kept for a minute.</li>
 * </ul>
 * The CRN is stored as both the order reference and the tracking number, so the tracking sync and Porter's webhook
 * ({@code order_id}) find the booking. Quotes use the pincodes' map positions; a booking uses the pin the customer placed and
 * falls back to the pincode's position (the rider then phones for the door).
 */
@Slf4j
public class PorterAdapter implements CourierProviderAdapter {

    public static final String CODE = "PORTER";
    /** Farther apart than this, the pickup and drop are not one city: Porter is not asked. */
    static final double MAX_SAME_CITY_KM = 60;
    private static final Duration TRACK_KEPT = Duration.ofSeconds(60);
    private static final Duration PLACE_KEPT = Duration.ofDays(7);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Porter's own sample points (Bengaluru), used only to check that an API key is accepted. */
    private static final double[] TEST_FROM = {12.935025, 77.609260};
    private static final double[] TEST_TO = {12.947146, 77.621029};

    private final RestClient http;
    private final CustomerPortalProperties.Porter env;
    private final Function<UUID, CustomerPortalProperties.Porter> settings;
    private final GeocodingPort geocoder;
    private final Clock clock;
    private final Map<String, Kept<Optional<Point>>> places = new ConcurrentHashMap<>();
    private final Map<String, Kept<TrackingResult>> tracks = new ConcurrentHashMap<>();

    record Point(double lat, double lng) {
    }

    private record Kept<T>(T value, Instant until) {
    }

    public PorterAdapter(RestClient.Builder builder, CustomerPortalProperties.Porter env,
                         Function<UUID, CustomerPortalProperties.Porter> settings, GeocodingPort geocoder) {
        this(builder, env, settings, geocoder, Clock.systemUTC());
    }

    PorterAdapter(RestClient.Builder builder, CustomerPortalProperties.Porter env,
                  Function<UUID, CustomerPortalProperties.Porter> settings, GeocodingPort geocoder, Clock clock) {
        this.http = builder.build();
        this.env = env;
        this.settings = settings;
        this.geocoder = geocoder;
        this.clock = clock;
    }

    @Override public String code() { return CODE; }
    @Override public String displayName() { return "Porter"; }
    @Override public boolean external() { return true; }
    @Override public boolean configured() { return env.configured(); }
    @Override public boolean configured(UUID companyId) { return settings.apply(companyId).configured(); }

    // ------------------------------------------------------------------ rates

    @Override
    public ProviderResult getRates(RateRequest request) {
        CustomerPortalProperties.Porter config = settings.apply(request.companyId());
        if (!config.configured()) {
            return ProviderResult.unavailable(CODE, displayName(), "Porter is not connected yet.");
        }
        Optional<Point> from = place(request.pickupPincode());
        Optional<Point> to = place(request.deliveryPincode());
        if (from.isEmpty() || to.isEmpty()) {
            return ProviderResult.unavailable(CODE, displayName(), "Porter needs a map location for both pincodes.");
        }
        if (km(from.get(), to.get()) > MAX_SAME_CITY_KM) {
            return ProviderResult.unavailable(CODE, displayName(), "Porter delivers within one city only.");
        }
        try {
            JsonNode answer = post(config, "/v1/get_quote", quoteBody(from.get(), to.get(), "OCCPL", config.getMobile()));
            return offer(answer, request);
        } catch (RestClientResponseException e) {
            return ProviderResult.unavailable(CODE, displayName(), refusal(e, "Porter could not price this delivery."));
        } catch (RestClientException e) {
            log.warn("Porter quote failed: {}", e.getClass().getSimpleName());
            return ProviderResult.unavailable(CODE, displayName(), "Porter could not be reached. Please try again.");
        }
    }

    /** The 2-wheeler from Porter's vehicles, if the parcel fits it. */
    ProviderResult offer(JsonNode answer, RateRequest request) {
        JsonNode vehicles = answer.has("vehicles") ? answer.path("vehicles") : answer.path("Vehicles");
        for (JsonNode v : vehicles) {
            if (!twoWheeler(v.path("type").asText(""))) {
                continue;
            }
            String tooBig = tooBig(v, request);
            if (tooBig != null) {
                return ProviderResult.unavailable(CODE, displayName(), tooBig);
            }
            BigDecimal fare = rupees(v.path("fare"));
            if (fare == null || fare.signum() <= 0) {
                break;
            }
            JsonNode eta = v.path("eta");
            String note = eta.isObject() && eta.path("value").isNumber()
                    ? "Same day · a rider in about " + eta.path("value").asInt() + " " + unit(eta.path("unit").asText("minute"))
                    : "Same day";
            return ProviderResult.available(CODE, displayName(), List.of(new ProviderRate("PORTER_2W", "Porter 2 Wheeler", "SURFACE",
                    0, 0, request.actualWeightKg(), List.of(new ChargeLine("FREIGHT", "Porter fare", ChargeComponent.FREIGHT, fare)),
                    null, null, note)));
        }
        return ProviderResult.unavailable(CODE, displayName(), "Porter has no two-wheeler for this route right now.");
    }

    private static boolean twoWheeler(String type) {
        String t = type.toLowerCase(Locale.ROOT).replace("-", " ");
        return t.startsWith("2 wheeler") || t.startsWith("two wheeler") || t.equals("2w");
    }

    /** Null when the parcel fits the vehicle's capacity and size, else why not. Missing limits are not enforced. */
    static String tooBig(JsonNode vehicle, RateRequest r) {
        BigDecimal maxKg = kg(vehicle.path("capacity"));
        if (maxKg != null && r.actualWeightKg() != null && r.actualWeightKg().compareTo(maxKg) > 0) {
            return "Too heavy for Porter's two-wheeler (up to " + maxKg.stripTrailingZeros().toPlainString() + " kg).";
        }
        JsonNode size = vehicle.path("size");
        BigDecimal[] box = {cm(size.path("length")), cm(size.path("breadth")), cm(size.path("height"))};
        BigDecimal[] parcel = {r.lengthCm(), r.breadthCm(), r.heightCm()};
        if (Arrays.stream(box).allMatch(b -> b != null) && Arrays.stream(parcel).allMatch(p -> p != null && p.signum() > 0)) {
            Arrays.sort(box);
            Arrays.sort(parcel);
            for (int i = 0; i < 3; i++) {
                if (parcel[i].compareTo(box[i]) > 0) {
                    return "Too large for Porter's two-wheeler (box of " + box[2].stripTrailingZeros().toPlainString() + " × "
                            + box[1].stripTrailingZeros().toPlainString() + " × " + box[0].stripTrailingZeros().toPlainString() + " cm).";
                }
            }
        }
        return null;
    }

    private static BigDecimal kg(JsonNode q) {
        if (!q.path("value").isNumber()) {
            return null;
        }
        BigDecimal v = q.path("value").decimalValue();
        String u = q.path("unit").asText("kg").toLowerCase(Locale.ROOT);
        return u.startsWith("ton") ? v.multiply(BigDecimal.valueOf(1000)) : v;
    }

    private static BigDecimal cm(JsonNode q) {
        if (!q.path("value").isNumber()) {
            return null;
        }
        BigDecimal v = q.path("value").decimalValue();
        return switch (q.path("unit").asText("cm").toLowerCase(Locale.ROOT)) {
            case "ft", "feet" -> v.multiply(new BigDecimal("30.48"));
            case "m" -> v.multiply(BigDecimal.valueOf(100));
            case "in", "inch" -> v.multiply(new BigDecimal("2.54"));
            default -> v;
        };
    }

    /** Porter's amounts are in paise ({@code minor_amount}). */
    private static BigDecimal rupees(JsonNode fare) {
        JsonNode m = fare.has("minor_amount") ? fare.path("minor_amount") : fare.path("minorAmount");
        return m.isNumber() ? m.decimalValue().movePointLeft(2).setScale(2, RoundingMode.HALF_UP) : null;
    }

    private static String unit(String u) {
        return u.toLowerCase(Locale.ROOT).startsWith("min") ? "minutes" : u;
    }

    private static Map<String, Object> quoteBody(Point from, Point to, String name, String mobile) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pickup_details", Map.of("lat", from.lat(), "lng", from.lng()));
        body.put("drop_details", Map.of("lat", to.lat(), "lng", to.lng()));
        body.put("customer", Map.of("name", name, "mobile", Map.of("country_code", "+91", "number", tenDigits(mobile))));
        return body;
    }

    // ------------------------------------------------------------------ booking

    @Override
    public BookingOutcome book(BookingRequest r) {
        CustomerPortalProperties.Porter config = settings.apply(r.companyId());
        if (!config.configured()) {
            return BookingOutcome.rejected("NOT_CONFIGURED", "Porter bookings are not set up yet.");
        }
        Optional<Point> from = where(r.pickup());
        Optional<Point> to = where(r.delivery());
        if (from.isEmpty() || to.isEmpty()) {
            return BookingOutcome.rejected("INVALID_DETAILS", "Porter needs the pickup and delivery on the map. Please place the pins.");
        }
        if (km(from.get(), to.get()) > MAX_SAME_CITY_KM) {
            return BookingOutcome.rejected("INVALID_DETAILS", "Porter delivers within one city only.");
        }
        String phoneA = tenDigits(r.pickup().phone());
        String phoneB = tenDigits(r.delivery().phone());
        if (phoneA.length() != 10 || phoneB.length() != 10) {
            return BookingOutcome.rejected("INVALID_DETAILS", "Porter needs a 10-digit mobile for the sender and the receiver.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", requestId(r.reference()));
        if (r.contents() != null && !r.contents().isBlank()) {
            body.put("delivery_instructions", Map.of("instructions_list",
                    List.of(Map.of("type", "text", "description", cut("Contents: " + r.contents(), 200)))));
        }
        body.put("pickup_details", Map.of("address", address(r.pickup(), from.get(), phoneA)));
        body.put("drop_details", Map.of("address", address(r.delivery(), to.get(), phoneB)));
        body.put("additional_comments", cut("Booking " + r.reference(), 256));

        JsonNode answer;
        try {
            answer = post(config, "/v1/orders/create", body);
        } catch (RestClientResponseException e) {
            return bookingRefused(e);
        } catch (ResourceAccessException e) {
            return BookingOutcome.unknown(null, "Porter did not answer in time. Our team will check whether the order was created.");
        } catch (RestClientException e) {
            return BookingOutcome.unknown(null, "Porter's answer could not be read. Our team will check whether the order was created.");
        }
        String crn = answer.path("order_id").asText("").trim();
        if (crn.isEmpty()) {
            return BookingOutcome.unknown(null, "Porter did not confirm the order. Our team will check it.");
        }
        String link = answer.path("tracking_url").asText("");
        return BookingOutcome.booked(crn, crn, null, "Porter order " + crn + " is booked. A rider will be assigned shortly."
                + (link.startsWith("https://") ? " Live tracking: " + link : ""));
    }

    /** What Porter's refusal means for the booking. Only a duplicate request id may mean the order exists. */
    private BookingOutcome bookingRefused(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        String type = errorType(e);
        if (status >= 500) {
            return BookingOutcome.unknown(null, "Porter answered with an error. Our team will check whether the order was created.");
        }
        if ("duplicate_request".equals(type)) {
            return BookingOutcome.unknown(null, "Porter says this booking was already sent. Our team will check the order.");
        }
        if (status == 401) {
            log.warn("Porter refused the API key while booking");
            return BookingOutcome.rejected("PROVIDER_REJECTED", "Porter could not take the order right now. Nothing was booked.");
        }
        if ("insufficient_wallet_balance".equals(type)) {
            log.warn("Porter wallet has insufficient balance");
            return BookingOutcome.rejected("PROVIDER_REJECTED", "Porter could not take the order right now. Nothing was booked.");
        }
        return BookingOutcome.rejected("PROVIDER_REJECTED", refusal(e, "Porter could not accept the order. Nothing was booked."));
    }

    private static Map<String, Object> address(BookingRequest.Party p, Point at, String phone) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("street_address1", cut(blank(p.address()) ? (p.city() + " " + p.pincode()) : p.address(), 250));
        if (!blank(p.city())) a.put("city", p.city());
        if (!blank(p.state())) a.put("state", p.state());
        if (!blank(p.pincode())) a.put("pincode", p.pincode());
        a.put("country", "India");
        a.put("lat", six(at.lat()));
        a.put("lng", six(at.lng()));
        a.put("contact_details", Map.of("name", cut(blank(p.name()) ? "Customer" : p.name(), 100), "phone_number", "+91" + phone));
        return a;
    }

    /** The customer's pin, else the pincode's map position. */
    private Optional<Point> where(BookingRequest.Party p) {
        if (p.pinned()) {
            return Optional.of(new Point(p.lat().doubleValue(), p.lng().doubleValue()));
        }
        return place(p.pincode());
    }

    /** Porter's request id: ours without dashes (a UUID is exactly 32 characters then). */
    static String requestId(String reference) {
        String id = reference == null ? UUID.randomUUID().toString() : reference;
        return cut(id.replace("-", ""), 32);
    }

    /** Porter keeps 6 decimals and floors the rest. */
    private static double six(double v) {
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.FLOOR).doubleValue();
    }

    // ------------------------------------------------------------------ tracking

    @Override
    public TrackingResult track(ShipmentRef shipment) {
        String crn = shipment.providerOrderRef() != null ? shipment.providerOrderRef() : shipment.awbNumber();
        if (crn == null || crn.isBlank()) {
            return TrackingResult.unavailable("Porter has not given this booking an order number yet.");
        }
        String key = shipment.companyId() + ":" + crn;
        Kept<TrackingResult> kept = tracks.get(key);
        if (kept != null && kept.until().isAfter(clock.instant())) {
            return kept.value();
        }
        CustomerPortalProperties.Porter config = settings.apply(shipment.companyId());
        if (!config.configured()) {
            return TrackingResult.unavailable("Porter is not connected.");
        }
        TrackingResult result;
        try {
            JsonNode answer = http.get().uri(config.host() + "/v1.1/orders/{crn}", crn)
                    .header("x-api-key", config.getApiKey())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve().body(JsonNode.class);
            result = tracking(crn, answer == null ? JSON.createObjectNode() : answer);
        } catch (RestClientResponseException e) {
            return TrackingResult.unavailable(e.getStatusCode().value() == 404
                    ? "Porter does not know this order yet." : "Porter tracking is not available right now.");
        } catch (RestClientException e) {
            return TrackingResult.unavailable("Porter could not be reached. Please try again.");
        }
        tracks.put(key, new Kept<>(result, clock.instant().plus(TRACK_KEPT)));
        return result;
    }

    /** Porter's order as a status line and events, newest first. */
    static TrackingResult tracking(String crn, JsonNode o) {
        String status = describe(o.path("status").asText(""));
        JsonNode rider = o.path("partner_info");
        String riderLine = rider.isObject() ? riderLine(rider) : null;
        JsonNode t = o.path("order_timings");
        List<TrackingEvent> events = new ArrayList<>();
        add(events, t.path("order_ended_time"), "Delivered", null);
        add(events, t.path("order_started_time"), "Picked up · out for delivery", riderLine);
        add(events, t.path("order_accepted_time"), "Rider assigned", riderLine);
        if ("cancelled".equalsIgnoreCase(o.path("status").asText(""))) {
            events.add(0, new TrackingEvent(null, "Cancelled", null, "Porter order cancelled"));
        }
        events.sort((a, b) -> a.at() == null ? -1 : b.at() == null ? 1 : b.at().compareTo(a.at()));
        if (events.isEmpty()) {
            events.add(new TrackingEvent(null, status, null, null));
        }
        return TrackingResult.of(riderLine == null ? status : status + " · " + riderLine, crn, null, events);
    }

    private static String riderLine(JsonNode rider) {
        String name = rider.path("name").asText("").trim();
        String vehicle = rider.path("vehicle_number").asText("").trim();
        String phone = rider.path("mobile").path("mobile_number").asText("").trim();
        String line = String.join(", ", List.of(name, vehicle, phone).stream().filter(s -> !s.isEmpty()).toList());
        return line.isEmpty() ? null : "Rider " + line;
    }

    private static void add(List<TrackingEvent> events, JsonNode epochSeconds, String status, String detail) {
        if (epochSeconds.isNumber() && epochSeconds.asLong() > 0) {
            events.add(new TrackingEvent(Instant.ofEpochSecond(epochSeconds.asLong()), status, null, detail));
        }
    }

    /**
     * Porter's statuses (Track Order {@code open/accepted/live/ended/cancelled}, webhook {@code order_accepted},
     * {@code order_start_trip}, {@code order_end_job}, {@code order_reopen}, {@code order_cancel}) in words the delivery stages
     * read ({@code DeliveryStage.of}).
     */
    public static String describe(String status) {
        return switch (status == null ? "" : status.trim().toLowerCase(Locale.ROOT)) {
            case "open" -> "Booked · finding a rider (pickup pending)";
            case "accepted", "order_accepted" -> "Rider assigned · pickup scheduled";
            case "live", "order_start_trip" -> "Picked up · out for delivery";
            case "ended", "completed", "order_end_job" -> "Delivered";
            case "order_reopen", "reopened" -> "Rider changed · finding a new rider (pickup pending)";
            case "cancelled", "order_cancel" -> "Cancelled";
            default -> status;
        };
    }

    @Override
    public LabelResult label(ShipmentRef shipment) {
        return LabelResult.notAvailable("Porter riders collect the parcel without a shipping label.");
    }

    // ------------------------------------------------------------------ connection

    @Override
    public ConnectionTest testConnection(UUID companyId) {
        CustomerPortalProperties.Porter config = settings.apply(companyId);
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            return ConnectionTest.failed("Enter the API key first.");
        }
        if (tenDigits(config.getMobile()).length() != 10) {
            return ConnectionTest.failed("Enter the 10-digit mobile registered with your Porter account.");
        }
        String system = config.isTestMode() ? "Porter's test system" : "Porter";
        try {
            post(config, "/v1/get_quote", quoteBody(new Point(TEST_FROM[0], TEST_FROM[1]), new Point(TEST_TO[0], TEST_TO[1]),
                    "OCCPL", config.getMobile()));
            return ConnectionTest.ok(system + " accepted the API key.");
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                return ConnectionTest.failed(system + " refused the API key. Check it, and that it belongs to "
                        + (config.isTestMode() ? "the test system." : "live, not the test system."));
            }
            if (e.getStatusCode().value() == 422) {
                return ConnectionTest.ok(system + " accepted the API key.");
            }
            return ConnectionTest.failed(refusal(e, system + " did not accept the check."));
        } catch (RestClientException e) {
            return ConnectionTest.failed(system + " could not be reached. Please try again.");
        }
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode post(CustomerPortalProperties.Porter config, String path, Object body) {
        JsonNode answer = http.post().uri(config.host() + path)
                .header("x-api-key", config.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().body(JsonNode.class);
        return answer == null ? JSON.createObjectNode() : answer;
    }

    /** A pincode's map position, kept for a week (the geocoder allows about one lookup a second). */
    Optional<Point> place(String pincode) {
        if (pincode == null || !pincode.trim().matches("[1-9][0-9]{5}")) {
            return Optional.empty();
        }
        String pin = pincode.trim();
        Kept<Optional<Point>> kept = places.get(pin);
        if (kept != null && kept.until().isAfter(clock.instant())) {
            return kept.value();
        }
        Optional<Point> found = geocoder.geocode(new GeocodingPort.Query(null, null, null, null, null, pin, "India"))
                .map(c -> new Point(c.latitude().doubleValue(), c.longitude().doubleValue()));
        places.put(pin, new Kept<>(found, clock.instant().plus(found.isPresent() ? PLACE_KEPT : Duration.ofHours(1))));
        return found;
    }

    /** Straight-line distance in km. */
    static double km(Point a, Point b) {
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLng = Math.toRadians(b.lng() - a.lng());
        double h = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(a.lat())) * Math.cos(Math.toRadians(b.lat())) * Math.pow(Math.sin(dLng / 2), 2);
        return 6371 * 2 * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    private static String errorType(RestClientResponseException e) {
        try {
            return JSON.readTree(e.getResponseBodyAsString()).path("type").asText("").trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    /** A refusal in words a customer may read. Porter's own messages are short and safe; anything else is replaced. */
    static String refusal(RestClientResponseException e, String fallback) {
        String type = errorType(e);
        return switch (type) {
            case "different_city_error" -> "Porter delivers within one city only.";
            case "restricted_location" -> "Porter does not serve this pickup or delivery location.";
            case "vehicle_not_found" -> "No Porter rider is free nearby right now. Please try again shortly.";
            case "similar_order_exception" -> "A similar Porter order was placed just now. Please check your bookings.";
            case "auth_token_invalid", "missing_auth_token" -> "Porter refused the connection. Please contact support.";
            default -> fallback;
        };
    }

    static String tenDigits(String phone) {
        String d = phone == null ? "" : phone.replaceAll("\\D", "");
        return d.length() > 10 ? d.substring(d.length() - 10) : d;
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
