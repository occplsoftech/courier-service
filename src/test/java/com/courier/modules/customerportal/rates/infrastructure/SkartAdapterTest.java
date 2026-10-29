package com.courier.modules.customerportal.rates.infrastructure;

import com.courier.modules.customerportal.rates.application.CustomerPortalProperties;
import com.courier.modules.customerportal.rates.domain.ChargeComponent;
import com.courier.modules.customerportal.rates.domain.ChargeLine;
import com.courier.modules.customerportal.rates.domain.ConnectionTest;
import com.courier.modules.customerportal.rates.domain.InternationalRateProvider;
import com.courier.modules.customerportal.rates.domain.InternationalRateProvider.Kind;
import com.courier.modules.customerportal.rates.domain.ProviderRate;
import com.courier.modules.customerportal.rates.domain.ProviderResult;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Answers trimmed from sKart's development system (2026-10-29). */
class SkartAdapterTest {

    private static final String BASE = "https://sk.test/api/v1/booking";
    private static final UUID COMPANY = UUID.randomUUID();

    private static final String RATES = """
            {"message":"Rates fetched successfully","statusCode":200,"data":[
             {"product_name":"DHL DEL","parent_vendor":"DHL","tat_days":4,"max_invoice_value":"25000","charges":[
               {"charge_name":"FUEL SURCHARGE","charge_id":28,"charge_amount":"736.00","igst_amount":"132.48","cgst_amount":"0.00","sgst_amount":"0.00"},
               {"charge_name":"Outer Delivery Area","charge_id":3,"charge_amount":"0.00","igst_amount":"0.00","cgst_amount":"0.00","sgst_amount":"0.00"},
               {"charge_name":"FREIGHT","charge_id":1,"charge_amount":"1496.00","igst_amount":"269.28","cgst_amount":"0.00","sgst_amount":"0.00"},
               {"charge_name":"AQIS Document Submission Charge","charge_id":80,"charge_amount":"12.00","igst_amount":"2.16","cgst_amount":"0.00","sgst_amount":"0.00"}],
              "grand_total_with_gst":2647.92,"grand_total_without_gst":"2244.00"},
             {"product_name":"Silver Line","parent_vendor":"SKART SELF INTERNATIONAL","tat_days":0,"charges":[
               {"charge_name":"FREIGHT","charge_id":"1","charge_amount":"623.00","igst_amount":"112.14"}],"grand_total_with_gst":735.14}]}""";

    private static final String TRACK = """
            {"message":"Data fetched successfully","statusCode":200,"data":{"extra_data":{"awb":"37207354542"},"track_data":[
             {"date":"2024-08-22 16:32:00","status":"Record created.","update_location":"Mumbai, India","update_description":"Record created."},
             {"date":"2024-08-22 22:38:00","status":"Shipment received at Aramex origin sorting facility","update_location":"BOM - HUB, India",
              "update_description":"Shipment received at Aramex origin sorting facility"},
             {"date":"2024-08-22 09:42:28","status":"Shipment Booked"}]}}""";

    private MockRestServiceServer server;
    private SkartAdapter adapter;
    private CustomerPortalProperties.Skart cfg;

    @BeforeEach
    void setUp() {
        cfg = new CustomerPortalProperties.Skart();
        cfg.setEnabled(true);
        cfg.setBaseUrl(BASE);
        cfg.setUserName("franchisee");
        cfg.setPassword("pw-1");
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new SkartAdapter(builder, cfg, companyId -> cfg);
    }

    @Test
    @DisplayName("is international only: never part of the domestic comparison")
    void internationalOnly() {
        assertThat(adapter.domestic()).isFalse();
        assertThat(adapter.external()).isTrue();
    }

    @Test
    @DisplayName("rates: sends the account and parcel; each product becomes a rate whose lines add up to sKart's total with GST")
    void rates() {
        server.expect(requestTo(BASE + "/rate-calculator")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.user_name").value("franchisee"))
                .andExpect(jsonPath("$.booking_type").value(1))
                .andExpect(jsonPath("$.destination_country").value("AUSTRALIA"))
                .andExpect(jsonPath("$.destination_pincode").value("3001"))
                .andExpect(jsonPath("$.shipment_type").value(1))
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.length").value(31))
                .andRespond(withSuccess(RATES, MediaType.APPLICATION_JSON));

        ProviderResult result = adapter.internationalRates(new InternationalRateProvider.Request(COMPANY, "110049", "AUSTRALIA",
                "3001", Kind.PARCEL, new BigDecimal("1.5"), 2, new BigDecimal("30.2")));

        assertThat(result.available()).isTrue();
        List<ProviderRate> rates = result.rates();
        assertThat(rates).extracting(ProviderRate::serviceName).containsExactly("DHL DEL", "Silver Line (SKART SELF INTERNATIONAL)");
        ProviderRate dhl = rates.get(0);
        assertThat(dhl.etaMaxDays()).isEqualTo(4);
        assertThat(dhl.providerTotal()).isEqualByComparingTo("2647.92");
        assertThat(dhl.charges()).extracting(ChargeLine::component).containsExactly(
                ChargeComponent.FUEL_SURCHARGE, ChargeComponent.FREIGHT, ChargeComponent.OTHER, ChargeComponent.TAX);
        assertThat(rates.get(1).etaMaxDays()).isNull();
        assertThat(rates.get(1).providerTotal()).isEqualByComparingTo("735.14");
        server.verify();
    }

    @Test
    @DisplayName("a document sends no pieces or length; sKart's refusal message is shown, never the request")
    void documentAndRefusal() {
        server.expect(requestTo(BASE + "/rate-calculator"))
                .andExpect(jsonPath("$.shipment_type").value(2))
                .andExpect(jsonPath("$.quantity").doesNotExist())
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Invalid country name passed\",\"statusCode\":404}"));

        ProviderResult result = adapter.internationalRates(new InternationalRateProvider.Request(COMPANY, "110049", "NARNIA",
                "", Kind.DOCUMENT, BigDecimal.ONE, 1, null));

        assertThat(result.available()).isFalse();
        assertThat(result.reason()).isEqualTo("sKart: Invalid country name passed").doesNotContain("pw-1");
    }

    @Test
    @DisplayName("not connected: nothing is asked")
    void notConnected() {
        cfg.setEnabled(false);

        assertThat(adapter.internationalRates(new InternationalRateProvider.Request(COMPANY, "110049", "AUSTRALIA", "3001",
                Kind.PARCEL, BigDecimal.ONE, 1, null)).available()).isFalse();
        assertThat(adapter.countries(COMPANY)).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("countries: active ones A-Z, kept for a while (asked once)")
    void countries() {
        server.expect(requestTo(BASE + "/country/?user_name=franchisee&password=pw-1")).andRespond(withSuccess("""
                {"statusCode":200,"data":[
                 {"country_id":12,"country_code":"AU","country_name":"AUSTRALIA","region":"South West Pacific","is_active":1,"pincode_avail":1},
                 {"country_id":249,"country_code":"AF","country_name":"Afghanistan","region":"South ","is_active":1,"pincode_avail":0},
                 {"country_id":9,"country_code":"XX","country_name":"GONE","is_active":0}]}""", MediaType.APPLICATION_JSON));

        List<InternationalRateProvider.Country> list = adapter.countries(COMPANY);

        assertThat(list).extracting(InternationalRateProvider.Country::name).containsExactly("Afghanistan", "AUSTRALIA");
        assertThat(list.get(0).region()).isEqualTo("South");
        assertThat(list.get(1).postcodeRequired()).isTrue();
        assertThat(adapter.countries(COMPANY)).isSameAs(list);
        server.verify();
    }

    @Test
    @DisplayName("connection test: 200 is connected, 400 Invalid credentials is refused")
    void connection() {
        server.expect(requestTo(BASE + "/country/INDIA?user_name=franchisee&password=pw-1"))
                .andRespond(withSuccess("{\"statusCode\":200,\"data\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/country/INDIA?user_name=franchisee&password=pw-1"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"status\":false,\"message\":\"Invalid credentials\"}"));

        ConnectionTest ok = adapter.testConnection(COMPANY);
        ConnectionTest refused = adapter.testConnection(COMPANY);

        assertThat(ok.ok()).isTrue();
        assertThat(refused.ok()).isFalse();
        assertThat(refused.message()).contains("did not accept");
    }

    @Test
    @DisplayName("tracking: newest first by time (sKart's list is not always in order), times read as India time, description kept only when it adds something")
    void tracking() {
        server.expect(requestTo(BASE + "/track-shipment")).andExpect(jsonPath("$.awb").value("37207354542"))
                .andRespond(withSuccess(TRACK, MediaType.APPLICATION_JSON));

        TrackingResult t = adapter.track(new ShipmentRef(COMPANY, UUID.randomUUID(), "b1", null, "37207354542", null));

        assertThat(t.available()).isTrue();
        assertThat(t.status()).isEqualTo("Shipment received at Aramex origin sorting facility");
        assertThat(t.currentLocation()).isEqualTo("BOM - HUB, India");
        assertThat(t.events()).extracting(e -> e.status()).containsExactly(
                "Shipment received at Aramex origin sorting facility", "Record created.", "Shipment Booked");
        assertThat(t.events().get(1).at()).isEqualTo(Instant.parse("2024-08-22T11:02:00Z"));
        assertThat(t.events().get(0).description()).isNull();
        assertThat(adapter.track(new ShipmentRef(COMPANY, UUID.randomUUID(), "b2", null, null, null)).available()).isFalse();
    }
}
