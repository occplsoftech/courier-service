package com.courier.modules.pricing.application;

import com.courier.modules.master.domain.Route;
import com.courier.modules.pricing.application.command.PricingCommand;
import com.courier.modules.pricing.domain.ChargeType;
import com.courier.modules.pricing.domain.PricingConfiguration;
import com.courier.modules.rate.domain.Rate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Carries one pricing calculation from validation through the charge-calculator chain.
 * Built once per request by {@code PricingEngineImpl}, mutated in place as each stage
 * resolves more of the picture — the same reason a builder is not used: several stages
 * (route validation, rate validation, weight calculation, the calculators themselves) each
 * contribute a piece nothing downstream could compute without.
 *
 * <p>Deliberately holds domain entities from other modules ({@link Route}, {@link Rate}) —
 * not a boundary violation: {@code RateServiceImpl} already reads fields off a
 * {@code Route} the master module's own service handed back, and this context does the
 * same, one layer up. Never leaves the {@code application} package as one of these types;
 * {@code api.PricingMapper} converts everything to plain values before it reaches a DTO.
 */
public final class PricingContext {

    private final PricingCommand command;
    private final PricingConfiguration configuration;
    private final Map<ChargeType, BigDecimal> charges = new EnumMap<>(ChargeType.class);

    private LocalDate bookingDate;
    private Route matchedRoute;
    private List<Rate> candidates;
    private Rate matchedRate;
    private BigDecimal actualWeight;
    private BigDecimal volumetricWeight;
    private BigDecimal chargeableWeight;
    /** The company's GST % (Settings → Finance); null falls back to the matched rate's own. */
    private BigDecimal gstPercentage;

    public PricingContext(PricingCommand command, PricingConfiguration configuration) {
        this.command = command;
        this.configuration = configuration;
    }

    public PricingCommand command() {
        return command;
    }

    public PricingConfiguration configuration() {
        return configuration;
    }

    public LocalDate bookingDate() {
        return bookingDate;
    }

    public void bookingDate(LocalDate bookingDate) {
        this.bookingDate = bookingDate;
    }

    public Route matchedRoute() {
        return matchedRoute;
    }

    public void matchedRoute(Route matchedRoute) {
        this.matchedRoute = matchedRoute;
    }

    public List<Rate> candidates() {
        return candidates;
    }

    public void candidates(List<Rate> candidates) {
        this.candidates = candidates;
    }

    public Rate matchedRate() {
        return matchedRate;
    }

    public void matchedRate(Rate matchedRate) {
        this.matchedRate = matchedRate;
    }

    public BigDecimal actualWeight() {
        return actualWeight;
    }

    public void actualWeight(BigDecimal actualWeight) {
        this.actualWeight = actualWeight;
    }

    public BigDecimal volumetricWeight() {
        return volumetricWeight;
    }

    public void volumetricWeight(BigDecimal volumetricWeight) {
        this.volumetricWeight = volumetricWeight;
    }

    public BigDecimal chargeableWeight() {
        return chargeableWeight;
    }

    public void chargeableWeight(BigDecimal chargeableWeight) {
        this.chargeableWeight = chargeableWeight;
    }

    // ---------------------------------------------------------------- charge accumulator

    public void charge(ChargeType type, BigDecimal amount) {
        charges.put(type, amount);
    }

    public BigDecimal charge(ChargeType type) {
        return charges.getOrDefault(type, BigDecimal.ZERO);
    }

    /** Freight + Fuel + Handling + ODA + Insurance + Applicable Charges — what GST is
     *  computed on. */
    public BigDecimal subtotalBeforeGst() {
        return charge(ChargeType.FREIGHT)
                .add(charge(ChargeType.FUEL))
                .add(charge(ChargeType.HANDLING))
                .add(charge(ChargeType.ODA))
                .add(charge(ChargeType.INSURANCE))
                .add(charge(ChargeType.APPLICABLE_CHARGES));
    }

    /** {@link #subtotalBeforeGst()} + GST — what a discount is computed against. */
    public BigDecimal totalBeforeDiscount() {
        return subtotalBeforeGst().add(charge(ChargeType.GST));
    }

    /** {@link #totalBeforeDiscount()} minus Discount — what Round Off is computed against. */
    public BigDecimal totalBeforeRoundOff() {
        return totalBeforeDiscount().subtract(charge(ChargeType.DISCOUNT));
    }

    /** {@link #totalBeforeRoundOff()} plus Round Off — the final net amount. */
    public BigDecimal netAmount() {
        return totalBeforeRoundOff().add(charge(ChargeType.ROUND_OFF));
    }

    public BigDecimal gstPercentage() {
        return gstPercentage;
    }

    public void gstPercentage(BigDecimal gstPercentage) {
        this.gstPercentage = gstPercentage;
    }
}
