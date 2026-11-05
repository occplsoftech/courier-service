package com.courier.modules.customerportal.rates.infrastructure;

import com.courier.modules.customerportal.rates.application.CustomerPortalProperties;
import com.courier.modules.customerportal.rates.domain.BookingOutcome;
import com.courier.modules.customerportal.rates.domain.ChargeComponent;
import com.courier.modules.customerportal.rates.domain.ConnectionTest;
import com.courier.modules.customerportal.rates.domain.ProviderResult;
import com.courier.modules.customerportal.rates.domain.LabelResult;
import com.courier.modules.customerportal.rates.domain.RateRequest;
import com.courier.modules.customerportal.rates.domain.ShipmentRef;
import com.courier.modules.customerportal.rates.domain.TrackingEvent;
import com.courier.modules.customerportal.rates.domain.TrackingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DelhiveryAdapterTest {

    private MockRestServiceServer server;
    private DelhiveryAdapter adapter;

    @BeforeEach
    void setUp() {
        CustomerPortalProperties.Delhivery cfg = new CustomerPortalProperties.Delhivery();
        cfg.setEnabled(true);
        cfg.setBaseUrl("https://dl.test");
        cfg.setToken("tok");
        cfg.setPickupLocation("OCCPL PUNE");
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new DelhiveryAdapter(builder, cfg, 5000);
    }

    private static RateRequest request() {
        // 40 x 30 x 20 cm, 2 packages: volumetric 9.6 kg beats 3 kg actual
        return new RateRequest(UUID.randomUUID(), UUID.randomUUID(), "411016", "110001", new BigDecimal("3"),
                new BigDecimal("40"), new BigDecimal("30"), new BigDecimal("20"), 2, new BigDecimal("2000"),
                RateRequest.PaymentMode.PREPAID);
    }

    @Test
    @DisplayName("checks the pincode, prices Surface and Express on the chargeable weight, splits Delhivery's GST out")
    void pricesBothModes() {
        server.expect(requestTo(startsWith("https://dl.test/c/api/pin-codes/json/")))
                .andExpect(header("Authorization", "Token tok"))
                .andRespond(withSuccess("{\"delivery_codes\":[{\"postal_code\":{\"pin\":110001,\"cod\":\"Y\"}}]}", MediaType.APPLICATION_JSON));
        for (String md : new String[]{"S", "E"}) {
            server.expect(requestTo(startsWith("https://dl.test/api/kinko/v1/invoice/charges/.json")))
                    .andExpect(queryParam("md", md))
                    .andExpect(queryParam("cgm", "9600"))
                    .andRespond(withSuccess("[{\"total_amount\":" + ("S".equals(md) ? "236" : "354")
                            + ",\"gross_amount\":" + ("S".equals(md) ? "200" : "300") + ",\"charged_weight\":9600}]",
                            MediaType.APPLICATION_JSON));
        }

        ProviderResult result = adapter.getRates(request());

        assertThat(result.available()).isTrue();
        assertThat(result.rates()).hasSize(2);
        assertThat(result.rates().get(0).charges()).extracting(l -> l.component())
                .containsExactly(ChargeComponent.FREIGHT, ChargeComponent.TAX);
        assertThat(result.rates().get(0).providerTotal()).isEqualByComparingTo("236");
        assertThat(result.rates().get(0).chargeableWeightKg()).isEqualByComparingTo("9.6");
        server.verify();
    }

    @Test
    @DisplayName("a pincode Delhivery does not serve is reported as such")
    void notServiceable() {
        server.expect(requestTo(startsWith("https://dl.test/c/api/pin-codes/json/")))
                .andRespond(withSuccess("{\"delivery_codes\":[]}", MediaType.APPLICATION_JSON));

        ProviderResult result = adapter.getRates(request());

        assertThat(result.available()).isFalse();
        assertThat(result.reason()).contains("does not deliver");
    }

    @Test
    @DisplayName("booking: success returns the waybill; success=false is a rejection")
    void books() {
        server.expect(requestTo("https://dl.test/api/cmu/create.json"))
                .andRespond(withSuccess("{\"success\":true,\"packages\":[{\"waybill\":\"DLV123\",\"status\":\"Success\"}]}", MediaType.APPLICATION_JSON));
        BookingOutcome ok = adapter.book(ShiprocketAdapterTest.booking());
        assertThat(ok.result()).isEqualTo(BookingOutcome.Result.BOOKED);
        assertThat(ok.awbNumber()).isEqualTo("DLV123");

        server.reset();
        server.expect(requestTo("https://dl.test/api/cmu/create.json"))
                .andRespond(withSuccess("{\"success\":false,\"packages\":[{\"remarks\":[\"Pin not serviceable\"]}]}", MediaType.APPLICATION_JSON));
        BookingOutcome refused = adapter.book(ShiprocketAdapterTest.booking());
        assertThat(refused.result()).isEqualTo(BookingOutcome.Result.REJECTED);
        // staff see Delhivery's own words; the customer-facing message stays generic
        assertThat(refused.detail()).isEqualTo("Pin not serviceable");
        assertThat(refused.message()).doesNotContain("Pin not serviceable");

        server.reset();
        server.expect(requestTo("https://dl.test/api/cmu/create.json"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(org.springframework.http.HttpStatus.BAD_REQUEST).body("{\"rmk\":\"ClientWarehouse matching query does not exist.\"}")
                        .contentType(MediaType.APPLICATION_JSON));
        assertThat(adapter.book(ShiprocketAdapterTest.booking()).detail()).isEqualTo("ClientWarehouse matching query does not exist.");
    }

    private static ShipmentRef ref(String awb) {
        return new ShipmentRef(UUID.randomUUID(), UUID.randomUUID(), "booking-1", "booking-1", awb, null);
    }

    @Test
    @DisplayName("tracking: current status and scans, newest first")
    void tracks() {
        server.expect(requestTo(startsWith("https://dl.test/api/v1/packages/json/")))
                .andExpect(queryParam("waybill", "WB9"))
                .andExpect(header("Authorization", "Token tok"))
                .andRespond(withSuccess("""
                        {"ShipmentData":[{"Shipment":{"AWB":"WB9",
                          "Status":{"Status":"In Transit","StatusLocation":"Delhi_Hub"},
                          "Scans":[
                            {"ScanDetail":{"Scan":"Manifested","ScanDateTime":"2026-10-20T10:00:00.000","ScannedLocation":"Pune"}},
                            {"ScanDetail":{"Scan":"In Transit","ScanDateTime":"2026-10-21T08:30:00.000","ScannedLocation":"Delhi_Hub","Instructions":"Shipment arrived"}}]}}]}""",
                        MediaType.APPLICATION_JSON));

        TrackingResult t = adapter.track(ref("WB9"));

        assertThat(t.available()).isTrue();
        assertThat(t.status()).isEqualTo("In Transit");
        assertThat(t.currentLocation()).isEqualTo("Delhi_Hub");
        assertThat(t.events()).extracting(TrackingEvent::status).containsExactly("In Transit", "Manifested");
        assertThat(t.events().get(0).description()).isEqualTo("Shipment arrived");
        server.verify();
    }

    @Test
    @DisplayName("tracking: an empty answer is 'no updates yet'")
    void noUpdates() {
        server.expect(requestTo(startsWith("https://dl.test/api/v1/packages/json/")))
                .andRespond(withSuccess("{\"ShipmentData\":[]}", MediaType.APPLICATION_JSON));

        assertThat(adapter.track(ref("WB9")).available()).isFalse();
    }

    @Test
    @DisplayName("label: the packing slip's PDF link")
    void label() {
        server.expect(requestTo(startsWith("https://dl.test/api/p/packing_slip")))
                .andExpect(queryParam("wbns", "WB9"))
                .andExpect(queryParam("pdf", "true"))
                .andRespond(withSuccess("{\"packages\":[{\"pdf_download_link\":\"https://dlv-labels.s3.amazonaws.com/WB9.pdf\"}]}",
                        MediaType.APPLICATION_JSON));

        LabelResult label = adapter.label(ref("WB9"));

        assertThat(label.url()).isEqualTo("https://dlv-labels.s3.amazonaws.com/WB9.pdf");
        server.verify();
    }

    @Test
    @DisplayName("per company: each company's own token is sent; the server's token is not used for it")
    void perCompanyToken() {
        UUID mine = UUID.randomUUID();
        CustomerPortalProperties.Delhivery own = new CustomerPortalProperties.Delhivery();
        own.setEnabled(true);
        own.setBaseUrl("https://dl.test");
        own.setToken("company-token");
        CustomerPortalProperties.Delhivery env = new CustomerPortalProperties.Delhivery();
        env.setEnabled(true);
        env.setBaseUrl("https://dl.test");
        env.setToken("server-token");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer srv = MockRestServiceServer.bindTo(builder).build();
        DelhiveryAdapter adapter = new DelhiveryAdapter(builder, env, id -> mine.equals(id) ? own : env, 5000);
        srv.expect(requestTo(startsWith("https://dl.test/c/api/pin-codes/json/"))).andExpect(header("Authorization", "Token company-token"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(adapter.testConnection(mine).ok()).isTrue();
        srv.verify();
    }

    @Test
    @DisplayName("connection test: a rejected token and a down courier are readable failures")
    void connectionFailures() {
        server.expect(requestTo(startsWith("https://dl.test/c/api/pin-codes/json/")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED));
        ConnectionTest rejected = adapter.testConnection(UUID.randomUUID());
        assertThat(rejected.ok()).isFalse();
        assertThat(rejected.message()).contains("did not accept");

        server.reset();
        server.expect(requestTo(startsWith("https://dl.test/c/api/pin-codes/json/")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(org.springframework.http.HttpStatus.BAD_GATEWAY));
        assertThat(adapter.testConnection(UUID.randomUUID()).message()).contains("HTTP 502");
    }
}
