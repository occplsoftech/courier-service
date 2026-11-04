package com.courier.modules.customerportal.rates.infrastructure;

import com.courier.modules.customerportal.rates.application.CustomerPortalProperties;
import com.courier.modules.customerportal.rates.domain.BookingOutcome;
import com.courier.modules.customerportal.rates.domain.BookingRequest;
import com.courier.modules.customerportal.rates.domain.ChargeComponent;
import com.courier.modules.customerportal.rates.domain.ConnectionTest;
import com.courier.modules.customerportal.rates.domain.ProviderRate;
import com.courier.modules.customerportal.rates.domain.ProviderResult;
import com.courier.modules.customerportal.rates.domain.LabelResult;
import com.courier.modules.customerportal.rates.domain.RateRequest;
import com.courier.modules.customerportal.rates.domain.ShipmentRef;
import com.courier.modules.customerportal.rates.domain.TrackingEvent;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.startsWith;

class ShiprocketAdapterTest {

    private MockRestServiceServer server;
    private ShiprocketAdapter adapter;

    static RateRequest request(RateRequest.PaymentMode mode) {
        return new RateRequest(UUID.randomUUID(), UUID.randomUUID(), "411016", "110001", new BigDecimal("2.5"),
                new BigDecimal("30"), new BigDecimal("20"), new BigDecimal("10"), 1, new BigDecimal("1500"), mode);
    }

    @BeforeEach
    void setUp() {
        CustomerPortalProperties.Shiprocket cfg = new CustomerPortalProperties.Shiprocket();
        cfg.setEnabled(true);
        cfg.setBaseUrl("https://sr.test");
        cfg.setEmail("api@shop.test");
        cfg.setPassword("secret");
        cfg.setPickupLocation("OCCPL-PUNE");
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new ShiprocketAdapter(builder, cfg);
    }

    @Test
    @DisplayName("logs in once, then maps every priced courier with its own charge lines")
    void mapsCouriers() {
        server.expect(requestTo("https://sr.test/v1/external/auth/login")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"token\":\"tkn\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith("https://sr.test/v1/external/courier/serviceability/")))
                .andExpect(header("Authorization", "Bearer tkn"))
                .andExpect(queryParam("cod", "1"))
                .andRespond(withSuccess("""
                        {"data":{"available_courier_companies":[
                          {"courier_company_id":10,"courier_name":"Xpress Air","rate":"250.40","freight_charge":"180","cod_charges":"40",
                           "estimated_delivery_days":"3","is_surface":false,"charge_weight":2.5},
                          {"courier_company_id":11,"courier_name":"No Price","rate":0}
                        ]}}""", MediaType.APPLICATION_JSON));

        ProviderResult result = adapter.getRates(request(RateRequest.PaymentMode.COD));

        assertThat(result.available()).isTrue();
        assertThat(result.rates()).hasSize(1);
        ProviderRate r = result.rates().get(0);
        assertThat(r.serviceName()).isEqualTo("Xpress Air");
        assertThat(r.transportMode()).isEqualTo("AIR");
        assertThat(r.etaMinDays()).isEqualTo(3);
        assertThat(r.providerTotal()).isEqualByComparingTo("250.40");
        assertThat(r.charges()).extracting(l -> l.component())
                .containsExactly(ChargeComponent.FREIGHT, ChargeComponent.COD, ChargeComponent.OTHER);
        server.verify();
    }

    @Test
    @DisplayName("a provider error becomes 'unavailable', never an exception or a made-up price")
    void providerError() {
        server.expect(requestTo("https://sr.test/v1/external/auth/login"))
                .andRespond(withSuccess("{\"token\":\"tkn\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith("https://sr.test/v1/external/courier/serviceability/")))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        ProviderResult result = adapter.getRates(request(RateRequest.PaymentMode.PREPAID));

        assertThat(result.available()).isFalse();
        assertThat(result.reason()).contains("Shiprocket");
    }

    @Test
    @DisplayName("without credentials nothing is called")
    void notConfigured() {
        ShiprocketAdapter off = new ShiprocketAdapter(RestClient.builder(), new CustomerPortalProperties.Shiprocket());

        assertThat(off.configured()).isFalse();
        assertThat(off.getRates(request(RateRequest.PaymentMode.PREPAID)).available()).isFalse();
    }

    static BookingRequest booking() {
        BookingRequest.Party p = new BookingRequest.Party("Ravi", "9876543211", "12 Connaught Place", "110001", "New Delhi", "Delhi");
        return new BookingRequest(UUID.randomUUID(), UUID.randomUUID(), "b-1", "10", request(RateRequest.PaymentMode.PREPAID),
                p, p, "Garments", new BigDecimal("111.80"));
    }

    private void login() {
        server.expect(requestTo("https://sr.test/v1/external/auth/login"))
                .andRespond(withSuccess("{\"token\":\"tkn\"}", MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("booking: order with our reference, then the chosen courier's AWB")
    void books() {
        login();
        server.expect(requestTo("https://sr.test/v1/external/orders/create/adhoc"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.order_id").value("b-1"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.pickup_location").value("OCCPL-PUNE"))
                .andRespond(withSuccess("{\"order_id\":555,\"shipment_id\":777}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://sr.test/v1/external/courier/assign/awb"))
                .andRespond(withSuccess("{\"response\":{\"data\":{\"awb_code\":\"SRAWB1\"}}}", MediaType.APPLICATION_JSON));

        BookingOutcome o = adapter.book(booking());

        assertThat(o.result()).isEqualTo(BookingOutcome.Result.BOOKED);
        assertThat(o.awbNumber()).isEqualTo("SRAWB1");
        assertThat(o.providerOrderRef()).isEqualTo("777");
    }

    @Test
    @DisplayName("booking: the order exists but the AWB step fails -> UNKNOWN with the Shiprocket reference, never retried")
    void halfBooked() {
        login();
        server.expect(requestTo("https://sr.test/v1/external/orders/create/adhoc"))
                .andRespond(withSuccess("{\"shipment_id\":777}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://sr.test/v1/external/courier/assign/awb"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        BookingOutcome o = adapter.book(booking());

        assertThat(o.result()).isEqualTo(BookingOutcome.Result.UNKNOWN);
        assertThat(o.providerOrderRef()).isEqualTo("777");
    }

    @Test
    @DisplayName("booking: a 4xx on the order is a definite rejection, a 5xx is unknown")
    void rejectedOrUnknown() {
        login();
        server.expect(requestTo("https://sr.test/v1/external/orders/create/adhoc")).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThat(adapter.book(booking()).result()).isEqualTo(BookingOutcome.Result.REJECTED);

        server.reset();
        server.expect(requestTo("https://sr.test/v1/external/orders/create/adhoc")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThat(adapter.book(booking()).result()).isEqualTo(BookingOutcome.Result.UNKNOWN);
    }

    private static ShipmentRef ref(String orderRef, String awb) {
        return new ShipmentRef(UUID.randomUUID(), UUID.randomUUID(), "booking-1", orderRef, awb, null);
    }

    @Test
    @DisplayName("cancel: finds the order by our exact reference and cancels it by its order id")
    void cancelsByReference() {
        login();
        server.expect(requestTo(startsWith("https://sr.test/v1/external/orders?")))
                .andExpect(queryParam("search", "TEST_BR-7"))
                .andRespond(withSuccess("{\"data\":[{\"id\":555,\"channel_order_id\":\"TEST_BR-70\",\"status\":\"NEW\"},"
                        + "{\"id\":777,\"channel_order_id\":\"TEST_BR-7\",\"status\":\"NEW\",\"shipments\":[{}]}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://sr.test/v1/external/orders/cancel")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"ids\":[777]}"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(adapter.cancelOrder(UUID.randomUUID(), null, "TEST_BR-7").result())
                .isEqualTo(com.courier.modules.customerportal.rates.domain.CancelOutcome.Result.CANCELLED);
        server.verify();
    }

    @Test
    @DisplayName("cancel: no exact match is 'not found'; a cancel error is a failure, never a clear")
    void cancelNotFoundAndFailure() {
        login();
        server.expect(requestTo(startsWith("https://sr.test/v1/external/orders?")))
                .andRespond(withSuccess("{\"data\":[{\"id\":1,\"channel_order_id\":\"OTHER\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith("https://sr.test/v1/external/orders?")))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThat(adapter.cancelOrder(UUID.randomUUID(), null, "TEST_BR-7").result())
                .isEqualTo(com.courier.modules.customerportal.rates.domain.CancelOutcome.Result.NOT_FOUND);
        assertThat(adapter.cancelOrder(UUID.randomUUID(), null, "TEST_BR-7").result())
                .isEqualTo(com.courier.modules.customerportal.rates.domain.CancelOutcome.Result.FAILED);
    }

    @Test
    @DisplayName("tracking: maps activities newest first with the current status")
    void tracksAwb() {
        server.expect(requestTo("https://sr.test/v1/external/auth/login"))
                .andRespond(withSuccess("{\"token\":\"tkn\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://sr.test/v1/external/courier/track/awb/AWB123"))
                .andExpect(header("Authorization", "Bearer tkn"))
                .andRespond(withSuccess("""
                        {"tracking_data":{"track_status":1,
                          "shipment_track":[{"awb_code":"AWB123","current_status":"In Transit"}],
                          "shipment_track_activities":[
                            {"date":"2026-10-20 09:15:00","activity":"Picked up","location":"Pune"},
                            {"date":"2026-10-21 18:40:00","activity":"Reached hub","location":"Delhi"}]}}""",
                        MediaType.APPLICATION_JSON));

        TrackingResult t = adapter.track(ref("SR-1", "AWB123"));

        assertThat(t.available()).isTrue();
        assertThat(t.status()).isEqualTo("In Transit");
        assertThat(t.currentLocation()).isEqualTo("Delhi");
        assertThat(t.events()).extracting(TrackingEvent::status).containsExactly("Reached hub", "Picked up");
        assertThat(t.events().get(1).at()).isEqualTo(java.time.Instant.parse("2026-10-20T03:45:00Z"));
        server.verify();
    }

    @Test
    @DisplayName("tracking: no AWB means nothing to ask Shiprocket")
    void noAwbNoCall() {
        assertThat(adapter.track(ref("SR-1", null)).available()).isFalse();
        server.verify();
    }

    @Test
    @DisplayName("tracking: a Shiprocket error is a readable 'not available', not an exception")
    void trackingError() {
        server.expect(requestTo("https://sr.test/v1/external/auth/login"))
                .andRespond(withSuccess("{\"token\":\"tkn\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://sr.test/v1/external/courier/track/awb/AWB123"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        TrackingResult t = adapter.track(ref("SR-1", "AWB123"));

        assertThat(t.available()).isFalse();
        assertThat(t.message()).contains("try again");
    }

    @Test
    @DisplayName("label: asks for the shipment's label and returns Shiprocket's URL")
    void label() {
        server.expect(requestTo("https://sr.test/v1/external/auth/login"))
                .andRespond(withSuccess("{\"token\":\"tkn\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://sr.test/v1/external/courier/generate/label"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"shipment_id\":[\"SR-1\"]}"))
                .andRespond(withSuccess("{\"label_created\":1,\"label_url\":\"https://kr-ship.s3.amazonaws.com/l.pdf\"}",
                        MediaType.APPLICATION_JSON));

        LabelResult label = adapter.label(ref("SR-1", "AWB123"));

        assertThat(label.available()).isTrue();
        assertThat(label.url()).isEqualTo("https://kr-ship.s3.amazonaws.com/l.pdf");
        assertThat(adapter.labelHosts()).contains(".amazonaws.com");
        server.verify();
    }

    private static CustomerPortalProperties.Shiprocket account(String email, String password, String pickup) {
        CustomerPortalProperties.Shiprocket c = new CustomerPortalProperties.Shiprocket();
        c.setEnabled(true);
        c.setBaseUrl("https://sr.test");
        c.setEmail(email);
        c.setPassword(password);
        c.setPickupLocation(pickup);
        return c;
    }

    private ShiprocketAdapter perCompany(java.util.Map<UUID, CustomerPortalProperties.Shiprocket> accounts, MockRestServiceServer[] out) {
        CustomerPortalProperties.Shiprocket env = account("env@x.test", "env", null);
        RestClient.Builder builder = RestClient.builder();
        out[0] = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        return new ShiprocketAdapter(builder, env, companyId -> accounts.getOrDefault(companyId, env));
    }

    @Test
    @DisplayName("each company logs in with its own account and keeps its own token; a changed password logs in again")
    void perCompanyLogin() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        java.util.Map<UUID, CustomerPortalProperties.Shiprocket> accounts = new java.util.HashMap<>();
        accounts.put(a, account("a@shop.test", "pa", null));
        accounts.put(b, account("b@shop.test", "pb", null));
        MockRestServiceServer[] srv = new MockRestServiceServer[1];
        ShiprocketAdapter adapter = perCompany(accounts, srv);
        srv[0].expect(requestTo("https://sr.test/v1/external/auth/login")).andExpect(content().json("{\"email\":\"a@shop.test\",\"password\":\"pa\"}"))
                .andRespond(withSuccess("{\"token\":\"tok-a\"}", MediaType.APPLICATION_JSON));
        srv[0].expect(requestTo("https://sr.test/v1/external/auth/login")).andExpect(content().json("{\"email\":\"b@shop.test\",\"password\":\"pb\"}"))
                .andRespond(withSuccess("{\"token\":\"tok-b\"}", MediaType.APPLICATION_JSON));
        srv[0].expect(requestTo(startsWith("https://sr.test/v1/external/courier/serviceability/"))).andExpect(header("Authorization", "Bearer tok-a"))
                .andRespond(withSuccess("{\"data\":{\"available_courier_companies\":[]}}", MediaType.APPLICATION_JSON));
        srv[0].expect(requestTo(startsWith("https://sr.test/v1/external/courier/serviceability/"))).andExpect(header("Authorization", "Bearer tok-b"))
                .andRespond(withSuccess("{\"data\":{\"available_courier_companies\":[]}}", MediaType.APPLICATION_JSON));

        RateRequest ra = new RateRequest(a, UUID.randomUUID(), "411016", "110001", new BigDecimal("1"), BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.TEN, 1, new BigDecimal("100"), RateRequest.PaymentMode.PREPAID);
        RateRequest rb = new RateRequest(b, ra.customerId(), "411016", "110001", new BigDecimal("1"), BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.TEN, 1, new BigDecimal("100"), RateRequest.PaymentMode.PREPAID);
        adapter.getRates(ra);
        adapter.getRates(rb);
        srv[0].verify();

        // Company A changes its password: the next call logs in again with the new one (the old token is not reused).
        accounts.put(a, account("a@shop.test", "pa2", null));
        srv[0].reset();
        srv[0].expect(requestTo("https://sr.test/v1/external/auth/login")).andExpect(content().json("{\"email\":\"a@shop.test\",\"password\":\"pa2\"}"))
                .andRespond(withSuccess("{\"token\":\"tok-a2\"}", MediaType.APPLICATION_JSON));
        srv[0].expect(requestTo(startsWith("https://sr.test/v1/external/courier/serviceability/"))).andExpect(header("Authorization", "Bearer tok-a2"))
                .andRespond(withSuccess("{\"data\":{\"available_courier_companies\":[]}}", MediaType.APPLICATION_JSON));
        adapter.getRates(ra);
        srv[0].verify();
    }

    @Test
    @DisplayName("a company whose courier is switched off is not configured, whatever the server has")
    void perCompanyConfigured() {
        UUID off = UUID.randomUUID();
        CustomerPortalProperties.Shiprocket disabled = account("a@shop.test", "pa", null);
        disabled.setEnabled(false);
        MockRestServiceServer[] srv = new MockRestServiceServer[1];
        ShiprocketAdapter adapter = perCompany(java.util.Map.of(off, disabled), srv);

        assertThat(adapter.configured(off)).isFalse();
        assertThat(adapter.configured(UUID.randomUUID())).isTrue();
    }

    @Test
    @DisplayName("connection test: login accepted and the pickup location exists")
    void connectionOk() {
        UUID c = UUID.randomUUID();
        MockRestServiceServer[] srv = new MockRestServiceServer[1];
        ShiprocketAdapter adapter = perCompany(java.util.Map.of(c, account("a@shop.test", "pa", "PUNE-1")), srv);
        srv[0].expect(requestTo("https://sr.test/v1/external/auth/login")).andRespond(withSuccess("{\"token\":\"t\"}", MediaType.APPLICATION_JSON));
        srv[0].expect(requestTo("https://sr.test/v1/external/settings/company/pickup")).andExpect(header("Authorization", "Bearer t"))
                .andRespond(withSuccess("{\"data\":{\"shipping_address\":[{\"pickup_location\":\"pune-1\"}]}}", MediaType.APPLICATION_JSON));

        ConnectionTest t = adapter.testConnection(c);

        assertThat(t.ok()).isTrue();
        srv[0].verify();
    }

    @Test
    @DisplayName("connection test: names the pickup location that is not registered, without echoing the password")
    void connectionPickupMissing() {
        UUID c = UUID.randomUUID();
        MockRestServiceServer[] srv = new MockRestServiceServer[1];
        ShiprocketAdapter adapter = perCompany(java.util.Map.of(c, account("a@shop.test", "pa-secret", "NOPE")), srv);
        srv[0].expect(requestTo("https://sr.test/v1/external/auth/login")).andRespond(withSuccess("{\"token\":\"t\"}", MediaType.APPLICATION_JSON));
        srv[0].expect(requestTo("https://sr.test/v1/external/settings/company/pickup"))
                .andRespond(withSuccess("{\"data\":{\"shipping_address\":[{\"pickup_location\":\"PUNE-1\"}]}}", MediaType.APPLICATION_JSON));

        ConnectionTest t = adapter.testConnection(c);

        assertThat(t.ok()).isFalse();
        assertThat(t.message()).contains("NOPE").doesNotContain("pa-secret");
    }

    @Test
    @DisplayName("connection test: wrong password and an unreachable Shiprocket are readable failures")
    void connectionFailures() {
        UUID c = UUID.randomUUID();
        MockRestServiceServer[] srv = new MockRestServiceServer[1];
        ShiprocketAdapter adapter = perCompany(java.util.Map.of(c, account("a@shop.test", "bad", "P")), srv);
        srv[0].expect(requestTo("https://sr.test/v1/external/auth/login")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThat(adapter.testConnection(c).message()).contains("did not accept");

        srv[0].reset();
        srv[0].expect(requestTo("https://sr.test/v1/external/auth/login")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThat(adapter.testConnection(c).message()).contains("error");
    }
}
