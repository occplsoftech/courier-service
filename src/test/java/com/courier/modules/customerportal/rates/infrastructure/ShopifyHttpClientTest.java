package com.courier.modules.customerportal.rates.infrastructure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShopifyHttpClientTest {

    @Test
    void followsOnlyTheNextLinkOnTheSameStore() {
        String link = "<https://s.myshopify.com/admin/api/2025-01/orders.json?limit=250&page_info=abc>; rel=\"previous\", "
                + "<https://s.myshopify.com/admin/api/2025-01/orders.json?limit=250&page_info=def>; rel=\"next\"";
        assertThat(ShopifyHttpClient.nextPage(link, "s.myshopify.com")).endsWith("page_info=def");
        assertThat(ShopifyHttpClient.nextPage("<https://other.com/x>; rel=\"next\"", "s.myshopify.com")).isNull();
        assertThat(ShopifyHttpClient.nextPage(null, "s.myshopify.com")).isNull();
    }
}
