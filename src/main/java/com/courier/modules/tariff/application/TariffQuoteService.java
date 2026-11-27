package com.courier.modules.tariff.application;

import com.courier.modules.tariff.domain.TariffPriceCalculator;
import com.courier.modules.tariff.domain.TariffRateCard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Prices a shipment on the zone tariff: the pickup pincode's FROM zone picks the active rate card, the delivery
 * pincode's TO zone picks the lane, and the card's slabs, charges and GST do the rest.
 */
public interface TariffQuoteService {

    Quote quote(QuoteInput input);

    /**
     * Like {@link #quote}, but empty when the zone tariff does not apply to the pickup pincode: it is in no from zone,
     * or that zone has no active rate card on the date. Once a card applies it is authoritative, so a missing to zone
     * or lane is still refused, not skipped.
     */
    Optional<Quote> quoteIfApplies(QuoteInput input);

    /**
     * Every lane the applicable card has to the delivery pincode's zone for documents ({@code documents}) or for
     * parcels, one quote each, cheapest first. Empty when the tariff does not apply or has no such lane. The input's
     * service and package type are ignored.
     */
    List<Quote> quoteOptions(QuoteInput input, boolean documents);

    /**
     * Every lane, documents and parcels, of the card that applies to the pickup pincode, to the delivery pincode's zone:
     * the calculator's "all options" view, cheapest first. Unlike {@link #quoteOptions} a pickup pincode the tariff does
     * not cover is refused with the reason. Lanes that cannot price this shipment (too heavy) come back in
     * {@code unavailable} with why. The input's service and package type are ignored.
     */
    Options quoteAll(QuoteInput input);

    /**
     * The volumetric divisor each parcel service of today's active rate cards prices with: the card's own when it has
     * one, else the service type's, else the deployment default — the divisor {@link #quote} uses. Services with
     * document lanes only are left out (documents never use volumetric weight). Ordered by service priority, then code.
     */
    List<ServiceDivisor> volumetricDivisors();

    record ServiceDivisor(String serviceTypeCode, String serviceTypeName, int divisor) {
    }

    record Unavailable(String serviceTypeCode, String serviceTypeName, String packageTypeCode, String reason) {
    }

    record Options(List<Quote> quotes, List<Unavailable> unavailable) {
    }

    /**
     * {@code bookingDate} null means today (India time). Dimensions per piece in cm, optional. {@code customerId}: the
     * business customer booked for, or null; when that customer uses business rates, a lane on their own rate card
     * (V183) prices before the company's card.
     */
    record QuoteInput(String originPincode, String destinationPincode, UUID serviceTypeId, UUID packageTypeId,
                      BigDecimal actualWeightKg, BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm,
                      Integer pieces, BigDecimal declaredValue, boolean cod, LocalDate bookingDate, UUID customerId) {

        public QuoteInput(String originPincode, String destinationPincode, UUID serviceTypeId, UUID packageTypeId,
                          BigDecimal actualWeightKg, BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm,
                          Integer pieces, BigDecimal declaredValue, boolean cod, LocalDate bookingDate) {
            this(originPincode, destinationPincode, serviceTypeId, packageTypeId, actualWeightKg, lengthCm, widthCm,
                    heightCm, pieces, declaredValue, cod, bookingDate, null);
        }
    }

    /**
     * {@code deliveryDays}: from the delivery pincode's TAT for the network this lane runs on when the pincode master
     * has one ({@code tatHours}, rounded up to whole days), else the service type's own delivery days.
     */
    record Quote(TariffRateCard card, TariffZoneService.Resolution from, TariffZoneService.Resolution to,
                 UUID serviceTypeId, String serviceTypeCode, String serviceTypeName, Integer deliveryDays,
                 UUID packageTypeId, String packageTypeCode, boolean oda, TariffPriceCalculator.Price price,
                 Integer tatHours) {

        public Quote(TariffRateCard card, TariffZoneService.Resolution from, TariffZoneService.Resolution to,
                     UUID serviceTypeId, String serviceTypeCode, String serviceTypeName, Integer deliveryDays,
                     UUID packageTypeId, String packageTypeCode, boolean oda, TariffPriceCalculator.Price price) {
            this(card, from, to, serviceTypeId, serviceTypeCode, serviceTypeName, deliveryDays, packageTypeId,
                    packageTypeCode, oda, price, null);
        }
    }
}
