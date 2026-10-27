package com.courier.modules.tariff.application;

import com.courier.modules.tariff.domain.TariffPriceCalculator;
import com.courier.modules.tariff.domain.TariffRateCard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Prices a shipment on the zone tariff: the pickup pincode's FROM zone picks the active rate card, the delivery
 * pincode's TO zone picks the lane, and the card's slabs, charges and GST do the rest.
 */
public interface TariffQuoteService {

    Quote quote(QuoteInput input);

    /** {@code bookingDate} null means today (India time). Dimensions per piece in cm, optional. */
    record QuoteInput(String originPincode, String destinationPincode, UUID serviceTypeId, UUID packageTypeId,
                      BigDecimal actualWeightKg, BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm,
                      Integer pieces, BigDecimal declaredValue, boolean cod, LocalDate bookingDate) {
    }

    record Quote(TariffRateCard card, TariffZoneService.Resolution from, TariffZoneService.Resolution to,
                 String serviceTypeCode, String packageTypeCode, boolean oda, TariffPriceCalculator.Price price) {
    }
}
