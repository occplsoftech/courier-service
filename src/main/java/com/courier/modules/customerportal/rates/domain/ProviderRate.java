package com.courier.modules.customerportal.rates.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One service a provider offered, with the provider's own charge lines. {@code charges} include the
 * provider's tax lines when the provider returns them; {@link #providerTotal()} is their sum.
 * Optional fields are null when the provider did not say — never guessed.
 */
public record ProviderRate(String serviceCode,
                           String serviceName,
                           String transportMode,
                           Integer etaMinDays,
                           Integer etaMaxDays,
                           BigDecimal chargeableWeightKg,
                           List<ChargeLine> charges,
                           String providerQuoteRef,
                           Instant validUntil,
                           String note) {

    /** Without a note. */
    public ProviderRate(String serviceCode, String serviceName, String transportMode, Integer etaMinDays, Integer etaMaxDays,
                        BigDecimal chargeableWeightKg, List<ChargeLine> charges, String providerQuoteRef, Instant validUntil) {
        this(serviceCode, serviceName, transportMode, etaMinDays, etaMaxDays, chargeableWeightKg, charges, providerQuoteRef, validUntil, null);
    }

    public BigDecimal providerTotal() {
        return charges.stream().map(ChargeLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
