package com.courier.modules.customerpricing.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The per-zone rate card of a business customer. {@code COMPANY_ADMIN} writes; authenticated users read and calculate. */
public interface ZoneRateService {

    /**
     * Creates the zone's rate card for one service type ({@code serviceTypeId} null = the card for every service), or fully
     * replaces it (header and slabs) if one exists.
     */
    ZoneRateView save(UUID zoneId, UUID serviceTypeId, SaveCommand command);

    /** The zone's card for one service type; empty if there is none yet. */
    java.util.Optional<ZoneRateView> getByZone(UUID zoneId, UUID serviceTypeId);

    /** Every card of one zone: the default and one per service type. */
    List<ZoneRateView> cardsOfZone(UUID zoneId);

    /** Every rate card of the company's customers, optionally for one customer. */
    List<ZoneRateView> search(UUID customerId);

    ZoneRateView activate(UUID zoneId, UUID serviceTypeId);

    ZoneRateView deactivate(UUID zoneId, UUID serviceTypeId);

    void delete(UUID zoneId, UUID serviceTypeId);

    /**
     * Prices a shipment for a customer and destination from the matching zone's rate card for {@code command.serviceTypeId()};
     * a service type with no card of its own uses the zone's "all services" card.
     */
    Quote calculate(QuoteCommand command);

    /**
     * One price per card of the matching zone that is active, in date and covers the weight: the "all services" card (service
     * type null) and every service type's own. Empty when the customer has no usable card for the destination.
     */
    List<ServiceQuote> calculateEach(QuoteCommand command);

    record ServiceQuote(UUID serviceTypeId, String serviceTypeCode, String serviceTypeName, Quote quote) {
    }

    record SaveCommand(BigDecimal minChargeableWeight, BigDecimal docketCharge, BigDecimal mathadiCharge,
                       BigDecimal packCharge, BigDecimal odaCharge, BigDecimal fuelSurchargePercent,
                       BigDecimal fovPercent, LocalDate effectiveFrom, LocalDate effectiveTo,
                       List<SlabInput> slabs, Long expectedVersion) {
    }

    record SlabInput(BigDecimal minWeight, BigDecimal maxWeight, BigDecimal ratePerKg) {
    }

    record QuoteCommand(UUID customerId, UUID districtId, UUID cityId, BigDecimal actualWeight,
                        BigDecimal declaredValue, boolean odaApplicable, LocalDate bookingDate, UUID serviceTypeId) {

        /** Without a service type: the zone's "all services" card. */
        public QuoteCommand(UUID customerId, UUID districtId, UUID cityId, BigDecimal actualWeight,
                            BigDecimal declaredValue, boolean odaApplicable, LocalDate bookingDate) {
            this(customerId, districtId, cityId, actualWeight, declaredValue, odaApplicable, bookingDate, null);
        }
    }

    record Quote(UUID zoneId, String zoneName, BigDecimal actualWeight, BigDecimal chargeableWeight,
                 BigDecimal ratePerKg, BigDecimal freight, BigDecimal docketCharge, BigDecimal mathadiCharge,
                 BigDecimal packCharge, BigDecimal odaCharge, BigDecimal fuelSurcharge, BigDecimal fovCharge,
                 BigDecimal total) {
    }
}
