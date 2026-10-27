package com.courier.modules.districtfreight.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * What District Level Freight asks of the company's zone tariff ({@code modules/tariff} supplies the adapter): the
 * freight and extra charges for a booking, or empty when the tariff does not price shipments from the origin pincode
 * (the booking branch's) on the date. Once it applies it is authoritative: a missing rate is thrown, not skipped.
 */
public interface ZoneTariffPort {

    Optional<TariffPrice> price(String originPincode, String destinationPincode, UUID serviceTypeId, UUID packageTypeId,
                                BigDecimal chargeableWeight, Integer pieces, BigDecimal declaredValue, LocalDate bookingDate);

    /**
     * @param label   "Zone tariff PUNE_CASH: PUNE → CENTRAL", shown where the freight came from
     * @param basis   how the freight was worked out, e.g. "200 up to 1 kg + 2 × 200 per extra kg"
     * @param extras  the card's charges (fuel, hamali, docket…) before GST, carried as Other Charges
     */
    record TariffPrice(String label, String basis, BigDecimal freight, BigDecimal extras) {
    }
}
