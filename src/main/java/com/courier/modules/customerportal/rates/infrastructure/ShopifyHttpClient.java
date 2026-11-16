package com.courier.modules.customerportal.rates.infrastructure;

import com.courier.modules.customerportal.rates.application.ShopifyClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shopify Admin REST API ({@value #VERSION}) with a custom app's access token ({@code X-Shopify-Access-Token}). Orders are paged with
 * the {@code Link: <...page_info=...>; rel="next"} header. Fulfilment goes through the order's fulfillment orders, as Shopify requires
 * since 2023.
 */
@Slf4j
@Component
public class ShopifyHttpClient implements ShopifyClient {

    static final String VERSION = "2025-01";
    private static final Pattern NEXT = Pattern.compile("<([^>]+)>;\\s*rel=\"next\"");
    private static final int PAGE = 250;

    private final RestClient http;
    private final ObjectMapper json = new ObjectMapper();

    public ShopifyHttpClient(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(10_000);
        f.setReadTimeout(30_000);
        this.http = builder.clone().requestFactory(f).build();
    }

    @Override
    public String shopName(String domain, String token) {
        JsonNode shop = get(url(domain, "/shop.json"), token).getBody().path("shop");
        return shop.path("name").asText(domain);
    }

    @Override
    public List<JsonNode> openOrders(String domain, String token, Instant since, int max) {
        StringBuilder q = new StringBuilder("/orders.json?status=open&fulfillment_status=unfulfilled&limit=").append(PAGE)
                .append("&order=updated_at+asc");
        if (since != null) {
            q.append("&updated_at_min=").append(since);
        }
        List<JsonNode> out = new ArrayList<>();
        String next = url(domain, q.toString());
        while (next != null && out.size() < max) {
            ResponseEntity<JsonNode> r = get(next, token);
            r.getBody().path("orders").forEach(out::add);
            next = nextPage(r.getHeaders().getFirst(HttpHeaders.LINK), domain);
        }
        return out.size() > max ? out.subList(0, max) : out;
    }

    @Override
    public void fulfil(String domain, String token, String orderId, String courier, String trackingNumber, boolean notify) {
        JsonNode fos = get(url(domain, "/orders/" + orderId + "/fulfillment_orders.json"), token).getBody().path("fulfillment_orders");
        List<Map<String, Object>> lines = new ArrayList<>();
        for (JsonNode fo : fos) {
            String status = fo.path("status").asText();
            if ("open".equals(status) || "in_progress".equals(status)) {
                lines.add(Map.of("fulfillment_order_id", fo.path("id").asLong()));
            }
        }
        if (lines.isEmpty()) {
            throw new ShopifyException("Shopify has nothing left to fulfil on this order (already fulfilled or cancelled).", false);
        }
        Map<String, Object> tracking = new LinkedHashMap<>();
        tracking.put("company", courier);
        tracking.put("number", trackingNumber);
        Map<String, Object> body = Map.of("fulfillment", Map.of("line_items_by_fulfillment_order", lines, "tracking_info", tracking,
                "notify_customer", notify));
        try {
            http.post().uri(URI.create(url(domain, "/fulfillments.json"))).header("X-Shopify-Access-Token", token)
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw refused(e);
        } catch (RestClientException e) {
            throw new ShopifyException("Shopify could not be reached.", false);
        }
    }

    private ResponseEntity<JsonNode> get(String url, String token) {
        try {
            ResponseEntity<String> r = http.get().uri(URI.create(url)).header("X-Shopify-Access-Token", token)
                    .accept(MediaType.APPLICATION_JSON).retrieve().toEntity(String.class);
            return new ResponseEntity<>(json.readTree(r.getBody() == null ? "{}" : r.getBody()), r.getHeaders(), r.getStatusCode());
        } catch (RestClientResponseException e) {
            throw refused(e);
        } catch (RestClientException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ShopifyException("Shopify could not be reached.", false);
        }
    }

    static ShopifyException refused(RestClientResponseException e) {
        int code = e.getStatusCode().value();
        if (code == 401) {
            return new ShopifyException("Shopify did not accept the access token. Copy it again from your app, or create a new one.", true);
        }
        if (code == 403) {
            return new ShopifyException("The app's token is missing a permission. In Shopify, give the app read_orders and "
                    + "write_merchant_managed_fulfillment_orders, then install it again.", true);
        }
        if (code == 404) {
            return new ShopifyException("Shopify did not find this store or order. Check the store address.", false);
        }
        if (code == 429) {
            return new ShopifyException("Shopify asked us to slow down; it will be tried again shortly.", false);
        }
        log.warn("Shopify answered {}: {}", code, e.getResponseBodyAsString().length() > 300
                ? e.getResponseBodyAsString().substring(0, 300) : e.getResponseBodyAsString());
        return new ShopifyException("Shopify answered with an error (" + code + ").", false);
    }

    static String url(String domain, String path) {
        return "https://" + domain + "/admin/api/" + VERSION + path;
    }

    /** The next page from a Link header, only when it points at the same store. */
    static String nextPage(String link, String domain) {
        if (link == null) {
            return null;
        }
        Matcher m = NEXT.matcher(link);
        if (!m.find()) {
            return null;
        }
        String next = m.group(1);
        return next.startsWith("https://" + domain + "/") ? next : null;
    }
}
