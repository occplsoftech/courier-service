package com.courier.modules.customerportal.rates.application;

import com.courier.modules.customerportal.rates.application.PortalOrderService.Buyer;
import com.courier.modules.customerportal.rates.application.PortalOrderService.Item;
import com.courier.modules.customerportal.rates.application.PortalOrderService.OrderInput;
import com.courier.modules.customerportal.rates.application.PortalOrderService.Parcel;
import com.courier.modules.customerportal.rates.application.PortalOrderService.Payment;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * A Shopify order (Admin REST JSON) as an inbox order (13.3b). The order number is Shopify's name (#1001); the delivery is the shipping
 * address (phone from the address, else the order, customer or billing address); weight from the order's total grams; value from the
 * order total. Cash on delivery when a payment gateway is called COD / cash on delivery and money is still due, for the amount due.
 * The result still goes through {@link PortalOrderService#apply}, which rejects an unusable phone or pincode.
 */
final class ShopifyOrderMapper {

    private ShopifyOrderMapper() {
    }

    /** Why a Shopify order is not imported at all (not an error the customer can fix in Shopify by editing). */
    static String notShippable(JsonNode o) {
        if (!o.path("cancelled_at").isNull() && !o.path("cancelled_at").isMissingNode()) {
            return "Cancelled in Shopify.";
        }
        if (o.path("shipping_address").isMissingNode() || o.path("shipping_address").isNull()) {
            return "No shipping address (digital or pickup order).";
        }
        return null;
    }

    static String number(JsonNode o) {
        String name = o.path("name").asText("");
        return name.isBlank() ? "#" + o.path("order_number").asText(o.path("id").asText()) : name.trim();
    }

    static OrderInput map(JsonNode o) {
        JsonNode a = o.path("shipping_address");
        String name = text(a, "name");
        if (name == null) {
            name = join(" ", text(a, "first_name"), text(a, "last_name"));
        }
        String phone = first(text(a, "phone"), text(o, "phone"), text(o.path("customer"), "phone"), text(o.path("billing_address"), "phone"),
                text(o.path("customer").path("default_address"), "phone"));
        String address = join(", ", text(a, "address1"), text(a, "address2"), text(a, "company"));
        String pin = text(a, "zip") == null ? null : text(a, "zip").replaceAll("\\s", "");
        Buyer buyer = new Buyer(name, phone, address, pin, text(a, "city"), text(a, "province"));

        BigDecimal value = money(o, "current_total_price");
        if (value == null || value.signum() <= 0) {
            value = money(o, "total_price");
        }
        if (value == null || value.signum() <= 0) {
            value = money(o, "subtotal_price");
        }
        BigDecimal grams = money(o, "total_weight");
        BigDecimal kg = grams == null || grams.signum() <= 0 ? null : grams.divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP);
        if (kg != null && kg.compareTo(new BigDecimal("0.01")) < 0) {
            kg = new BigDecimal("0.01");
        }

        List<Item> items = new ArrayList<>();
        for (JsonNode li : o.path("line_items")) {
            items.add(new Item(li.path("name").asText(li.path("title").asText("")), li.path("quantity").asInt(1), money(li, "price")));
        }
        String contents = items.stream().map(Item::name).filter(s -> s != null && !s.isBlank()).distinct().limit(5)
                .collect(Collectors.joining(", "));
        if (contents.length() > 200) {
            contents = contents.substring(0, 197) + "...";
        }
        Parcel parcel = new Parcel(kg, null, null, null, 1, contents.isBlank() ? null : contents, value);
        return new OrderInput(number(o), buyer, parcel, payment(o, value), items.size() > 100 ? items.subList(0, 100) : items, text(o, "note"));
    }

    /** COD: a cash-on-delivery gateway and the order not paid yet. */
    static Payment payment(JsonNode o, BigDecimal value) {
        boolean codGateway = false;
        for (JsonNode g : o.path("payment_gateway_names")) {
            String n = g.asText("").toLowerCase(Locale.ROOT);
            if (n.contains("cash on delivery") || n.contains("cod") || n.contains("cash_on_delivery")) {
                codGateway = true;
            }
        }
        String fin = o.path("financial_status").asText("").toLowerCase(Locale.ROOT);
        boolean unpaid = fin.equals("pending") || fin.equals("partially_paid") || fin.equals("authorized");
        if (!codGateway || !unpaid) {
            return new Payment("PREPAID", null);
        }
        BigDecimal due = money(o, "total_outstanding");
        return new Payment("COD", due != null && due.signum() > 0 ? due : value);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String t = v.asText("").trim();
        return t.isEmpty() ? null : t;
    }

    private static BigDecimal money(JsonNode n, String field) {
        String t = text(n, field);
        try {
            return t == null ? null : new BigDecimal(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String first(String... values) {
        for (String v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static String join(String sep, String... parts) {
        String s = java.util.Arrays.stream(parts).filter(p -> p != null && !p.isBlank()).collect(Collectors.joining(sep));
        return s.isEmpty() ? null : s;
    }
}
