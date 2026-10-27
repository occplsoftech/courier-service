package com.courier.modules.tariff.infrastructure;

import com.courier.modules.districtfreight.domain.ZoneTariffPort;
import com.courier.modules.tariff.application.TariffQuoteService;
import com.courier.modules.tariff.domain.TariffPriceCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Answers District Level Freight's question "does the zone tariff price this booking?" with the tariff's freight and
 * the sum of its charges. GST and rounding are left to booking, which applies the branch's GST to freight and Other
 * Charges alike, so the card's GST and rounding are not used here.
 */
@Component
@RequiredArgsConstructor
public class DistrictFreightZoneTariffAdapter implements ZoneTariffPort {

    private final TariffQuoteService quotes;

    @Override
    public Optional<TariffPrice> price(String originPincode, String destinationPincode, UUID serviceTypeId,
                                       UUID packageTypeId, BigDecimal chargeableWeight, Integer pieces,
                                       BigDecimal declaredValue, LocalDate bookingDate) {
        return quotes.quoteIfApplies(new TariffQuoteService.QuoteInput(originPincode, destinationPincode, serviceTypeId,
                        packageTypeId, chargeableWeight, null, null, null, pieces, declaredValue, false, bookingDate))
                .map(q -> new TariffPrice(
                        "Zone tariff %s: %s → %s".formatted(q.card().getCode(), q.from().zone().getCode(), q.to().zone().getCode()),
                        q.price().freight().basis(),
                        q.price().freight().amount(),
                        q.price().charges().stream().map(TariffPriceCalculator.ChargeLine::amount)
                                .reduce(BigDecimal.ZERO, BigDecimal::add)));
    }
}
