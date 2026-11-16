package com.courier.modules.customerportal.rates.infrastructure;

import com.courier.modules.customerportal.rates.application.StorePlatform;
import com.courier.modules.customerportal.rates.application.WooOrderMapper;
import com.courier.modules.customerportal.rates.domain.ShopConnection;
import com.courier.shared.exception.ApiException;
import com.courier.shared.exception.BusinessRuleException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * WooCommerce (13.3c) through its REST API {@code /wp-json/wc/v3} with a key and secret the customer creates in WooCommerce → Settings →
 * Advanced → REST API (Read/Write). Basic authentication over HTTPS; when the web server drops the Authorization header (some hosts do),
 * the key and secret go in the query instead. Only public https hosts are called, redirects are not followed.
 */
@Slf4j
@Component
public class WooCommercePlatform implements StorePlatform {

    private static final Pattern HOST = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?)+$");
    private static final Pattern IPV4 = Pattern.compile("^[0-9.]+$");
    private static final Pattern PATH = Pattern.compile("^(/[A-Za-z0-9._~-]+)*$");
    private static final int PAGE = 100;

    private final RestClient http;
    private final boolean guardHosts;
    private final ObjectMapper json = new ObjectMapper();

    /** Tests: a prepared client, and the public-host check off (a mock server has no real address). */
    WooCommercePlatform(RestClient http, boolean guardHosts) {
        this.http = http;
        this.guardHosts = guardHosts;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public WooCommercePlatform(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        f.setConnectTimeout(10_000);
        f.setReadTimeout(30_000);
        this.http = builder.clone().requestFactory(f).build();
        this.guardHosts = true;
    }

    @Override
    public ShopConnection.Platform platform() {
        return ShopConnection.Platform.WOOCOMMERCE;
    }

    /** "shop.in", "https://www.shop.in/" or "https://site.com/store" → https://… without the trailing slash; https and a public host only. */
    @Override
    public String address(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.toLowerCase(Locale.ROOT).startsWith("http://")) {
            throw new BusinessRuleException("Your store must use https:// (WooCommerce only allows API keys over a secure connection).");
        }
        s = s.replaceFirst("(?i)^https://", "");
        int slash = s.indexOf('/');
        String host = (slash < 0 ? s : s.substring(0, slash)).toLowerCase(Locale.ROOT);
        String path = slash < 0 ? "" : s.substring(slash).replaceAll("/+$", "");
        path = path.replaceFirst("(?i)/wp-admin.*$", "").replaceFirst("(?i)/wp-json.*$", "");
        if (!HOST.matcher(host).matches() || IPV4.matcher(host).matches() || !PATH.matcher(path).matches()) {
            throw new BusinessRuleException("Enter your store's web address, like https://yourshop.in");
        }
        String address = "https://" + host + path;
        if (address.length() > 120) {
            throw new BusinessRuleException("The store address is too long.");
        }
        return address;
    }

    @Override
    public Map<String, String> credentials(Map<String, String> pasted) {
        String key = trim(pasted.get("consumerKey"));
        String secret = trim(pasted.get("consumerSecret"));
        if (!key.matches("^ck_[A-Za-z0-9]{20,64}$")) {
            throw new BusinessRuleException("Paste the Consumer key from WooCommerce (it starts with ck_).");
        }
        if (!secret.matches("^cs_[A-Za-z0-9]{20,64}$")) {
            throw new BusinessRuleException("Paste the Consumer secret from WooCommerce (it starts with cs_).");
        }
        return Map.of("key", key, "secret", secret);
    }

    @Override
    public String check(String address, Map<String, String> credentials) {
        get(address, "/wp-json/wc/v3/orders?per_page=1", credentials);
        try {
            JsonNode site = call(HttpMethod.GET, URI.create(address + "/wp-json/"), null, null).getBody();
            String name = site == null ? null : site.path("name").asText(null);
            return name == null || name.isBlank() ? address.replaceFirst("^https://", "") : name;
        } catch (StoreException e) {
            return address.replaceFirst("^https://", "");
        }
    }

    @Override
    public List<StoreOrder> openOrders(String address, Map<String, String> credentials, Instant since, int max) {
        List<JsonNode> found = new ArrayList<>();
        String q = "/wp-json/wc/v3/orders?status=processing&per_page=" + PAGE + "&orderby=modified&order=asc"
                + (since == null ? "" : "&modified_after=" + enc(since.truncatedTo(ChronoUnit.SECONDS).toString()));
        int pages = 1;
        for (int page = 1; page <= pages && found.size() < max; page++) {
            ResponseEntity<JsonNode> r = get(address, q + "&page=" + page, credentials);
            if (r.getBody() != null && r.getBody().isArray()) {
                r.getBody().forEach(found::add);
            }
            String total = r.getHeaders().getFirst("X-WP-TotalPages");
            try {
                pages = total == null ? 1 : Math.min(50, Integer.parseInt(total.trim()));
            } catch (NumberFormatException e) {
                pages = 1;
            }
        }
        if (found.size() > max) {
            found = found.subList(0, max);
        }
        Map<Long, BigDecimal> kg = weights(address, credentials, found);
        List<StoreOrder> out = new ArrayList<>();
        for (JsonNode o : found) {
            String number = WooOrderMapper.number(o);
            String id = o.path("id").asText();
            String not = WooOrderMapper.notShippable(o);
            if (not != null) {
                out.add(new StoreOrder(number, id, not, null, null));
                continue;
            }
            try {
                out.add(new StoreOrder(number, id, null, WooOrderMapper.map(o, kg), null));
            } catch (ApiException | IllegalArgumentException e) {
                out.add(new StoreOrder(number, id, null, null, e.getMessage()));
            }
        }
        return out;
    }

    /** The products' weights in kg (in the store's weight unit), for the orders' products; empty when the store does not say. */
    private Map<Long, BigDecimal> weights(String address, Map<String, String> credentials, List<JsonNode> orders) {
        Set<Long> ids = new LinkedHashSet<>();
        orders.forEach(o -> ids.addAll(WooOrderMapper.productIds(o)));
        Map<Long, BigDecimal> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        try {
            JsonNode unitNode = get(address, "/wp-json/wc/v3/settings/products/woocommerce_weight_unit", credentials).getBody();
            String unit = unitNode == null ? "kg" : unitNode.path("value").asText("kg");
            List<Long> list = new ArrayList<>(ids);
            for (int i = 0; i < list.size() && i < 500; i += PAGE) {
                String include = list.subList(i, Math.min(list.size(), i + PAGE)).stream().map(String::valueOf).collect(Collectors.joining(","));
                JsonNode products = get(address, "/wp-json/wc/v3/products?per_page=" + PAGE + "&include=" + include, credentials).getBody();
                if (products != null) {
                    for (JsonNode p : products) {
                        BigDecimal w = WooOrderMapper.toKg(p.path("weight").asText(null), unit);
                        if (w != null) {
                            out.put(p.path("id").asLong(), w);
                        }
                    }
                }
            }
        } catch (StoreException e) {
            log.info("WooCommerce product weights not read from {}: {}", address, e.getMessage());
        }
        return out;
    }

    @Override
    public void shipped(String address, Map<String, String> credentials, String orderId, String courier, String trackingNumber) {
        if (!orderId.matches("^[0-9]{1,20}$")) {
            throw new StoreException("This order's WooCommerce id is not valid.", false);
        }
        String note = "Your order has shipped with " + courier + ". Tracking number: " + trackingNumber + ".";
        send(HttpMethod.POST, address, "/wp-json/wc/v3/orders/" + orderId + "/notes", credentials, Map.of("note", note, "customer_note", true));
        send(HttpMethod.PUT, address, "/wp-json/wc/v3/orders/" + orderId, credentials, Map.of("status", "completed"));
    }

    // ------------------------------------------------------------------ HTTP

    private ResponseEntity<JsonNode> get(String address, String path, Map<String, String> c) {
        return send(HttpMethod.GET, address, path, c, null);
    }

    /** With Basic authentication; once more with the key in the query when the server dropped the header (401). */
    private ResponseEntity<JsonNode> send(HttpMethod method, String address, String path, Map<String, String> c, Object body) {
        String basic = "Basic " + Base64.getEncoder().encodeToString((c.get("key") + ":" + c.get("secret")).getBytes(StandardCharsets.UTF_8));
        try {
            return call(method, URI.create(address + path), basic, body);
        } catch (StoreException e) {
            if (!e.auth()) {
                throw e;
            }
            String sep = path.contains("?") ? "&" : "?";
            return call(method, URI.create(address + path + sep + "consumer_key=" + enc(c.get("key")) + "&consumer_secret=" + enc(c.get("secret"))),
                    null, body);
        }
    }

    private ResponseEntity<JsonNode> call(HttpMethod method, URI uri, String authorization, Object body) {
        try {
            if (guardHosts) {
                PublicHostGuard.check(uri.getHost());
            }
        } catch (IllegalArgumentException e) {
            throw new StoreException(e.getMessage(), false);
        }
        try {
            RestClient.RequestBodySpec req = http.method(method).uri(uri).accept(MediaType.APPLICATION_JSON);
            if (authorization != null) {
                req.header(HttpHeaders.AUTHORIZATION, authorization);
            }
            if (body != null) {
                req.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            ResponseEntity<String> r = req.retrieve().toEntity(String.class);
            if (r.getStatusCode().is3xxRedirection()) {
                throw new StoreException("The store address redirects elsewhere (" + r.getHeaders().getFirst(HttpHeaders.LOCATION)
                        + "). Enter the address it redirects to.", false);
            }
            String text = r.getBody() == null ? "null" : r.getBody();
            return new ResponseEntity<>(json.readTree(text), r.getHeaders(), r.getStatusCode());
        } catch (RestClientResponseException e) {
            throw refused(e);
        } catch (JsonProcessingException e) {
            throw new StoreException("The store did not answer like a WooCommerce store. Check the address (and that permalinks are not \"Plain\").",
                    false);
        } catch (RestClientException e) {
            throw new StoreException("The store could not be reached.", false);
        }
    }

    static StoreException refused(RestClientResponseException e) {
        int code = e.getStatusCode().value();
        if (code == 401) {
            return new StoreException("WooCommerce did not accept the key and secret. Copy them again, or create a new key.", true);
        }
        if (code == 403) {
            return new StoreException("The WooCommerce key is not allowed to do this. Give it Read/Write permission.", true);
        }
        if (code == 404) {
            return new StoreException("No WooCommerce API at this address. Check the address and that WooCommerce is installed.", false);
        }
        if (code == 429) {
            return new StoreException("The store asked us to slow down; it will be tried again shortly.", false);
        }
        log.warn("WooCommerce answered {}", code);
        return new StoreException("The store answered with an error (" + code + ").", false);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
