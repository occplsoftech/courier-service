package com.courier.modules.customerpricing;

import com.courier.modules.customerpricing.domain.ZoneLocation;
import com.courier.modules.customerpricing.domain.ZoneRate;
import com.courier.modules.customerpricing.domain.ZoneRateSlab;
import com.courier.shared.exception.BusinessRuleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerPricingTest {

    private static final UUID DISTRICT = UUID.randomUUID();
    private static final UUID CITY_A = UUID.randomUUID();
    private static final UUID CITY_B = UUID.randomUUID();

    private static ZoneLocation location(UUID district, UUID city) {
        return ZoneLocation.builder().zoneId(UUID.randomUUID()).customerId(UUID.randomUUID())
                .districtId(district).cityId(city).build();
    }

    @Test
    @DisplayName("a whole-district location overlaps any city of that district")
    void wholeDistrictOverlapsCity() {
        ZoneLocation whole = location(DISTRICT, null);
        assertThat(whole.overlaps(DISTRICT, CITY_A)).isTrue();
        assertThat(whole.overlaps(DISTRICT, null)).isTrue();
        assertThat(whole.overlaps(UUID.randomUUID(), null)).isFalse();
    }

    @Test
    @DisplayName("two different cities of one district do not overlap; the same city does")
    void citiesOverlapOnlyWhenEqual() {
        ZoneLocation cityA = location(DISTRICT, CITY_A);
        assertThat(cityA.overlaps(DISTRICT, CITY_B)).isFalse();
        assertThat(cityA.overlaps(DISTRICT, CITY_A)).isTrue();
        assertThat(cityA.overlaps(DISTRICT, null)).isTrue();
    }

    @Test
    @DisplayName("a slab covers above its minimum up to and including its maximum: 5 kg is in 0-5, 5.3 kg is in 5-10")
    void slabCoverage() {
        ZoneRateSlab first = ZoneRateSlab.builder().minWeight(BigDecimal.ZERO).maxWeight(new BigDecimal("5")).ratePerKg(BigDecimal.ONE).build();
        ZoneRateSlab second = ZoneRateSlab.builder().minWeight(new BigDecimal("5")).maxWeight(new BigDecimal("10")).ratePerKg(BigDecimal.ONE).build();
        ZoneRateSlab open = ZoneRateSlab.builder().minWeight(new BigDecimal("10")).ratePerKg(BigDecimal.ONE).build();

        assertThat(first.covers(new BigDecimal("0.5"))).isTrue();
        assertThat(first.covers(new BigDecimal("5"))).isTrue();
        assertThat(second.covers(new BigDecimal("5"))).isFalse();
        assertThat(first.covers(new BigDecimal("5.3"))).isFalse();
        assertThat(second.covers(new BigDecimal("5.3"))).isTrue();
        assertThat(second.covers(new BigDecimal("10"))).isTrue();
        assertThat(open.covers(new BigDecimal("10"))).isFalse();
        assertThat(open.covers(new BigDecimal("10.1"))).isTrue();
        assertThat(open.covers(new BigDecimal("100000"))).isTrue();
    }

    @Test
    @DisplayName("slab with max <= min or negative rate is rejected")
    void slabInvariants() {
        assertThatThrownBy(() -> ZoneRateSlab.builder().minWeight(BigDecimal.TEN).maxWeight(BigDecimal.TEN)
                .ratePerKg(BigDecimal.ONE).build().applyInvariants()).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> ZoneRateSlab.builder().minWeight(BigDecimal.ZERO).ratePerKg(new BigDecimal("-1"))
                .build().applyInvariants()).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("rate rejects percentages over 100 and an inverted effective window")
    void rateInvariants() {
        assertThatThrownBy(() -> ZoneRate.builder().fuelSurchargePercent(new BigDecimal("101")).build()
                .applyInvariants()).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> ZoneRate.builder().effectiveFrom(LocalDate.of(2026, 2, 1))
                .effectiveTo(LocalDate.of(2026, 1, 1)).build().applyInvariants())
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("effective window is inclusive and open-ended when a bound is null")
    void rateEffectiveWindow() {
        ZoneRate rate = ZoneRate.builder().effectiveFrom(LocalDate.of(2026, 1, 1))
                .effectiveTo(LocalDate.of(2026, 1, 31)).build();
        assertThat(rate.coversDate(LocalDate.of(2026, 1, 1))).isTrue();
        assertThat(rate.coversDate(LocalDate.of(2026, 1, 31))).isTrue();
        assertThat(rate.coversDate(LocalDate.of(2026, 2, 1))).isFalse();
        assertThat(ZoneRate.builder().build().coversDate(LocalDate.of(2099, 1, 1))).isTrue();
    }
}
