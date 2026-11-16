package com.courier.modules.customerportal.rates.application;

import com.courier.modules.customerportal.rates.domain.ShopConnection;
import com.courier.shared.exception.ApiException;
import com.courier.shared.exception.BusinessRuleException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Shopify (13.3b): a custom app's Admin API token; open unfulfilled orders; fulfilled with tracking after booking. */
@Component
@RequiredArgsConstructor
public class ShopifyPlatform implements StorePlatform {

    private static final Pattern DOMAIN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,60}\\.myshopify\\.com$");

    private final ShopifyClient shopify;

    @Override
    public ShopConnection.Platform platform() {
        return ShopConnection.Platform.SHOPIFY;
    }

    /** "My Shop.myshopify.com", "https://myshop.myshopify.com/admin" or just "myshop" → myshop.myshopify.com; anything else is refused. */
    @Override
    public String address(String raw) {
        String d = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        d = d.replaceFirst("^https?://", "");
        int slash = d.indexOf('/');
        if (slash >= 0) {
            d = d.substring(0, slash);
        }
        if (!d.isEmpty() && !d.contains(".")) {
            d = d + ".myshopify.com";
        }
        if (!DOMAIN.matcher(d).matches()) {
            throw new BusinessRuleException("Enter your store's Shopify address, like yourshop.myshopify.com (Settings → Domains in Shopify).");
        }
        return d;
    }

    @Override
    public Map<String, String> credentials(Map<String, String> pasted) {
        String t = pasted.getOrDefault("accessToken", "");
        t = t == null ? "" : t.trim();
        if (t.length() < 20 || t.length() > 200 || t.contains(" ")) {
            throw new BusinessRuleException("Paste the Admin API access token from your Shopify app (it starts with shpat_).");
        }
        return Map.of("token", t);
    }

    @Override
    public String check(String address, Map<String, String> credentials) {
        return shopify.shopName(address, credentials.get("token"));
    }

    @Override
    public List<StoreOrder> openOrders(String address, Map<String, String> credentials, Instant since, int max) {
        List<StoreOrder> out = new ArrayList<>();
        for (JsonNode o : shopify.openOrders(address, credentials.get("token"), since, max)) {
            String number = ShopifyOrderMapper.number(o);
            String not = ShopifyOrderMapper.notShippable(o);
            if (not != null) {
                out.add(new StoreOrder(number, o.path("id").asText(), not, null, null));
                continue;
            }
            try {
                out.add(new StoreOrder(number, o.path("id").asText(), null, ShopifyOrderMapper.map(o), null));
            } catch (ApiException | IllegalArgumentException e) {
                out.add(new StoreOrder(number, o.path("id").asText(), null, null, e.getMessage()));
            }
        }
        return out;
    }

    @Override
    public void shipped(String address, Map<String, String> credentials, String orderId, String courier, String trackingNumber) {
        shopify.fulfil(address, credentials.get("token"), orderId, courier, trackingNumber, true);
    }
}
