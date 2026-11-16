package com.courier.modules.customerportal.rates.application;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/** The Shopify Admin API calls the order import needs (13.3b). The domain is always a checked {@code *.myshopify.com} name. */
public interface ShopifyClient {

    /** The store's name; proves the token works. */
    String shopName(String domain, String token);

    /** Open, unfulfilled orders changed since {@code since} (all open ones when null), oldest first, up to {@code max}. */
    List<JsonNode> openOrders(String domain, String token, Instant since, int max);

    /** Marks the order fulfilled with this tracking number (Shopify emails the buyer when {@code notify}). */
    void fulfil(String domain, String token, String orderId, String courier, String trackingNumber, boolean notify);

    /** Shopify refused or failed; {@code auth} when the token is wrong, revoked or lacks a permission. */
    class ShopifyException extends RuntimeException {
        private final boolean auth;

        public ShopifyException(String message, boolean auth) {
            super(message);
            this.auth = auth;
        }

        public boolean auth() {
            return auth;
        }
    }
}
