package com.courier.modules.customerportal.rates.infrastructure;

import com.courier.modules.company.application.geocoding.GeocodingPort;
import com.courier.modules.customerportal.rates.application.CustomerPortalProperties;
import com.courier.modules.customerportal.rates.domain.BookingOutcome;
import com.courier.modules.customerportal.rates.domain.BookingRequest;
import com.courier.modules.customerportal.rates.domain.ConnectionTest;
import com.courier.modules.customerportal.rates.domain.DeliveryStage;
import com.courier.modules.customerportal.rates.domain.ProviderRate;
import com.courier.modules.customerportal.rates.domain.ProviderResult;
import com.courier.modules.customerportal.rates.domain.RateRequest;
import com.courier.modules.customerportal.rates.domain.ShipmentRef;
import com.courier.modules.customerportal.rates.domain.TrackingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Answers from the Porter API Contract 1.0 samples. */
class PorterAdapterTest {

    private static final String UAT = "https://pfe-apigw-uat.porter.in";
    private static final UUID COMPANY = UUID.randomUUID();

    private static final String QUOTE = """
            {"vehicles":[
             {"type":"2 Wheeler","eta":{"value":10,"unit":"minute"},"fare":{"currency":"INR","minor_amount":35000},
              "capacity":{"value":20,"unit":"kg"},
              "size":{"length":{"value":40,"unit":"cm"},"breadth":{"value":40,"unit":"cm"},"height":{"value":40,"unit":"cm"}}},
             {"type":"3 Wheeler","eta":null,"fare":{"currency":"INR","minor_amount":36100},"capacity":{"value":1,"unit":"ton"},
              "size":{"length":{"value":7.2,"unit":"ft"},"breadth":{"value":4.9,"unit":"ft"},"height":{"value":4.9,"unit":"ft"}}}]}""";

    private static final String LIVE = """
            {"order_id":"CRN93814651","status":"live",
             "partner_info":{"name":"Anupam Patel","vehicle_number":"AK-02-HH-2020","vehicle_type":"TWO_WHEELER",
               "mobile":{"country_code":"91","mobile_number":"9535321734"},"location":{"lat":12.934672,"long":77.6093797}},
             "order_timings":{"pickup_time":1669879581,"order_accepted_time":1669877932,"order_started_time":1669877997,"order_ended_time":null},
             "fare_details":{"estimated_fare_details":{"currency":"INR","minor_amount":8400},"actual_fare_details":null}}""";

    private final Map<String, GeocodingPort.Coordinates> map = new HashMap<>();
    private int lookups;
    private MockRestServiceServer server;
    private PorterAdapter adapter;
    private CustomerPortalProperties.Porter cfg;
    private Instant now = Instant.parse("2026-11-03T10:00:00Z");

    @BeforeEach
    void setUp() {
        cfg = new CustomerPortalProperties.Porter();
        cfg.setEnabled(true);
        cfg.setTestMode(true);
        cfg.setApiKey("key-1");
        cfg.setMobile("98765 43210");
        map.put("411001", new GeocodingPort.Coordinates(new BigDecimal("18.5204"), new BigDecimal("73.8567")));
        map.put("411057", new GeocodingPort.Coordinates(new BigDecimal("18.5912"), new BigDecimal("73.7389")));
        map.put("400001", new GeocodingPort.Coordinates(new BigDecimal("18.9388"), new BigDecimal("72.8354")));
        GeocodingPort geocoder = q -> {
            lookups++;
            return Optional.ofNullable(map.get(q.postalCode()));
        };
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new PorterAdapter(builder, cfg, id -> cfg, geocoder, new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now; }
        });
    }

    private static RateRequest rate(String from, String to, String kg, String l, String b, String h) {
        return new RateRequest(COMPANY, UUID.randomUUID(), from, to, new BigDecimal(kg), l == null ? null : new BigDecimal(l),
                b == null ? null : new BigDecimal(b), h == null ? null : new BigDecimal(h), 1, new BigDecimal("500"),
                RateRequest.PaymentMode.PREPAID);
    }

    @Test
    @DisplayName("quote: asks Porter's test system with the pincodes' map points and offers only the 2-wheeler, paise as rupees")
    void quote() {
        server.expect(requestTo(UAT + "/v1/get_quote")).andExpect(method(HttpMethod.POST))
                .andExpect(header("x-api-key", "key-1"))
                .andExpect(jsonPath("$.pickup_details.lat").value(18.5204))
                .andExpect(jsonPath("$.drop_details.lng").value(73.7389))
                .andExpect(jsonPath("$.customer.mobile.country_code").value("+91"))
                .andExpect(jsonPath("$.customer.mobile.number").value("9876543210"))
                .andRespond(withSuccess(QUOTE, MediaType.APPLICATION_JSON));

        ProviderResult r = adapter.getRates(rate("411001", "411057", "2", "30", "20", "10"));

        server.verify();
        assertThat(r.available()).isTrue();
        assertThat(r.rates()).hasSize(1);
        ProviderRate only = r.rates().get(0);
        assertThat(only.serviceCode()).isEqualTo("PORTER_2W");
        assertThat(only.providerTotal()).isEqualByComparingTo("350.00");
        assertThat(only.note()).contains("10 minutes");
    }

    @Test
    @DisplayName("quote: pickup and drop in different cities are not sent to Porter, and pincodes are looked up once")
    void otherCity() {
        server.expect(never(), requestTo(UAT + "/v1/get_quote"));

        ProviderResult first = adapter.getRates(rate("411001", "400001", "2", null, null, null));
        adapter.getRates(rate("411001", "400001", "2", null, null, null));

        assertThat(first.available()).isFalse();
        assertThat(first.reason()).isEqualTo("Porter delivers within one city only.");
        assertThat(lookups).isEqualTo(2);
        server.verify();
    }

    @Test
    @DisplayName("quote: a parcel heavier or larger than the 2-wheeler's limits is not offered")
    void tooBig() {
        server.expect(requestTo(UAT + "/v1/get_quote")).andRespond(withSuccess(QUOTE, MediaType.APPLICATION_JSON));
        server.expect(requestTo(UAT + "/v1/get_quote")).andRespond(withSuccess(QUOTE, MediaType.APPLICATION_JSON));

        assertThat(adapter.getRates(rate("411001", "411057", "25", null, null, null)).reason()).contains("Too heavy").contains("20 kg");
        assertThat(adapter.getRates(rate("411001", "411057", "5", "60", "20", "10")).reason()).contains("Too large");
    }

    @Test
    @DisplayName("quote: Porter's refusal is put in plain words")
    void refused() {
        server.expect(requestTo(UAT + "/v1/get_quote")).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"type\":\"restricted_location\",\"message\":\"restricted drop location\"}"));

        ProviderResult r = adapter.getRates(rate("411001", "411057", "2", null, null, null));

        assertThat(r.available()).isFalse();
        assertThat(r.reason()).isEqualTo("Porter does not serve this pickup or delivery location.");
    }

    private BookingRequest booking(BookingRequest.Party pickup, BookingRequest.Party drop) {
        return new BookingRequest(COMPANY, UUID.randomUUID(), "0b6f2a7e-1c3d-4e5f-8a9b-0c1d2e3f4a5b", "PORTER_2W",
                rate("411001", "411057", "2", null, null, null), pickup, drop, "Documents", new BigDecimal("413.00"));
    }

    @Test
    @DisplayName("booking: sends the pins (6 decimals), +91 phones and a 32-character request id; the CRN is the tracking number")
    void book() {
        server.expect(requestTo(UAT + "/v1/orders/create")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.request_id").value("0b6f2a7e1c3d4e5f8a9b0c1d2e3f4a5b"))
                .andExpect(jsonPath("$.pickup_details.address.lat").value(18.520412))
                .andExpect(jsonPath("$.pickup_details.address.street_address1").value("Flat 4, FC Road"))
                .andExpect(jsonPath("$.pickup_details.address.contact_details.phone_number").value("+919876543211"))
                .andExpect(jsonPath("$.drop_details.address.lat").value(18.5912))
                .andExpect(jsonPath("$.delivery_instructions.instructions_list[0].description").value("Contents: Documents"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"request_id":"0b6f2a7e1c3d4e5f8a9b0c1d2e3f4a5b","order_id":"CRN17855725","estimated_pickup_time":1642473111,
                         "estimated_fare_details":{"currency":"INR","minor_amount":35000},
                         "tracking_url":"https://porter.in/track_live_order?booking_id=CRN17855725"}"""));

        BookingOutcome out = adapter.book(booking(
                new BookingRequest.Party("Asha", "+91 98765 43211", "Flat 4, FC Road", "411001", "Pune", "Maharashtra",
                        new BigDecimal("18.5204129"), new BigDecimal("73.8567001")),
                new BookingRequest.Party("Ravi", "9876543212", "Wakad", "411057", "Pune", "Maharashtra")));

        server.verify();
        assertThat(out.result()).isEqualTo(BookingOutcome.Result.BOOKED);
        assertThat(out.providerOrderRef()).isEqualTo("CRN17855725");
        assertThat(out.awbNumber()).isEqualTo("CRN17855725");
        assertThat(out.message()).contains("https://porter.in/track_live_order");
    }

    @Test
    @DisplayName("booking: no free rider is a refusal; a server error or a duplicate request id may have created the order")
    void bookOutcomes() {
        BookingRequest.Party a = new BookingRequest.Party("Asha", "9876543211", "FC Road", "411001", "Pune", "Maharashtra");
        BookingRequest.Party b = new BookingRequest.Party("Ravi", "9876543212", "Wakad", "411057", "Pune", "Maharashtra");
        server.expect(requestTo(UAT + "/v1/orders/create")).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_JSON).body("{\"type\":\"vehicle_not_found\",\"message\":\"No drivers\"}"));
        server.expect(requestTo(UAT + "/v1/orders/create")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        server.expect(requestTo(UAT + "/v1/orders/create")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"type\":\"duplicate_request\",\"message\":\"already processed\"}"));

        BookingOutcome noRider = adapter.book(booking(a, b));
        BookingOutcome error = adapter.book(booking(a, b));
        BookingOutcome duplicate = adapter.book(booking(a, b));

        assertThat(noRider.result()).isEqualTo(BookingOutcome.Result.REJECTED);
        assertThat(noRider.message()).contains("No Porter rider is free nearby");
        assertThat(error.result()).isEqualTo(BookingOutcome.Result.UNKNOWN);
        assertThat(duplicate.result()).isEqualTo(BookingOutcome.Result.UNKNOWN);
    }

    @Test
    @DisplayName("booking: a sender without a 10-digit mobile is refused before Porter is asked")
    void badPhone() {
        server.expect(never(), requestTo(UAT + "/v1/orders/create"));

        BookingOutcome out = adapter.book(booking(
                new BookingRequest.Party("Asha", "12345", "FC Road", "411001", "Pune", "Maharashtra"),
                new BookingRequest.Party("Ravi", "9876543212", "Wakad", "411057", "Pune", "Maharashtra")));

        assertThat(out.result()).isEqualTo(BookingOutcome.Result.REJECTED);
        server.verify();
    }

    @Test
    @DisplayName("tracking: rider and status in words, events newest first; asked at most once a minute per order")
    void track() {
        server.expect(requestTo(UAT + "/v1.1/orders/CRN93814651")).andExpect(method(HttpMethod.GET))
                .andExpect(header("x-api-key", "key-1"))
                .andRespond(withSuccess(LIVE, MediaType.APPLICATION_JSON));
        ShipmentRef ref = new ShipmentRef(COMPANY, UUID.randomUUID(), "b1", "CRN93814651", "CRN93814651", null);

        TrackingResult t = adapter.track(ref);
        TrackingResult again = adapter.track(ref);

        server.verify();
        assertThat(t.available()).isTrue();
        assertThat(t.status()).isEqualTo("Picked up · out for delivery · Rider Anupam Patel, AK-02-HH-2020, 9535321734");
        assertThat(DeliveryStage.of(t.status())).isEqualTo(DeliveryStage.OUT_FOR_DELIVERY);
        assertThat(t.events()).extracting(e -> e.status()).containsExactly("Picked up · out for delivery", "Rider assigned");
        assertThat(again).isSameAs(t);
    }

    @Test
    @DisplayName("Porter's statuses map onto the delivery stages")
    void stages() {
        assertThat(DeliveryStage.of(PorterAdapter.describe("open"))).isEqualTo(DeliveryStage.AWAITING_PICKUP);
        assertThat(DeliveryStage.of(PorterAdapter.describe("order_accepted"))).isEqualTo(DeliveryStage.AWAITING_PICKUP);
        assertThat(DeliveryStage.of(PorterAdapter.describe("order_start_trip"))).isEqualTo(DeliveryStage.OUT_FOR_DELIVERY);
        assertThat(DeliveryStage.of(PorterAdapter.describe("ended"))).isEqualTo(DeliveryStage.DELIVERED);
        assertThat(DeliveryStage.of(PorterAdapter.describe("order_end_job"))).isEqualTo(DeliveryStage.DELIVERED);
        assertThat(DeliveryStage.of(PorterAdapter.describe("order_reopen"))).isEqualTo(DeliveryStage.AWAITING_PICKUP);
        assertThat(DeliveryStage.of(PorterAdapter.describe("order_cancel"))).isEqualTo(DeliveryStage.CANCELLED);
    }

    @Test
    @DisplayName("connection test: a refused key fails and names the system; an accepted one passes")
    void connection() {
        server.expect(requestTo(UAT + "/v1/get_quote")).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON).body("{\"type\":\"auth_token_invalid\",\"message\":\"Invalid Api Token\"}"));
        server.expect(requestTo(UAT + "/v1/get_quote")).andRespond(withSuccess(QUOTE, MediaType.APPLICATION_JSON));

        ConnectionTest refused = adapter.testConnection(COMPANY);
        ConnectionTest ok = adapter.testConnection(COMPANY);

        assertThat(refused.ok()).isFalse();
        assertThat(refused.message()).contains("test system");
        assertThat(ok.ok()).isTrue();
    }

    @Test
    @DisplayName("live system unless TEST is chosen")
    void liveHost() {
        cfg.setTestMode(false);
        server.expect(requestTo("https://pfe-apigw.porter.in/v1/get_quote")).andRespond(withSuccess(QUOTE, MediaType.APPLICATION_JSON));

        assertThat(adapter.getRates(rate("411001", "411057", "2", null, null, null)).available()).isTrue();
        server.verify();
    }
}
