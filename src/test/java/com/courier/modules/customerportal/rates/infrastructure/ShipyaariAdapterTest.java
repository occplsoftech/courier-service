package com.courier.modules.customerportal.rates.infrastructure;

import com.courier.modules.customerportal.rates.application.CustomerPortalProperties;
import com.courier.modules.customerportal.rates.domain.BookingOutcome;
import com.courier.modules.customerportal.rates.domain.BookingRequest;
import com.courier.modules.customerportal.rates.domain.ChargeComponent;
import com.courier.modules.customerportal.rates.domain.ConnectionTest;
import com.courier.modules.customerportal.rates.domain.LabelResult;
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

import java.io.IOException;
import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ShipyaariAdapterTest {

    private static final String LOGIN_OK = "{\"success\":true,\"data\":[{\"sellerId\":1,\"token\":\"tkn\"}],\"message\":\"Seller Signed In Successfully.\"}";

    private MockRestServiceServer server;
    private ShipyaariAdapter adapter;

    static RateRequest rate(RateRequest.PaymentMode mode, int packages) {
        return new RateRequest(UUID.randomUUID(), UUID.randomUUID(), "411016", "110001", new BigDecimal("2.5"),
                new BigDecimal("30"), new BigDecimal("20"), new BigDecimal("10"), packages, new BigDecimal("1000"), mode);
    }

    static BookingRequest booking(String serviceCode, RateRequest rate, String pickupPhone) {
        return new BookingRequest(rate.companyId(), rate.customerId(), "11111111-2222-3333-4444-555555555555", serviceCode, rate,
                new BookingRequest.Party("Asha", pickupPhone, "Office 4, Thorat Chowk", "411016", "Pune", "Maharashtra"),
                new BookingRequest.Party("Ravi", "+91 98765 43210", "12 MG Road", "110001", "Delhi", "Delhi"), "Garments",
                new BigDecimal("111.80"));
    }

    @BeforeEach
    void setUp() {
        CustomerPortalProperties.Shipyaari cfg = new CustomerPortalProperties.Shipyaari();
        cfg.setEnabled(true);
        cfg.setBaseUrl("https://sy.test");
        cfg.setEmail("api@shop.test");
        cfg.setPassword("secret");
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new ShipyaariAdapter(builder, cfg);
    }

    private void login() {
        server.expect(requestTo("https://sy.test/api/v1/seller/signIn")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.email").value("api@shop.test"))
                .andExpect(jsonPath("$.password").value("secret"))
                .andRespond(withSuccess(LOGIN_OK, MediaType.APPLICATION_JSON));
    }

    // ------------------------------------------------------------------ rates

    @Test
    @DisplayName("logs in, asks for serviceability, and maps every priced service with its charge lines")
    void mapsServices() {
        login();
        server.expect(requestTo("https://sy.test/api/v1/order/checkServiceabilityV2")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tkn"))
                .andExpect(jsonPath("$.pickupPincode").value(411016))
                .andExpect(jsonPath("$.deliveryPincode").value(110001))
                .andExpect(jsonPath("$.paymentMode").value("COD"))
                .andExpect(jsonPath("$.weight").value(2.5))
                .andExpect(jsonPath("$.dimension.width").value(20))
                .andRespond(withSuccess("""
                        {"success":true,"data":[
                          {"partnerServiceId":"8a6cec14","partnerName":"DTDC","partnerServiceName":"DTDC-B2C","companyServiceName":"ECONOMY",
                           "serviceMode":"SURFACE","appliedWeight":3,"base":100,"add":20,"variables":0,"cod":30,"insurance":0,"tax":27,
                           "total":177,"EDT":5},
                          {"partnerName":"DTDC","companyServiceName":"ECONOMY","serviceMode":"SURFACE","total":177},
                          {"partnerName":"BLUEDART","companyServiceName":"STANDARD","serviceMode":"AIR","total":0},
                          {"partnerName":"DELHIVERY","companyServiceName":"STANDARD","serviceMode":"AIR","base":200,"total":236,"EDT":3}
                        ]}""", MediaType.APPLICATION_JSON));

        ProviderResult result = adapter.getRates(rate(RateRequest.PaymentMode.COD, 1));

        assertThat(result.available()).isTrue();
        assertThat(result.rates()).as("a zero price and a repeated service are dropped").hasSize(2);
        ProviderRate dtdc = result.rates().get(0);
        assertThat(dtdc.serviceCode()).isEqualTo("DTDC|ECONOMY|SURFACE");
        assertThat(dtdc.serviceName()).isEqualTo("DTDC Economy (Surface)");
        assertThat(dtdc.transportMode()).isEqualTo("SURFACE");
        assertThat(dtdc.etaMinDays()).isEqualTo(5);
        assertThat(dtdc.chargeableWeightKg()).isEqualByComparingTo("3");
        assertThat(dtdc.providerQuoteRef()).isEqualTo("8a6cec14");
        assertThat(dtdc.providerTotal()).isEqualByComparingTo("177");
        assertThat(dtdc.charges()).extracting(c -> c.component())
                .containsExactly(ChargeComponent.FREIGHT, ChargeComponent.FREIGHT, ChargeComponent.COD, ChargeComponent.TAX);
        ProviderRate delhivery = result.rates().get(1);
        assertThat(delhivery.providerTotal()).as("lines always add up to the quoted total").isEqualByComparingTo("236");
    }

    @Test
    @DisplayName("prepaid shipments are asked as PREPAID and their COD charge is not shown")
    void prepaid() {
        login();
        server.expect(requestTo("https://sy.test/api/v1/order/checkServiceabilityV2"))
                .andExpect(jsonPath("$.paymentMode").value("PREPAID"))
                .andRespond(withSuccess("""
                        {"success":true,"data":[{"partnerName":"DTDC","companyServiceName":"ECONOMY","serviceMode":"SURFACE",
                          "base":100,"cod":30,"tax":18,"total":148}]}""", MediaType.APPLICATION_JSON));

        ProviderRate r = adapter.getRates(rate(RateRequest.PaymentMode.PREPAID, 1)).rates().get(0);

        assertThat(r.charges()).extracting(c -> c.component()).doesNotContain(ChargeComponent.COD);
        assertThat(r.providerTotal()).isEqualByComparingTo("148");
    }

    @Test
    @DisplayName("an error from Shipyaari or an unreachable server is 'unavailable', never an exception")
    void ratesFailSoftly() {
        login();
        server.expect(requestTo("https://sy.test/api/v1/order/checkServiceabilityV2")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        ProviderResult result = adapter.getRates(rate(RateRequest.PaymentMode.PREPAID, 1));

        assertThat(result.available()).isFalse();
        assertThat(result.reason()).doesNotContain("secret");
    }

    @Test
    @DisplayName("not connected: no call is made")
    void notConnected() {
        CustomerPortalProperties.Shipyaari cfg = new CustomerPortalProperties.Shipyaari();
        ShipyaariAdapter off = new ShipyaariAdapter(RestClient.builder(), cfg);

        assertThat(off.getRates(rate(RateRequest.PaymentMode.PREPAID, 1)).available()).isFalse();
        assertThat(off.book(booking("DTDC|ECONOMY|SURFACE", rate(RateRequest.PaymentMode.PREPAID, 1), "9876543210")).result())
                .isEqualTo(BookingOutcome.Result.REJECTED);
        assertThat(off.configured()).isFalse();
    }

    @Test
    @DisplayName("one login serves later calls")
    void tokenReused() {
        login();
        server.expect(requestTo("https://sy.test/api/v1/order/checkServiceabilityV2"))
                .andRespond(withSuccess("{\"success\":true,\"data\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://sy.test/api/v1/order/checkServiceabilityV2"))
                .andRespond(withSuccess("{\"success\":true,\"data\":[]}", MediaType.APPLICATION_JSON));

        adapter.getRates(rate(RateRequest.PaymentMode.PREPAID, 1));
        adapter.getRates(rate(RateRequest.PaymentMode.PREPAID, 1));

        server.verify(); // exactly one login was expected
    }

    @Test
    @DisplayName("a 401 drops the stored login, so the next call signs in again")
    void tokenRenewedAfter401() {
        login();
        server.expect(requestTo("https://sy.test/api/v1/order/checkServiceabilityV2")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        login();
        server.expect(requestTo("https://sy.test/api/v1/order/checkServiceabilityV2"))
                .andRespond(withSuccess("{\"success\":true,\"data\":[{\"partnerName\":\"DTDC\",\"companyServiceName\":\"ECONOMY\",\"total\":50}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(adapter.getRates(rate(RateRequest.PaymentMode.PREPAID, 1)).available()).isFalse();
        assertThat(adapter.getRates(rate(RateRequest.PaymentMode.PREPAID, 1)).available()).isTrue();

        server.verify();
    }

    // ---------------------------------------------------------------- booking

    private static final String BOOKED = """
            {"success":true,"data":[{"orderId":159398,"shipyaariId":1717767675374,"orderType":"B2C",
              "awbs":[{"tracking":{"awb":"77090047322","label":"","status":[{"currentStatus":"BOOKED"}]},
                       "charges":{"partnerName":"DTDC","companyServiceName":"ECONOMY","total":177}}]}],
             "message":"B2C - 159398 : Placed Successfully"}""";

    @Test
    @DisplayName("books in one call with the customer's own pickup, the chosen service and our booking id as the order id")
    void books() {
        login();
        server.expect(requestTo("https://sy.test/api/v1/order/placeOrderApiV3")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tkn"))
                .andExpect(jsonPath("$.orderId").value("11111111-2222-3333-4444-555555555555"))
                .andExpect(jsonPath("$.orderType").value("B2C"))
                .andExpect(jsonPath("$.transit").value("FORWARD"))
                .andExpect(jsonPath("$.courierPartner").value("DTDC"))
                .andExpect(jsonPath("$.courierPartnerServices").value("ECONOMY"))
                .andExpect(jsonPath("$.serviceMode").value("SURFACE"))
                .andExpect(jsonPath("$.pickupDetails.pincode").value(411016))
                .andExpect(jsonPath("$.pickupDetails.fullAddress").value("Office 4, Thorat Chowk, Pune, Maharashtra 411016"))
                .andExpect(jsonPath("$.pickupDetails.contact.mobileNo").value(9876543210L))
                .andExpect(jsonPath("$.deliveryDetails.contact.mobileNo").value(9876543210L))
                .andExpect(jsonPath("$.boxInfo.length()").value(1))
                .andExpect(jsonPath("$.boxInfo[0].deadWeight").value(2.5))
                .andExpect(jsonPath("$.boxInfo[0].measureUnit").value("cm"))
                .andExpect(jsonPath("$.boxInfo[0].codInfo.isCod").value(false))
                .andExpect(jsonPath("$.boxInfo[0].codInfo.invoiceValue").value(1000))
                .andExpect(jsonPath("$.boxInfo[0].products[0].name").value("Garments"))
                .andRespond(withSuccess(BOOKED, MediaType.APPLICATION_JSON));

        BookingOutcome outcome = adapter.book(booking("DTDC|ECONOMY|SURFACE", rate(RateRequest.PaymentMode.PREPAID, 1), "9876543210"));

        assertThat(outcome.result()).isEqualTo(BookingOutcome.Result.BOOKED);
        assertThat(outcome.providerOrderRef()).isEqualTo("159398");
        assertThat(outcome.awbNumber()).isEqualTo("77090047322");
        assertThat(outcome.message()).contains("DTDC");
        server.verify();
    }

    @Test
    @DisplayName("COD collects the declared value; several packages are split so they add up to it exactly")
    void codAndPackages() {
        login();
        server.expect(requestTo("https://sy.test/api/v1/order/placeOrderApiV3"))
                .andExpect(jsonPath("$.boxInfo.length()").value(3))
                .andExpect(jsonPath("$.boxInfo[0].codInfo.isCod").value(true))
                .andExpect(jsonPath("$.boxInfo[0].codInfo.collectableAmount").value(333.33))
                .andExpect(jsonPath("$.boxInfo[2].codInfo.collectableAmount").value(333.34))
                .andExpect(jsonPath("$.boxInfo[0].deadWeight").value(0.833))
                .andRespond(withSuccess(BOOKED, MediaType.APPLICATION_JSON));

        adapter.book(booking("DTDC|ECONOMY|SURFACE", rate(RateRequest.PaymentMode.COD, 3), "9876543210"));

        server.verify();
    }

    @Test
    @DisplayName("a 4xx refusal is REJECTED and shows the customer no Shipyaari wallet detail")
    void refusedIsRejected() {
        login();
        server.expect(requestTo("https://sy.test/api/v1/order/placeOrderApiV3"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":false,\"data\":[],\"message\":\"Insufficient wallet balance. Kindly recharge.\"}"));

        BookingOutcome outcome = adapter.book(booking("DTDC|ECONOMY|SURFACE", rate(RateRequest.PaymentMode.PREPAID, 1), "9876543210"));

        assertThat(outcome.result()).isEqualTo(BookingOutcome.Result.REJECTED);
        assertThat(outcome.message()).doesNotContainIgnoringCase("wallet");
    }

    @Test
    @DisplayName("a 200 with success:false is also a certain refusal")
    void successFalse() {
        login();
        server.expect(requestTo("https://sy.test/api/v1/order/placeOrderApiV3"))
                .andRespond(withSuccess("{\"success\":false,\"data\":[],\"message\":\"Please Provide Valid Courier Partner Name\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(adapter.book(booking("DTDC|ECONOMY|SURFACE", rate(RateRequest.PaymentMode.PREPAID, 1), "9876543210")).result())
                .isEqualTo(BookingOutcome.Result.REJECTED);
    }

    @Test
    @DisplayName("a server error or a timeout is UNKNOWN: the order may exist")
    void doubtIsUnknown() {
        login();
        server.expect(requestTo("https://sy.test/api/v1/order/placeOrderApiV3")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThat(adapter.book(booking("DTDC|ECONOMY|SURFACE", rate(RateRequest.PaymentMode.PREPAID, 1), "9876543210")).result())
                .isEqualTo(BookingOutcome.Result.UNKNOWN);

        server.reset(); // the login is remembered, so only the order call is expected again
        server.expect(requestTo("https://sy.test/api/v1/order/placeOrderApiV3")).andRespond(request -> {
            throw new IOException("read timed out");
        });
        assertThat(adapter.book(booking("DTDC|ECONOMY|SURFACE", rate(RateRequest.PaymentMode.PREPAID, 1), "9876543210")).result())
                .isEqualTo(BookingOutcome.Result.UNKNOWN);
    }

    @Test
    @DisplayName("an order created without a tracking number is UNKNOWN and keeps Shipyaari's order id")
    void noAwb() {
        login();
        server.expect(requestTo("https://sy.test/api/v1/order/placeOrderApiV3"))
                .andRespond(withSuccess("{\"success\":true,\"data\":[{\"orderId\":42,\"awbs\":[]}]}", MediaType.APPLICATION_JSON));

        BookingOutcome outcome = adapter.book(booking("DTDC|ECONOMY|SURFACE", rate(RateRequest.PaymentMode.PREPAID, 1), "9876543210"));

        assertThat(outcome.result()).isEqualTo(BookingOutcome.Result.UNKNOWN);
        assertThat(outcome.providerOrderRef()).isEqualTo("42");
    }

    @Test
    @DisplayName("a mobile number that is not ten digits is refused before anything is sent")
    void badMobile() {
        BookingOutcome outcome = adapter.book(booking("DTDC|ECONOMY|SURFACE", rate(RateRequest.PaymentMode.PREPAID, 1), "12345"));

        assertThat(outcome.result()).isEqualTo(BookingOutcome.Result.REJECTED);
        assertThat(outcome.message()).contains("10 digits");
        server.verify(); // no request expected, none made
    }

    @Test
    @DisplayName("a login Shipyaari refuses means nothing was booked")
    void loginRefusedOnBooking() {
        server.expect(requestTo("https://sy.test/api/v1/seller/signIn"))
                .andRespond(withSuccess("{\"success\":false,\"data\":[],\"message\":\"Invalid credentials\"}", MediaType.APPLICATION_JSON));

        BookingOutcome outcome = adapter.book(booking("DTDC|ECONOMY|SURFACE", rate(RateRequest.PaymentMode.PREPAID, 1), "9876543210"));

        assertThat(outcome.result()).isEqualTo(BookingOutcome.Result.REJECTED);
        assertThat(outcome.failureCode()).isEqualTo("PROVIDER_UNAVAILABLE");
    }

    // ---------------------------------------------------------------- tracking

    @Test
    @DisplayName("tracking reads the status and the scans, newest first, with Shipyaari's millisecond times")
    void tracks() {
        login();
        server.expect(requestTo(startsWith("https://sy.test/api/v1/tracking/getTracking")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer tkn"))
                .andExpect(r -> assertThat(r.getURI().getQuery()).isEqualTo("trackingNo=77090047322"))
                .andRespond(withSuccess("""
                        {"success":true,"data":[{"trackingInfo":[{"awb":"77090047322","currentStatus":"IN TRANSIT",
                          "processedLog":[{"Scans":[
                            {"status":"BOOKED","message":"ONLINE SHIPMENT BOOKED","location":"PUNE","time":1733297520000},
                            {"status":"IN TRANSIT","message":"Left origin hub","location":"MUMBAI HUB","time":1733383920000}]}]}]}]}""",
                        MediaType.APPLICATION_JSON));

        TrackingResult t = adapter.track(new ShipmentRef(UUID.randomUUID(), UUID.randomUUID(), "ref", "159398", "77090047322", null));

        assertThat(t.available()).isTrue();
        assertThat(t.status()).isEqualTo("In transit");
        assertThat(t.currentLocation()).isEqualTo("MUMBAI HUB");
        assertThat(t.events()).hasSize(2);
        assertThat(t.events().get(0).status()).isEqualTo("In transit");
        assertThat(t.events().get(0).description()).isEqualTo("Left origin hub");
        assertThat(t.events().get(0).at()).isAfter(t.events().get(1).at());
    }

    @Test
    @DisplayName("tracking without a tracking number, or when Shipyaari has none, says so without calling or failing")
    void trackingUnavailable() {
        assertThat(adapter.track(new ShipmentRef(UUID.randomUUID(), UUID.randomUUID(), "ref", "1", null, null)).available()).isFalse();

        login();
        server.expect(requestTo(startsWith("https://sy.test/api/v1/tracking/getTracking")))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":false,\"message\":\"No Tracking Info Found With This Tracking Number\"}"));
        TrackingResult t = adapter.track(new ShipmentRef(UUID.randomUUID(), UUID.randomUUID(), "ref", "1", "999", null));

        assertThat(t.available()).isFalse();
    }

    // ------------------------------------------------------------------- label

    @Test
    @DisplayName("the label is the first https address in Shipyaari's answer")
    void label() {
        login();
        server.expect(requestTo("https://sy.test/api/v1/labels/fetchLabels")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.awbs[0]").value("77090047322"))
                .andExpect(jsonPath("$.source").value("API"))
                .andRespond(withSuccess("{\"success\":true,\"data\":[{\"awb\":\"77090047322\",\"label\":\"https://sy-seller.s3.ap-south-1.amazonaws.com/l/1.pdf\"}]}",
                        MediaType.APPLICATION_JSON));

        LabelResult result = adapter.label(new ShipmentRef(UUID.randomUUID(), UUID.randomUUID(), "ref", "1", "77090047322", null));

        assertThat(result.available()).isTrue();
        assertThat(result.url()).isEqualTo("https://sy-seller.s3.ap-south-1.amazonaws.com/l/1.pdf");
        assertThat(adapter.labelHosts()).contains(".amazonaws.com", ".shipyaari.com");
    }

    @Test
    @DisplayName("an answer without a web address is 'not available yet', not an error")
    void labelMissing() {
        login();
        server.expect(requestTo("https://sy.test/api/v1/labels/fetchLabels"))
                .andRespond(withSuccess("{\"success\":true,\"data\":[]}", MediaType.APPLICATION_JSON));

        assertThat(adapter.label(new ShipmentRef(UUID.randomUUID(), UUID.randomUUID(), "ref", "1", "77090047322", null)).available())
                .isFalse();
    }

    // ---------------------------------------------------------- connection test

    @Test
    @DisplayName("the connection test is a login: accepted, refused, or unreachable, with no secret in the message")
    void connectionTest() {
        login();
        ConnectionTest ok = adapter.testConnection(UUID.randomUUID());
        assertThat(ok.ok()).isTrue();

        server.reset();
        server.expect(requestTo("https://sy.test/api/v1/seller/signIn"))
                .andRespond(withSuccess("{\"success\":false,\"data\":[],\"message\":\"Invalid credentials\"}", MediaType.APPLICATION_JSON));
        ConnectionTest refused = adapter.testConnection(UUID.randomUUID());
        assertThat(refused.ok()).isFalse();
        assertThat(refused.message()).contains("e-mail and password").doesNotContain("secret");

        server.reset();
        server.expect(requestTo("https://sy.test/api/v1/seller/signIn")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThat(adapter.testConnection(UUID.randomUUID()).ok()).isFalse();

        server.reset();
        server.expect(requestTo("https://sy.test/api/v1/seller/signIn")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThat(adapter.testConnection(UUID.randomUUID()).message()).contains("try again later");
    }
}
