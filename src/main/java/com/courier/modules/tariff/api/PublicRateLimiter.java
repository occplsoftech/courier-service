package com.courier.modules.tariff.api;

import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * At most {@value #PER_MINUTE} public rate checks a minute per caller address, in memory. The website's visitors share one
 * address here; its own web server limits each visitor.
 */
@Component
class PublicRateLimiter {

    static final int PER_MINUTE = 120;

    private final Map<String, Integer> calls = new HashMap<>();
    private long minute;

    synchronized void check(String caller) {
        long now = System.currentTimeMillis() / 60_000;
        if (now != minute) {
            calls.clear();
            minute = now;
        }
        if (calls.merge(caller == null ? "" : caller, 1, Integer::sum) > PER_MINUTE) {
            throw new BusinessRuleException(ErrorCode.RATE_LIMIT_EXCEEDED, "Too many rate checks. Please wait a minute and try again.");
        }
    }
}
