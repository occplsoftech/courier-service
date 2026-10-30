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
                             List<ChargeLine> charges) {

    /** Without the charge breakdown (providers that book from the rate alone). */
    public BookingRequest(UUID companyId, UUID customerId, String reference, String serviceCode, RateRequest rate, Party pickup,
                          Party delivery, String contents, BigDecimal customerTotal) {
        this(companyId, customerId, reference, serviceCode, rate, pickup, delivery, contents, customerTotal, List.of());
    }

    public record Party(String name, String phone, String address, String pincode, String city, String state) {
        public static Party of(Object raw) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = raw instanceof Map<?, ?> ? (Map<String, Object>) raw : Map.of();
            return new Party(s(m.get("name")), s(m.get("phone")), s(m.get("address")), s(m.get("pincode")),
                    s(m.get("city")), s(m.get("state")));
        }

        private static String s(Object o) {
            return o == null ? null : String.valueOf(o);
        }
    }
}
