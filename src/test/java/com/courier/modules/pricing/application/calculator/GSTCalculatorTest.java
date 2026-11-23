package com.courier.modules.pricing.application.calculator;

import com.courier.modules.pricing.application.PricingContext;
import com.courier.modules.pricing.application.PricingTestSupport;
import com.courier.modules.pricing.domain.ChargeType;
import com.courier.modules.rate.domain.Rate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class GSTCalculatorTest {

    private final GSTCalculator calculator = new GSTCalculator();

    @Test
    void gstOnTheFreightThroughInsuranceSubtotal() {
        Rate rate = PricingTestSupport.rate("RATE1", "0.000", "5.000");
        rate.setGstPercentage(new BigDecimal("18"));
        PricingContext context = PricingTestSupport.contextWithMatchedRate(rate,
                new BigDecimal("2.000"), PricingTestSupport.command(new BigDecimal("2.000")),
                PricingTestSupport.configuration());

        context.charge(ChargeType.FREIGHT, new BigDecimal("100.00"));
        context.charge(ChargeType.FUEL, new BigDecimal("10.00"));
        context.charge(ChargeType.HANDLING, new BigDecimal("5.00"));
        context.charge(ChargeType.ODA, new BigDecimal("15.00"));
        context.charge(ChargeType.INSURANCE, BigDecimal.ZERO);

        // (100 + 10 + 5 + 15) * 18% = 23.40
        assertThat(calculator.calculate(context)).isEqualByComparingTo("23.40");
        assertThat(calculator.isEnabled(context)).isTrue();
    }

    @Test
    void theCompanysGstWinsOverTheRatesOwn() {
        Rate rate = PricingTestSupport.rate("RATE1", "0.000", "5.000");
        rate.setGstPercentage(new BigDecimal("18"));
        PricingContext context = PricingTestSupport.contextWithMatchedRate(rate,
                new BigDecimal("2.000"), PricingTestSupport.command(new BigDecimal("2.000")),
                PricingTestSupport.configuration());
        context.charge(ChargeType.FREIGHT, new BigDecimal("100.00"));
        context.gstPercentage(BigDecimal.ZERO);

        assertThat(calculator.calculate(context)).isEqualByComparingTo("0.00");
    }
}
