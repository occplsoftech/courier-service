package com.courier.modules.customerportal.rates.application;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/** {@code app.customer-portal.*}: rate comparison settings and external courier credentials. */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.customer-portal")
public class CustomerPortalProperties {

    /** How long a comparison may be booked without asking the providers again. */
    private Duration quoteValidity = Duration.ofMinutes(30);

    /** Longest wait for one provider; a slower one is shown as unavailable. */
    private Duration providerTimeout = Duration.ofSeconds(10);

    /** cm³ per kg for the standard volumetric weight stored on a quote (each courier still applies its own). */
    private int volumetricDivisor = 5000;

    /** Markup on external rates when no pricing_markup_rules row applies. */
    private BigDecimal defaultMarkupPercent = new BigDecimal("10");

    /** GST charged on OCCPL's markup (a service of OCCPL). Confirm with the accountant before go-live. */
    private BigDecimal markupGstPercent = new BigDecimal("18");

    /**
     * GST on OCCPL network rates. The zone rate card is pre-tax; at the counter GST comes from the
     * booking branch's own percentage — keep this equal to it.
     */
    private BigDecimal occplNetworkGstPercent = new BigDecimal("18");

    private WalletPayment walletPayment = new WalletPayment();
    private Shiprocket shiprocket = new Shiprocket();
    private Delhivery delhivery = new Delhivery();
    private Shipyaari shipyaari = new Shipyaari();
    private Skart skart = new Skart();
    private TrackingSync trackingSync = new TrackingSync();

    /**
     * {@code app.customer-portal.wallet-payment.*}: paying for portal bookings from the customer's wallet.
     * Off by default so deploying the code changes nothing; switch it on once customers have funded wallets.
     */
    @Getter
    @Setter
    public static class WalletPayment {
        /** On: a booking is debited from the customer's wallet before the courier is asked, and refunded if refused. */
        private boolean enabled = false;
        /**
         * Providers that are not paid from the wallet. None by default: OCCPL's own network is debited at booking like any
         * other courier (V-wallet decision, 2026-10), so the branch must not bill the same parcel again at pickup.
         */
        private java.util.List<String> excludedProviders = new java.util.ArrayList<>();

        public boolean covers(String providerCode) {
            return enabled && (excludedProviders == null || excludedProviders.stream().noneMatch(p -> p.equalsIgnoreCase(providerCode)));
        }
    }

    /** {@code app.customer-portal.tracking-sync.*}: keeping booked shipments' delivery status fresh (V135). */
    @Getter
    @Setter
    public static class TrackingSync {
        /** Off: the background job asks no courier (webhooks are still accepted). */
        private boolean enabled = true;
        /** Between checks of a shipment still on its way. */
        private Duration interval = Duration.ofHours(1);
        /** After a webhook: how soon the job confirms with the courier. */
        private Duration afterWebhook = Duration.ofSeconds(30);
        /** Bookings older than this are no longer checked, finished or not. */
        private Duration maxAge = Duration.ofDays(45);
        /** Most shipments one run asks about (each is one call to its courier). */
        private int batchSize = 100;
        /** The address couriers reach this server on, for the webhook URL shown to administrators. Blank = APP_BASE_URL. */
        private String publicBaseUrl = "";
    }

    @Getter
    @Setter
    public static class Shiprocket {
        private boolean enabled = false;
        private String baseUrl = "https://apiv2.shiprocket.in";
        /** API user created in the Shiprocket panel (Settings → API). */
        private String email;
        private String password;
        /** Pickup location nickname registered in the Shiprocket panel (needed for booking). */
        private String pickupLocation;

        public boolean configured() {
            return enabled && notBlank(email) && notBlank(password);
        }

        public boolean bookable() {
            return configured() && notBlank(pickupLocation);
        }
    }

    @Getter
    @Setter
    public static class Delhivery {
        private boolean enabled = false;
        /** https://staging-express.dlv.delhivery.com for the test account, https://track.delhivery.com live. */
        private String baseUrl = "https://track.delhivery.com";
        private String token;
        /** Pickup location name registered with Delhivery (needed for booking). */
        private String pickupLocation;

        public boolean configured() {
            return enabled && notBlank(token);
        }

        public boolean bookable() {
            return configured() && notBlank(pickupLocation);
        }
    }

    /** Shipyaari (aggregator): an API user's e-mail and password; the order carries its own pickup address. */
    @Getter
    @Setter
    public static class Shipyaari {
        private boolean enabled = false;
        /** https://stage-api-seller.shipyaari.com for the staging account, https://api-seller.shipyaari.com live. */
        private String baseUrl = "https://api-seller.shipyaari.com";
        private String email;
        private String password;

        public boolean configured() {
            return enabled && notBlank(email) && notBlank(password);
        }

        public boolean bookable() {
            return configured();
        }
    }

    /**
     * sKart (international, aggregator of DHL, FedEx, UPS, Aramex, Skynet…): the franchisee account's user name and password,
     * sent with every call. The documented base URL is the development system; the live one comes from sKart.
     */
    @Getter
    @Setter
    public static class Skart {
        private boolean enabled = false;
        private String baseUrl = "https://devapiv2.skart-express.com/api/v1/booking";
        private String userName;
        private String password;

        public boolean configured() {
            return enabled && notBlank(userName) && notBlank(password);
        }
    }

    static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
