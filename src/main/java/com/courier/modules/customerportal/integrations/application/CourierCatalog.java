package com.courier.modules.customerportal.integrations.application;

import java.util.List;
import java.util.Optional;

/**
 * The couriers a company can connect and the fields each one needs. {@code available=false} means the portal has no
 * integration code for that courier yet, so it cannot be connected however many credentials are entered.
 * Base URLs are deliberately not fields: a company administrator must not be able to point the server at an arbitrary host.
 */
public final class CourierCatalog {

    public record Field(String key, String label, boolean secret, boolean required, String help) {
    }

    public record Provider(String code, String name, boolean available, String note, List<Field> fields) {
        public Optional<Field> field(String key) {
            return fields.stream().filter(f -> f.key().equals(key)).findFirst();
        }
    }

    public static final String SHIPROCKET = "SHIPROCKET";
    public static final String DELHIVERY = "DELHIVERY";
    public static final String SHIPYAARI = "SHIPYAARI";
    public static final String SKART = "SKART";

    private static final List<Provider> PROVIDERS = List.of(
            new Provider(SHIPROCKET, "Shiprocket", true,
                    "Create a separate API user in the Shiprocket panel (Settings → API) instead of using the owner login.",
                    List.of(new Field("email", "API user e-mail", false, true, "The API user's login e-mail."),
                            new Field("password", "API user password", true, true, "Stored encrypted; never shown again."),
                            new Field("pickupLocation", "Pickup location name", false, true,
                                    "Exactly as registered in the Shiprocket panel (Settings → Pickup addresses)."))),
            new Provider(DELHIVERY, "Delhivery", true,
                    "Use the API token from your Delhivery account (staging and live accounts have different tokens).",
                    List.of(new Field("token", "API token", true, true, "Stored encrypted; never shown again."),
                            new Field("pickupLocation", "Pickup location name", false, true,
                                    "Exactly as registered with Delhivery (client warehouse name)."))),
            new Provider(SHIPYAARI, "Shipyaari", true,
                    "Use the e-mail and password of your Shipyaari seller account (a staging account has its own). "
                            + "Orders are paid from that account's Shipyaari wallet, so keep it funded and its KYC complete.",
                    List.of(new Field("email", "Account e-mail", false, true, "The e-mail you sign in to Shipyaari with."),
                            new Field("password", "Account password", true, true, "Stored encrypted; never shown again."))),
            new Provider(SKART, "sKart International", true,
                    "International shipments (DHL, FedEx, UPS, Aramex, Skynet and more) through your sKart franchisee account. "
                            + "Shown in the portal's international rate calculator.",
                    List.of(new Field("userName", "User name", false, true, "The user name of your sKart API account."),
                            new Field("password", "Password", true, true, "Stored encrypted; never shown again."))),
            new Provider("BLUEDART", "Blue Dart", false, "Integration not built yet.", List.of()),
            new Provider("EKART", "Ekart", false, "Integration not built yet.", List.of()));

    private CourierCatalog() {
    }

    public static List<Provider> all() {
        return PROVIDERS;
    }

    public static Optional<Provider> find(String code) {
        return PROVIDERS.stream().filter(p -> p.code().equalsIgnoreCase(code == null ? "" : code.trim())).findFirst();
    }
}
