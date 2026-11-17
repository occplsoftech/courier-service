package com.courier.modules.platformbookings.application;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Who carries a booking, in words: "OCCPL Network", a courier's own name ("Delhivery"), or for an aggregator the real courier and the
 * aggregator ("Blue Dart via Shiprocket"), taken from the service name the aggregator gave ("DELHIVERY Economy (Surface)").
 */
public final class CarrierLabel {

    public static final String OCCPL = "OCCPL Network";

    /** Aggregators: they book with other couriers, named in their service. */
    static final Map<String, String> AGGREGATORS = Map.of("SHIPYAARI", "Shipyaari", "SHIPROCKET", "Shiprocket");

    private record Courier(String name, Pattern match) {
    }

    private static final List<Courier> COURIERS = List.of(
            c("Blue Dart", "blue[ ]*dart"), c("Delhivery", "delhivery"), c("Xpressbees", "xpress[ ]*bees"), c("Ekart", "ekart"),
            c("DTDC", "dtdc"), c("Ecom Express", "ecom express|ecomexpress"), c("Shadowfax", "shadowfax"), c("Amazon Shipping", "amazon"),
            c("India Post", "india[ ]*post|speed[ ]*post"), c("Gati", "gati"), c("Smartr", "smartr"), c("Professional", "professional"),
            c("Shree Maruti", "maruti"), c("Trackon", "trackon"), c("Dotzot", "dotzot"), c("Movin", "movin"));

    private CarrierLabel() {
    }

    private static Courier c(String name, String regex) {
        return new Courier(name, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
    }

    /** The courier an aggregator's service name points at, or null. */
    static String courierIn(String service) {
        if (service == null) {
            return null;
        }
        for (Courier c : COURIERS) {
            if (c.match().matcher(service).find()) {
                return c.name();
            }
        }
        return null;
    }

    /**
     * The pattern (lower case, usable in MySQL REGEXP as well as Java) that finds this courier in an aggregator's service name; for a name
     * that is not a known courier, the name itself (letters, digits and spaces only).
     */
    public static String patternFor(String courier) {
        for (Courier c : COURIERS) {
            if (c.name().equalsIgnoreCase(courier)) {
                return c.match().pattern();
            }
        }
        String plain = courier == null ? "" : courier.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "").trim();
        return plain.isEmpty() ? "^$" : plain;
    }

    /**
     * @param providerCode null or OCCPL_NETWORK for OCCPL's own network
     * @param providerName the provider's display name (e.g. "Delhivery"), when known
     */
    public static String of(String providerCode, String providerName, String service) {
        if (providerCode == null || providerCode.isBlank() || "OCCPL_NETWORK".equals(providerCode)) {
            return OCCPL;
        }
        String code = providerCode.toUpperCase(Locale.ROOT);
        String aggregator = AGGREGATORS.get(code);
        if (aggregator != null) {
            String real = courierIn(service);
            return real == null ? aggregator : real + " via " + aggregator;
        }
        return providerName == null || providerName.isBlank() ? providerCode : providerName;
    }
}
