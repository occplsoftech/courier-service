package com.courier.modules.pricing.application.calculator;

import com.courier.modules.pricing.application.PricingContext;
import com.courier.modules.pricing.domain.ChargeType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * GST on {@link PricingContext#subtotalBeforeGst()} — Freight + Fuel + Handling + ODA +
 * Insurance — at the company's GST % (Settings → Finance), the one GST rate every booking uses; the matched
 * rate's own {@code gstPercentage} only when no company rate was resolved. Always on: GST is a statutory
 * charge, not a company toggle, which is why the module's Configuration list has no
 * enable/disable switch for it (unlike Fuel/ODA/Insurance/Discount).
 */
@Component
public class GSTCalculator implements ChargeCalculator {

    @Override
    public ChargeType type() {
        return ChargeType.GST;
    }

    @Override
    public int order() {
        return 60;
    }

    @Override
    public boolean isEnabled(PricingContext context) {
        return true;
    }

    @Override
    public BigDecimal calculate(PricingContext context) {
        BigDecimal subtotal = context.subtotalBeforeGst();
        BigDecimal gstPercentage = context.gstPercentage() != null
                ? context.gstPercentage() : context.matchedRate().getGstPercentage();
        return subtotal.multiply(gstPercentage)
                .divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
    }
}
