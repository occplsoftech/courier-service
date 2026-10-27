package com.courier.modules.tariff.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** How a quote's grand total is rounded; the difference is shown as a round-off line. */
public enum TotalRounding {
    NONE,
    NEAREST_RUPEE,
    UP_RUPEE;

    public BigDecimal apply(BigDecimal amount) {
        return switch (this) {
            case NONE -> amount;
            case NEAREST_RUPEE -> amount.setScale(0, RoundingMode.HALF_UP).setScale(2);
            case UP_RUPEE -> amount.setScale(0, RoundingMode.CEILING).setScale(2);
        };
    }
}
