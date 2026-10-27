package com.courier.modules.tariff.domain;

import com.courier.shared.exception.BusinessRuleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TariffZoneTest {

    @Test
    @DisplayName("FROM and TO zones never compete; BOTH competes with either")
    void usageOverlap() {
        assertThat(ZoneUsage.FROM.overlaps(ZoneUsage.TO)).isFalse();
        assertThat(ZoneUsage.TO.overlaps(ZoneUsage.FROM)).isFalse();
        assertThat(ZoneUsage.TO.overlaps(ZoneUsage.TO)).isTrue();
        assertThat(ZoneUsage.BOTH.overlaps(ZoneUsage.FROM)).isTrue();
        assertThat(ZoneUsage.BOTH.overlaps(ZoneUsage.TO)).isTrue();
    }

    @Test
    @DisplayName("pincode is the most specific scope, state the least")
    void scopeOrder() {
        assertThat(LocationScope.PINCODE.rank()).isLessThan(LocationScope.CITY.rank());
        assertThat(LocationScope.CITY.rank()).isLessThan(LocationScope.DISTRICT.rank());
        assertThat(LocationScope.DISTRICT.rank()).isLessThan(LocationScope.STATE.rank());
    }

    @Test
    @DisplayName("codes are trimmed, upper-cased and spaces become underscores")
    void codeNormalised() {
        TariffZone z = TariffZone.builder().code(" ex   pune ").name(" Ex-Pune ").zoneUsage(ZoneUsage.TO).build();
        z.applyInvariants();

        assertThat(z.getCode()).isEqualTo("EX_PUNE");
        assertThat(z.getName()).isEqualTo("Ex-Pune");
    }

    @Test
    @DisplayName("a code with other characters, or a negative order, is refused")
    void invalid() {
        assertThatThrownBy(() -> TariffZone.builder().code("ZONE#1").name("x").zoneUsage(ZoneUsage.TO).build().applyInvariants())
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> TariffZone.builder().code("ZONE").name("x").zoneUsage(ZoneUsage.TO).displayOrder(-1).build().applyInvariants())
                .isInstanceOf(BusinessRuleException.class);
    }
}
