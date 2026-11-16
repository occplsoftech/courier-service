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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A WooCommerce order (REST wc/v3 JSON) as an inbox order (13.3c). The delivery is the shipping address (the billing one when the store
 * does not ask for shipping); phone from shipping, else billing. Value = order total; COD when the payment method is "cod". The weight
 * is the products' weights times quantities when the store keeps them ({@code kgByProduct}), else the default. Indian state codes
 * (MH, KA…) become names.
 */
public final class WooOrderMapper {

    static final Map<String, String> IN_STATES = Map.ofEntries(
            Map.entry("AN", "Andaman and Nicobar Islands"), Map.entry("AP", "Andhra Pradesh"), Map.entry("AR", "Arunachal Pradesh"),
            Map.entry("AS", "Assam"), Map.entry("BR", "Bihar"), Map.entry("CH", "Chandigarh"), Map.entry("CT", "Chhattisgarh"),
            Map.entry("DN", "Dadra and Nagar Haveli and Daman and Diu"), Map.entry("DD", "Dadra and Nagar Haveli and Daman and Diu"),
            Map.entry("DL", "Delhi"), Map.entry("GA", "Goa"), Map.entry("GJ", "Gujarat"), Map.entry("HR", "Haryana"),
            Map.entry("HP", "Himachal Pradesh"), Map.entry("JK", "Jammu and Kashmir"), Map.entry("JH", "Jharkhand"), Map.entry("KA", "Karnataka"),
            Map.entry("KL", "Kerala"), Map.entry("LA", "Ladakh"), Map.entry("LD", "Lakshadweep"), Map.entry("MP", "Madhya Pradesh"),
            Map.entry("MH", "Maharashtra"), Map.entry("MN", "Manipur"), Map.entry("ML", "Meghalaya"), Map.entry("MZ", "Mizoram"),
            Map.entry("NL", "Nagaland"), Map.entry("OR", "Odisha"), Map.entry("OD", "Odisha"), Map.entry("PY", "Puducherry"),
            Map.entry("PB", "Punjab"), Map.entry("RJ", "Rajasthan"), Map.entry("SK", "Sikkim"), Map.entry("TN", "Tamil Nadu"),
            Map.entry("TS", "Telangana"), Map.entry("TG", "Telangana"), Map.entry("TR", "Tripura"), Map.entry("UP", "Uttar Pradesh"),
            Map.entry("UK", "Uttarakhand"), Map.entry("UT", "Uttarakhand"), Map.entry("WB", "West Bengal"));

    private WooOrderMapper() {
    }

    public static String number(JsonNode o) {
        String n = o.path("number").asText("");
        return n.isBlank() ? o.path("id").asText() : n.trim();
    }

    /** Why an order is left out: not a physical shipment to an address. */
    public static String notShippable(JsonNode o) {
        JsonNode a = address(o);
        if (text(a, "address_1") == null && text(a, "postcode") == null) {
            return "No shipping address.";
        }
        return null;
    }

    public static List<Long> productIds(JsonNode o) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode li : o.path("line_items")) {
            long id = li.path("product_id").asLong(0);
            if (id > 0) {
                ids.add(id);
            }
        }
        return ids;
    }

    public static OrderInput map(JsonNode o, Map<Long, BigDecimal> kgByProduct) {
        JsonNode a = address(o);
        JsonNode bill = o.path("billing");
        String name = join(" ", text(a, "first_name"), text(a, "last_name"));
        if (name == null) {
            name = join(" ", text(bill, "first_name"), text(bill, "last_name"));
        }
        String phone = text(a, "phone") != null ? text(a, "phone") : text(bill, "phone");
        String address = join(", ", text(a, "address_1"), text(a, "address_2"), text(a, "company"));
        String pin = text(a, "postcode") == null ? null : text(a, "postcode").replaceAll("\\s", "");
        String state = text(a, "state");
        if (state != null && "IN".equalsIgnoreCase(text(a, "country") == null ? "IN" : text(a, "country"))) {
            state = IN_STATES.getOrDefault(state.toUpperCase(Locale.ROOT), state.length() <= 3 ? null : state);
        }
        Buyer buyer = new Buyer(name, phone, address, pin, text(a, "city"), state);

        BigDecimal value = money(o, "total");
        List<Item> items = new ArrayList<>();
        BigDecimal kg = BigDecimal.ZERO;
        boolean allWeighed = true;
        for (JsonNode li : o.path("line_items")) {
            int qty = Math.max(1, li.path("quantity").asInt(1));
            BigDecimal price = li.path("price").isNumber() ? li.path("price").decimalValue() : money(li, "price");
            items.add(new Item(li.path("name").asText(""), qty, price));
            BigDecimal w = kgByProduct == null ? null : kgByProduct.get(li.path("product_id").asLong(0));
            if (w == null || w.signum() <= 0) {
                allWeighed = false;
            } else {
                kg = kg.add(w.multiply(BigDecimal.valueOf(qty)));
            }
        }
        BigDecimal weight = allWeighed && kg.signum() > 0 ? kg.setScale(3, RoundingMode.HALF_UP).max(new BigDecimal("0.01")) : null;
        String contents = items.stream().map(Item::name).filter(s -> s != null && !s.isBlank()).distinct().limit(5).collect(Collectors.joining(", "));
        if (contents.length() > 200) {
            contents = contents.substring(0, 197) + "...";
        }
        boolean cod = "cod".equalsIgnoreCase(o.path("payment_method").asText(""));
        Payment payment = cod ? new Payment("COD", value) : new Payment("PREPAID", null);
        return new OrderInput(number(o), buyer, new Parcel(weight, null, null, null, 1, contents.isBlank() ? null : contents, value), payment,
                items.size() > 100 ? items.subList(0, 100) : items, text(o, "customer_note"));
    }

    /** A product weight in the store's unit (kg, g, lbs, oz) in kg; null when not set. */
    public static BigDecimal toKg(String weight, String unit) {
        if (weight == null || weight.isBlank()) {
            return null;
        }
        BigDecimal w;
        try {
            w = new BigDecimal(weight.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        return switch (unit == null ? "kg" : unit.toLowerCase(Locale.ROOT)) {
            case "g" -> w.divide(BigDecimal.valueOf(1000), 4, RoundingMode.HALF_UP);
            case "lbs" -> w.multiply(new BigDecimal("0.45359237"));
            case "oz" -> w.multiply(new BigDecimal("0.028349523"));
            default -> w;
        };
    }

    private static JsonNode address(JsonNode o) {
        JsonNode s = o.path("shipping");
        return text(s, "address_1") != null || text(s, "postcode") != null ? s : o.path("billing");
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

    private static String join(String sep, String... parts) {
        String s = Arrays.stream(parts).filter(p -> p != null && !p.isBlank()).collect(Collectors.joining(sep));
        return s.isEmpty() ? null : s;
    }
}
