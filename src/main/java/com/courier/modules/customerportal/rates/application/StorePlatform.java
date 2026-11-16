package com.courier.modules.customerportal.rates.application;

import com.courier.modules.customerportal.rates.application.PortalOrderService.OrderInput;
import com.courier.modules.customerportal.rates.domain.ShopConnection;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One kind of online store a customer can connect (13.3b Shopify, 13.3c WooCommerce): checks the address and credentials, lists the
 * orders waiting to ship, and sends the tracking number back. Calls fail with {@link StoreException}.
 */
public interface StorePlatform {

    ShopConnection.Platform platform();

    /** The store address as stored (normalised); refuses anything that is not a store address of this platform. */
    String address(String raw);

    /** The credentials to keep (encrypted), from what the customer pasted; refuses missing or malformed ones. */
    Map<String, String> credentials(Map<String, String> pasted);

    /** Proves the credentials work; returns the store's name. */
    String check(String address, Map<String, String> credentials);

    /** Orders waiting to ship that changed since {@code since}, oldest first, up to {@code max}. */
    List<StoreOrder> openOrders(String address, Map<String, String> credentials, Instant since, int max);

    /** Tells the store the order has shipped with this tracking number (the store tells the buyer). */
    void shipped(String address, Map<String, String> credentials, String orderId, String courier, String trackingNumber);

    /** An order from the store: {@code notShippable} set when it is left out (cancelled, nothing to ship); else {@code input}. */
    record StoreOrder(String number, String externalId, String notShippable, OrderInput input, String mappingError) {
    }

    /** The store refused or failed; {@code auth} when the credentials are wrong, revoked or lack a permission. */
    class StoreException extends RuntimeException {
        private final boolean auth;

        public StoreException(String message, boolean auth) {
            super(message);
            this.auth = auth;
        }

        public boolean auth() {
            return auth;
        }
    }
}
