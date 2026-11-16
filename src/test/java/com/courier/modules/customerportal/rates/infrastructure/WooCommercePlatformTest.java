package com.courier.modules.customerportal.rates.infrastructure;

import com.courier.shared.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetAddress;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WooCommercePlatformTest {

    private final WooCommercePlatform woo = new WooCommercePlatform(RestClient.builder());

    @Test
    void addressIsHttpsWithoutTrailingSlashOrAdminPath() {
        assertThat(woo.address("myshop.in")).isEqualTo("https://myshop.in");
        assertThat(woo.address("https://WWW.MyShop.in/")).isEqualTo("https://www.myshop.in");
        assertThat(woo.address("https://site.com/store/wp-admin/admin.php?page=wc")).isEqualTo("https://site.com/store");
        assertThatThrownBy(() -> woo.address("http://myshop.in")).isInstanceOf(BusinessRuleException.class).hasMessageContaining("https");
        assertThatThrownBy(() -> woo.address("https://10.0.0.5")).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> woo.address("https://localhost")).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> woo.address("https://myshop.in:8080")).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> woo.address("https://user@evil.com")).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void credentialsMustLookLikeWooKeys() {
        String ck = "ck_" + "a".repeat(40);
        String cs = "cs_" + "b".repeat(40);
        assertThat(woo.credentials(Map.of("consumerKey", " " + ck + " ", "consumerSecret", cs))).containsEntry("key", ck).containsEntry("secret", cs);
        assertThatThrownBy(() -> woo.credentials(Map.of("consumerKey", "abc", "consumerSecret", cs))).hasMessageContaining("ck_");
        assertThatThrownBy(() -> woo.credentials(Map.of("consumerKey", ck))).hasMessageContaining("cs_");
    }

    @Test
    void privateAddressesAreNotPublic() throws Exception {
        for (String ip : new String[] {"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0",
                "::1", "fd00::1", "::ffff:10.0.0.1"}) {
            assertThat(PublicHostGuard.isPublic(InetAddress.getByName(ip))).as(ip).isFalse();
        }
        assertThat(PublicHostGuard.isPublic(InetAddress.getByName("8.8.8.8"))).isTrue();
        assertThatThrownBy(() -> PublicHostGuard.check("localhost")).isInstanceOf(IllegalArgumentException.class);
    }

    @org.junit.jupiter.api.Nested
    class AgainstAMockStore {
        private final RestClient.Builder builder = RestClient.builder();
        private final org.springframework.test.web.client.MockRestServiceServer server =
                org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        private final WooCommercePlatform store = new WooCommercePlatform(builder.build(), false);
        private final Map<String, String> creds = Map.of("key", "ck_" + "a".repeat(40), "secret", "cs_" + "b".repeat(40));

        @Test
        void readsAllPagesWithProductWeightsAndRetriesWithTheKeyInTheQuery() {
            var json = org.springframework.http.MediaType.APPLICATION_JSON;
            String o1 = "{\"id\":1,\"number\":\"101\",\"total\":\"500\",\"payment_method\":\"cod\",\"shipping\":{\"first_name\":\"A\","
                    + "\"address_1\":\"12 MG Road, Camp\",\"postcode\":\"411001\",\"phone\":\"9876543210\"},"
                    + "\"line_items\":[{\"product_id\":7,\"name\":\"Kurta\",\"quantity\":2,\"price\":250}]}";
            String o2 = "{\"id\":2,\"number\":\"102\",\"total\":\"90\",\"billing\":{},\"shipping\":{}}";
            // the host dropped the Authorization header: 401, then the same call with the key in the query
            server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                            org.hamcrest.Matchers.startsWith("https://shop.in/wp-json/wc/v3/orders?status=processing")))
                    .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header("Authorization",
                            org.hamcrest.Matchers.startsWith("Basic ")))
                    .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest());
            server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                            org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.containsString("page=1"), org.hamcrest.Matchers.containsString("consumer_key=ck_"))))
                    .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("[" + o1 + "]", json)
                            .header("X-WP-TotalPages", "2"));
            server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(org.hamcrest.Matchers.containsString("page=2")))
                    .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("[" + o2 + "]", json)
                            .header("X-WP-TotalPages", "2"));
            server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(org.hamcrest.Matchers.containsString("woocommerce_weight_unit")))
                    .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("{\"value\":\"g\"}", json));
            server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(org.hamcrest.Matchers.containsString("products?per_page=100&include=7")))
                    .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("[{\"id\":7,\"weight\":\"300\"}]", json));

            var orders = store.openOrders("https://shop.in", creds, java.time.Instant.parse("2026-11-01T00:00:00Z"), 1000);
            assertThat(orders).hasSize(2);
            assertThat(orders.get(0).input().parcel().weightKg()).isEqualByComparingTo("0.6");
            assertThat(orders.get(0).input().payment().mode()).isEqualTo("COD");
            assertThat(orders.get(1).notShippable()).isNotNull();
        }

        @Test
        void shippedAddsACustomerNoteAndCompletesTheOrder() {
            var json = org.springframework.http.MediaType.APPLICATION_JSON;
            server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://shop.in/wp-json/wc/v3/orders/731/notes"))
                    .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(org.springframework.http.HttpMethod.POST))
                    .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.customer_note").value(true))
                    .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.note")
                            .value(org.hamcrest.Matchers.containsString("26110000017")))
                    .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("{}", json));
            server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://shop.in/wp-json/wc/v3/orders/731"))
                    .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(org.springframework.http.HttpMethod.PUT))
                    .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.status").value("completed"))
                    .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("{}", json));
            store.shipped("https://shop.in", creds, "731", "OCCPL Network", "26110000017");
            server.verify();
        }

        @Test
        void wrongKeyIsAnAuthFailure() {
            server.expect(org.springframework.test.web.client.ExpectedCount.twice(),
                            org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(org.hamcrest.Matchers.containsString("/orders?per_page=1")))
                    .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest());
            assertThatThrownBy(() -> store.check("https://shop.in", creds))
                    .isInstanceOfSatisfying(com.courier.modules.customerportal.rates.application.StorePlatform.StoreException.class,
                            e -> assertThat(e.auth()).isTrue());
        }
    }
}
