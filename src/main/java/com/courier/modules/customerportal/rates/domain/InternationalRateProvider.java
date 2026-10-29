package com.courier.modules.customerportal.rates.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A provider that prices shipments abroad (sKart). Separate from the domestic {@link CourierProviderAdapter#getRates}: the
 * destination is a country and a foreign postcode, not an Indian pincode. Implementations never throw for a provider
 * failure: they answer {@link ProviderResult#unavailable} with a reason the customer may read.
 */
public interface InternationalRateProvider {

    /** What an international price check needs. {@code kind}: DOCUMENT, PARCEL or COMMERCIAL. */
    record Request(UUID companyId, String originPincode, String destinationCountry, String destinationPostcode, Kind kind,
                   BigDecimal weightKg, int pieces, BigDecimal maxLengthCm) {
    }

    enum Kind { PARCEL, DOCUMENT, COMMERCIAL }

    /** One destination the provider ships to. {@code postcodeRequired}: the provider prices by postcode there. */
    record Country(int id, String code, String name, String region, boolean postcodeRequired) {
    }

    ProviderResult internationalRates(Request request);

    /** Every destination country; empty when the provider cannot be asked. */
    List<Country> countries(UUID companyId);
}
