package com.courier.modules.customerportal.rates.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a provider is asked to book: the frozen quote option and the shipment as the customer entered it.
 * {@code reference} is our booking id — sent to the provider as its order id, so a provider that rejects
 * duplicate order ids also protects against double booking on its side.
 */
public record BookingRequest(UUID companyId,
                             UUID customerId,
                             String reference,
                             String serviceCode,
                             RateRequest rate,
                             Party pickup,
                             Party delivery,
                             String contents,
                             BigDecimal customerTotal,
                             List<ChargeLine> charges,
                             BigDecimal chargeableWeightKg,
                             String pickupLocation) {

    /** Without a courier pickup location name (the company's own is used). */
    public BookingRequest(UUID companyId, UUID customerId, String reference, String serviceCode, RateRequest rate, Party pickup,
                          Party delivery, String contents, BigDecimal customerTotal, List<ChargeLine> charges,
                          BigDecimal chargeableWeightKg) {
        this(companyId, customerId, reference, serviceCode, rate, pickup, delivery, contents, customerTotal, charges, chargeableWeightKg, null);
    }

    /** The pickup location name to book from: the one given for this booking, else the company's saved one. */
    public String pickupLocationOr(String companyDefault) {
        return pickupLocation == null || pickupLocation.isBlank() ? companyDefault : pickupLocation;
    }

    /** Without the quoted chargeable weight. */
    public BookingRequest(UUID companyId, UUID customerId, String reference, String serviceCode, RateRequest rate, Party pickup,
                          Party delivery, String contents, BigDecimal customerTotal, List<ChargeLine> charges) {
        this(companyId, customerId, reference, serviceCode, rate, pickup, delivery, contents, customerTotal, charges, null);
    }

    /** Without the charge breakdown (providers that book from the rate alone). */
    public BookingRequest(UUID companyId, UUID customerId, String reference, String serviceCode, RateRequest rate, Party pickup,
                          Party delivery, String contents, BigDecimal customerTotal) {
        this(companyId, customerId, reference, serviceCode, rate, pickup, delivery, contents, customerTotal, List.of());
    }

    /**
     * A sender or receiver. {@code lat}/{@code lng} are the map pin the customer placed (Porter sends its rider there); null when
     * none was placed.
     */
    public record Party(String name, String phone, String address, String pincode, String city, String state,
                        BigDecimal lat, BigDecimal lng) {

        public Party(String name, String phone, String address, String pincode, String city, String state) {
            this(name, phone, address, pincode, city, state, null, null);
        }

        public static Party of(Object raw) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = raw instanceof Map<?, ?> ? (Map<String, Object>) raw : Map.of();
            BigDecimal lat = number(m.get("lat"));
            BigDecimal lng = number(m.get("lng"));
            boolean pinned = lat != null && lng != null && lat.abs().compareTo(BigDecimal.valueOf(90)) <= 0
                    && lng.abs().compareTo(BigDecimal.valueOf(180)) <= 0 && lat.signum() != 0 && lng.signum() != 0;
            return new Party(s(m.get("name")), s(m.get("phone")), s(m.get("address")), s(m.get("pincode")),
                    s(m.get("city")), s(m.get("state")), pinned ? lat : null, pinned ? lng : null);
        }

        public boolean pinned() {
            return lat != null && lng != null;
        }

        private static BigDecimal number(Object o) {
            if (o == null) {
                return null;
            }
            try {
                return new BigDecimal(String.valueOf(o).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private static String s(Object o) {
            return o == null ? null : String.valueOf(o);
        }
    }
}
