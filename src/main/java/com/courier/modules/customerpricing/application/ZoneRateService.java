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
                       List<SlabInput> slabs, Long expectedVersion, BigDecimal minFreight,
                       com.courier.modules.customerpricing.domain.SlabMode slabMode) {

        /** Without a minimum freight, pricing the whole weight at one slab (as before 11.6). */
        public SaveCommand(BigDecimal minChargeableWeight, BigDecimal docketCharge, BigDecimal mathadiCharge,
                           BigDecimal packCharge, BigDecimal odaCharge, BigDecimal fuelSurchargePercent,
                           BigDecimal fovPercent, LocalDate effectiveFrom, LocalDate effectiveTo,
                           List<SlabInput> slabs, Long expectedVersion) {
            this(minChargeableWeight, docketCharge, mathadiCharge, packCharge, odaCharge, fuelSurchargePercent, fovPercent,
                    effectiveFrom, effectiveTo, slabs, expectedVersion, null, null);
        }
    }

    /** {@code ratePerKg} is the price per kg, or the flat price of the whole slab when {@code fixedRate}. */
    record SlabInput(BigDecimal minWeight, BigDecimal maxWeight, BigDecimal ratePerKg, boolean fixedRate) {

        public SlabInput(BigDecimal minWeight, BigDecimal maxWeight, BigDecimal ratePerKg) {
            this(minWeight, maxWeight, ratePerKg, false);
        }
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
                 BigDecimal total, boolean fixedRate, BigDecimal minChargeableWeight, boolean tiered,
                 boolean minFreightApplied) {

        /** Whole-weight pricing, no minimum freight applied. */
        public Quote(UUID zoneId, String zoneName, BigDecimal actualWeight, BigDecimal chargeableWeight, BigDecimal ratePerKg,
                     BigDecimal freight, BigDecimal docketCharge, BigDecimal mathadiCharge, BigDecimal packCharge, BigDecimal odaCharge,
                     BigDecimal fuelSurcharge, BigDecimal fovCharge, BigDecimal total, boolean fixedRate, BigDecimal minChargeableWeight) {
            this(zoneId, zoneName, actualWeight, chargeableWeight, ratePerKg, freight, docketCharge, mathadiCharge, packCharge,
                    odaCharge, fuelSurcharge, fovCharge, total, fixedRate, minChargeableWeight, false, false);
        }

        /** Without the card's minimum chargeable weight. */
        public Quote(UUID zoneId, String zoneName, BigDecimal actualWeight, BigDecimal chargeableWeight, BigDecimal ratePerKg,
                     BigDecimal freight, BigDecimal docketCharge, BigDecimal mathadiCharge, BigDecimal packCharge, BigDecimal odaCharge,
                     BigDecimal fuelSurcharge, BigDecimal fovCharge, BigDecimal total, boolean fixedRate) {
            this(zoneId, zoneName, actualWeight, chargeableWeight, ratePerKg, freight, docketCharge, mathadiCharge, packCharge,
                    odaCharge, fuelSurcharge, fovCharge, total, fixedRate, null);
        }

        /** {@code ratePerKg} is the slab's rate, or (fixed slab) the flat price spread over the chargeable weight. */
        public Quote(UUID zoneId, String zoneName, BigDecimal actualWeight, BigDecimal chargeableWeight, BigDecimal ratePerKg,
                     BigDecimal freight, BigDecimal docketCharge, BigDecimal mathadiCharge, BigDecimal packCharge, BigDecimal odaCharge,
                     BigDecimal fuelSurcharge, BigDecimal fovCharge, BigDecimal total) {
            this(zoneId, zoneName, actualWeight, chargeableWeight, ratePerKg, freight, docketCharge, mathadiCharge, packCharge,
                    odaCharge, fuelSurcharge, fovCharge, total, false);
        }
    }
}
