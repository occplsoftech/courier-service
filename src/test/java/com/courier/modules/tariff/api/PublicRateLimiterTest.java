package com.courier.modules.tariff.api;

import com.courier.shared.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicRateLimiterTest {

    @Test
    void refusesACallerAboveTheLimitOnly() {
        PublicRateLimiter limiter = new PublicRateLimiter();
        // Twice the limit: refused even if the minute turns over halfway.
        assertThatThrownBy(() -> {
            for (int i = 0; i <= 2 * PublicRateLimiter.PER_MINUTE; i++) {
                limiter.check("a");
            }
        }).isInstanceOf(BusinessRuleException.class);
        limiter.check("b");
        assertThat(limiter).isNotNull();
    }
}
