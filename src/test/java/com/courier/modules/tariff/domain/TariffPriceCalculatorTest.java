package com.courier.modules.tariff.domain;

import com.courier.modules.tariff.domain.TariffPriceCalculator.Price;
import com.courier.modules.tariff.domain.TariffPriceCalculator.Shipment;
import com.courier.shared.exception.BusinessRuleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The tariff arithmetic, with the rows of OCCPL Courier's cash-booking sheet. */
class TariffPriceCalculatorTest {

    private static final UUID CENTRAL = UUID.randomUUID();
    private static final UUID KERALA = UUID.randomUUID();
    private static final UUID AIR = UUID.randomUUID();
    private static final UUID PREMIUM = UUID.randomUUID();
    private static final UUID DOC = UUID.randomUUID();
    private static final UUID NONDOC = UUID.randomUUID();

    /** PUNE → CENTRAL, BY AIR, Document: three fixed slabs of 200 up to 1 kg, then 200 per extra kg up to 99 kg. */
    private static final List<TariffRateSlab> AIR_DOC = List.of(
            fixed("0.001", "0.25", "200"), fixed("0.251", "0.5", "200"), fixed("0.501", "1", "200"),
            perKg("1.001", "99", "200", null));

    private static TariffRateSlab fixed(String from, String to, String rate) {
        return TariffRateSlab.builder().weightFromKg(new BigDecimal(from)).weightToKg(new BigDecimal(to))
                .rateType(SlabRateType.FIXED).rate(new BigDecimal(rate)).build();
    }

    private static TariffRateSlab perKg(String from, String to, String rate, String step) {
        return TariffRateSlab.builder().weightFromKg(new BigDecimal(from)).weightToKg(new BigDecimal(to))
                .rateType(SlabRateType.PER_EXTRA_KG).rate(new BigDecimal(rate))
                .stepKg(new BigDecimal(step == null ? "1" : step)).build();
    }

    private static BigDecimal freight(List<TariffRateSlab> lane, String kg) {
        return TariffPriceCalculator.freight(lane, new BigDecimal(kg)).amount();
    }

    private static TariffRateCard card(Integer divisor, TotalRounding rounding) {
        return TariffRateCard.builder().code("PUNE_CASH").name("Pune cash").fromZoneId(UUID.randomUUID())
                .effectiveFrom(LocalDate.of(2025, 4, 1)).gstPercent(new BigDecimal("18")).volumetricDivisor(divisor)
                .totalRounding(rounding).build();
    }

    private static Shipment shipment(String kg, boolean document, boolean sameState) {
        return new Shipment(new BigDecimal(kg), null, null, null, 1, null, false, false, document,
                CENTRAL, AIR, document ? DOC : NONDOC, sameState);
    }

    private static TariffChargeRule rule(String code, ChargeCalcMethod method, String value) {
        return TariffChargeRule.builder().chargeCode(code).chargeName(code).calcMethod(method)
                .chargeValue(new BigDecimal(value)).build();
    }

    @Test
    @DisplayName("up to the last fixed slab the fixed rate is the freight")
    void fixedSlabs() {
        assertThat(freight(AIR_DOC, "0.2")).isEqualByComparingTo("200");
        assertThat(freight(AIR_DOC, "0.5")).isEqualByComparingTo("200");
        assertThat(freight(AIR_DOC, "1")).isEqualByComparingTo("200");
    }

    @Test
    @DisplayName("above it: the fixed rate plus the per-kg rate for each extra kg or part of a kg")
    void perExtraKg() {
        assertThat(freight(AIR_DOC, "1.001")).isEqualByComparingTo("400");
        assertThat(freight(AIR_DOC, "2")).isEqualByComparingTo("400");
        assertThat(freight(AIR_DOC, "2.3")).isEqualByComparingTo("600");
        assertThat(TariffPriceCalculator.freight(AIR_DOC, new BigDecimal("2.3")).basis())
                .isEqualTo("200 up to 1 kg + 2 × 200 per extra kg");
    }

    @Test
    @DisplayName("cargo: fixed up to 20 kg, then per extra kg")
    void cargo() {
        List<TariffRateSlab> cargo = List.of(fixed("0.001", "20", "1200"), perKg("20.001", "9999", "45", null));
        assertThat(freight(cargo, "18")).isEqualByComparingTo("1200");
        assertThat(freight(cargo, "25")).isEqualByComparingTo("1425");
        assertThat(freight(cargo, "25.4")).isEqualByComparingTo("1470");
    }

    @Test
    @DisplayName("a step other than 1 kg counts each step or part of one")
    void halfKgStep() {
        List<TariffRateSlab> premium = List.of(fixed("0.001", "0.5", "250"), perKg("0.501", "9999", "250", "0.5"));
        assertThat(freight(premium, "0.5")).isEqualByComparingTo("250");
        assertThat(freight(premium, "1.2")).isEqualByComparingTo("750");
    }

    @Test
    @DisplayName("above the heaviest slab there is no price")
    void aboveLimit() {
        assertThatThrownBy(() -> freight(AIR_DOC, "100"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("above this rate's limit of 99 kg");
    }

    @Test
    @DisplayName("volumetric weight counts when heavier, but never for documents or a card without a divisor")
    void volumetric() {
        Shipment box = new Shipment(new BigDecimal("2"), new BigDecimal("40"), new BigDecimal("30"), new BigDecimal("20"),
                1, null, false, false, false, CENTRAL, AIR, NONDOC, true);
        Price p = TariffPriceCalculator.price(card(5000, TotalRounding.NONE), AIR_DOC, List.of(), box);
        assertThat(p.volumetricWeightKg()).isEqualByComparingTo("4.8");
        assertThat(p.chargeableWeightKg()).isEqualByComparingTo("4.8");
        assertThat(p.freight().amount()).isEqualByComparingTo("1000");

        Shipment envelope = new Shipment(new BigDecimal("0.3"), new BigDecimal("40"), new BigDecimal("30"), new BigDecimal("20"),
                1, null, false, false, true, CENTRAL, AIR, DOC, true);
        assertThat(TariffPriceCalculator.price(card(5000, TotalRounding.NONE), AIR_DOC, List.of(), envelope).chargeableWeightKg())
                .isEqualByComparingTo("0.3");
        assertThat(TariffPriceCalculator.price(card(null, TotalRounding.NONE), AIR_DOC, List.of(), box).chargeableWeightKg())
                .isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("charges, GST in the same state and rounding: fuel % of freight, hamali per kg with a minimum, docket not taxed")
    void fullQuoteSameState() {
        TariffChargeRule fuel = rule("FUEL", ChargeCalcMethod.PERCENT_OF_FREIGHT, "10");
        TariffChargeRule hamali = rule("HAMALI", ChargeCalcMethod.PER_KG, "5");
        hamali.setMinAmount(new BigDecimal("20"));
        TariffChargeRule docket = rule("DOCKET", ChargeCalcMethod.FLAT, "30");
        docket.setTaxable(false);

        Price p = TariffPriceCalculator.price(card(null, TotalRounding.NEAREST_RUPEE), AIR_DOC, List.of(fuel, hamali, docket),
                shipment("2.3", false, true));

        // Freight 600; fuel 60; hamali 3 kg × 5 = 15 → min 20; taxable 680; CGST 61.20 + SGST 61.20; docket 30.
        assertThat(p.freight().amount()).isEqualByComparingTo("600");
        assertThat(p.charges()).extracting(TariffPriceCalculator.ChargeLine::code).containsExactly("DOCKET", "FUEL", "HAMALI");
        assertThat(p.taxableAmount()).isEqualByComparingTo("680");
        assertThat(p.taxes()).extracting(TariffPriceCalculator.TaxLine::code).containsExactly("CGST", "SGST");
        assertThat(p.taxTotal()).isEqualByComparingTo("122.40");
        assertThat(p.nonTaxableAmount()).isEqualByComparingTo("30");
        assertThat(p.roundOff()).isEqualByComparingTo("-0.40");
        assertThat(p.total()).isEqualByComparingTo("832");
    }

    @Test
    @DisplayName("to another state: IGST at the full rate")
    void igst() {
        Price p = TariffPriceCalculator.price(card(null, TotalRounding.NONE), AIR_DOC, List.of(), shipment("1", true, false));
        assertThat(p.taxes()).singleElement().satisfies(t -> {
            assertThat(t.code()).isEqualTo("IGST");
            assertThat(t.amount()).isEqualByComparingTo("36");
        });
        assertThat(p.total()).isEqualByComparingTo("236");
    }

    @Test
    @DisplayName("per charge code the most specific matching rule wins; rules for another lane are ignored")
    void specificity() {
        TariffChargeRule all = rule("FUEL", ChargeCalcMethod.PERCENT_OF_FREIGHT, "10");
        TariffChargeRule air = rule("FUEL", ChargeCalcMethod.PERCENT_OF_FREIGHT, "15");
        air.setServiceTypeId(AIR);
        TariffChargeRule premium = rule("FUEL", ChargeCalcMethod.PERCENT_OF_FREIGHT, "20");
        premium.setServiceTypeId(PREMIUM);
        TariffChargeRule kerala = rule("ODA_KERALA", ChargeCalcMethod.FLAT, "100");
        kerala.setToZoneId(KERALA);

        Price p = TariffPriceCalculator.price(card(null, TotalRounding.NONE), AIR_DOC, List.of(all, air, premium, kerala),
                shipment("1", true, true));

        assertThat(p.charges()).singleElement().satisfies(c -> {
            assertThat(c.code()).isEqualTo("FUEL");
            assertThat(c.amount()).isEqualByComparingTo("30");
        });
    }

    @Test
    @DisplayName("COD and ODA charges only when the shipment is COD or the pincode is ODA; % of value with a minimum")
    void conditional() {
        TariffChargeRule cod = rule("COD", ChargeCalcMethod.PERCENT_OF_VALUE, "2");
        cod.setAppliesWhen(ChargeAppliesWhen.COD_ONLY);
        cod.setMinAmount(new BigDecimal("50"));
        TariffChargeRule oda = rule("ODA", ChargeCalcMethod.FLAT, "150");
        oda.setAppliesWhen(ChargeAppliesWhen.ODA_ONLY);
        TariffChargeRule inactive = rule("FOV", ChargeCalcMethod.FLAT, "10");
        inactive.setStatus(TariffStatus.INACTIVE);
        List<TariffChargeRule> rules = List.of(cod, oda, inactive);
        TariffRateCard card = card(null, TotalRounding.NONE);

        assertThat(TariffPriceCalculator.price(card, AIR_DOC, rules, shipment("1", true, true)).charges()).isEmpty();

        Shipment codOda = new Shipment(BigDecimal.ONE, null, null, null, 1, new BigDecimal("4000"), true, true, true,
                CENTRAL, AIR, DOC, true);
        assertThat(TariffPriceCalculator.price(card, AIR_DOC, rules, codOda).charges())
                .extracting(TariffPriceCalculator.ChargeLine::code, c -> c.amount().toPlainString())
                .containsExactly(org.assertj.core.groups.Tuple.tuple("COD", "80.00"), org.assertj.core.groups.Tuple.tuple("ODA", "150.00"));
    }

    @Test
    @DisplayName("a lane is refused when slabs overlap, leave a gap, or start per kg")
    void laneValidation() {
        assertThat(TariffRateSlab.validateLane(new java.util.ArrayList<>(List.of(perKg("1.001", "99", "200", null),
                fixed("0.001", "1", "200"))))).extracting(TariffRateSlab::isFixed).containsExactly(true, false);
        assertThatThrownBy(() -> TariffRateSlab.validateLane(List.of(fixed("0.001", "1", "200"), perKg("1", "99", "200", null))))
                .hasMessageContaining("overlap");
        assertThatThrownBy(() -> TariffRateSlab.validateLane(List.of(fixed("0.001", "1", "200"), perKg("2", "99", "200", null))))
                .hasMessageContaining("gap");
        assertThatThrownBy(() -> TariffRateSlab.validateLane(List.of(perKg("0.001", "99", "200", null))))
                .hasMessageContaining("lightest slab must be FIXED");
        assertThatThrownBy(() -> TariffRateSlab.validateLane(List.of()))
                .hasMessageContaining("at least one");
    }

    @Test
    @DisplayName("rate card dates: overlap and cover, open-ended included")
    void cardDates() {
        TariffRateCard y2025 = card(null, TotalRounding.NONE);
        y2025.setEffectiveTo(LocalDate.of(2026, 3, 31));
        TariffRateCard y2026 = card(null, TotalRounding.NONE);
        y2026.setEffectiveFrom(LocalDate.of(2026, 4, 1));
        TariffRateCard open = card(null, TotalRounding.NONE);

        assertThat(y2025.overlaps(y2026)).isFalse();
        assertThat(y2026.overlaps(y2025)).isFalse();
        assertThat(open.overlaps(y2026)).isTrue();
        assertThat(y2025.covers(LocalDate.of(2026, 3, 31))).isTrue();
        assertThat(y2025.covers(LocalDate.of(2026, 4, 1))).isFalse();
        assertThat(open.covers(LocalDate.of(2030, 1, 1))).isTrue();
    }
}
