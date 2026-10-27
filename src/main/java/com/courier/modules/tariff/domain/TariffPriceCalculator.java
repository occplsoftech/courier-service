package com.courier.modules.tariff.domain;

import com.courier.shared.exception.BusinessRuleException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Prices one shipment against a rate card's slabs and charge rules. Pure: no repositories, no clock, so the arithmetic
 * is tested on its own.
 *
 * <ol>
 *   <li>Chargeable weight: the larger of actual and volumetric (volumetric only when the card has a divisor and the
 *       package is not a document), rounded up to the gram.</li>
 *   <li>Freight: the first slab whose upper weight reaches the chargeable weight. FIXED is its rate; PER_EXTRA_KG is the
 *       rate of the fixed slab before it plus {@code rate} for each step (1 kg) or part of a step above that slab.</li>
 *   <li>Charges: for each charge code, the most specific active rule that matches the lane and whose condition holds
 *       (COD, ODA); then min / max.</li>
 *   <li>GST on freight plus the taxable charges: CGST + SGST when both ends are in the same state, IGST otherwise.</li>
 *   <li>Non-taxable charges, then the card's rounding.</li>
 * </ol>
 */
public final class TariffPriceCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal GRAM = new BigDecimal("0.001");

    private TariffPriceCalculator() {
    }

    /** What is being priced. Dimensions are per piece, in cm, and may be null. */
    public record Shipment(BigDecimal actualWeightKg, BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm,
                           int pieces, BigDecimal declaredValue, boolean cod, boolean oda, boolean document,
                           UUID toZoneId, UUID serviceTypeId, UUID packageTypeId, boolean sameState) {
    }

    public record Freight(BigDecimal amount, TariffRateSlab slab, TariffRateSlab baseSlab, int extraSteps, String basis) {
    }

    public record ChargeLine(String code, String name, BigDecimal amount, boolean taxable, String basis, UUID ruleId) {
    }

    public record TaxLine(String code, BigDecimal percent, BigDecimal amount) {
    }

    public record Price(BigDecimal actualWeightKg, BigDecimal volumetricWeightKg, BigDecimal chargeableWeightKg,
                        Freight freight, List<ChargeLine> charges, BigDecimal taxableAmount, List<TaxLine> taxes,
                        BigDecimal taxTotal, BigDecimal nonTaxableAmount, BigDecimal roundOff, BigDecimal total) {
    }

    public static Price price(TariffRateCard card, List<TariffRateSlab> laneSlabs, List<TariffChargeRule> rules, Shipment s) {
        if (s.actualWeightKg() == null || s.actualWeightKg().signum() <= 0) {
            throw new BusinessRuleException("Enter the weight in kg, above 0.");
        }
        int pieces = Math.max(1, s.pieces());
        BigDecimal actual = s.actualWeightKg().setScale(3, RoundingMode.CEILING);
        BigDecimal volumetric = volumetric(card, s, pieces);
        BigDecimal chargeable = volumetric != null && volumetric.compareTo(actual) > 0 ? volumetric : actual;

        Freight freight = freight(laneSlabs, chargeable);
        List<ChargeLine> charges = charges(rules, s, pieces, chargeable, freight.amount());

        BigDecimal taxable = freight.amount();
        BigDecimal nonTaxable = BigDecimal.ZERO.setScale(2);
        for (ChargeLine c : charges) {
            if (c.taxable()) {
                taxable = taxable.add(c.amount());
            } else {
                nonTaxable = nonTaxable.add(c.amount());
            }
        }
        List<TaxLine> taxes = taxes(card.getGstPercent(), taxable, s.sameState());
        BigDecimal taxTotal = taxes.stream().map(TaxLine::amount).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        BigDecimal beforeRounding = taxable.add(taxTotal).add(nonTaxable);
        BigDecimal total = card.getTotalRounding().apply(beforeRounding);
        return new Price(actual, volumetric, chargeable, freight, charges, taxable, taxes, taxTotal, nonTaxable,
                total.subtract(beforeRounding), total);
    }

    static BigDecimal volumetric(TariffRateCard card, Shipment s, int pieces) {
        if (card.getVolumetricDivisor() == null || s.document()
                || s.lengthCm() == null || s.widthCm() == null || s.heightCm() == null) {
            return null;
        }
        if (s.lengthCm().signum() <= 0 || s.widthCm().signum() <= 0 || s.heightCm().signum() <= 0) {
            throw new BusinessRuleException("Length, width and height must be above 0 cm, or left empty.");
        }
        return s.lengthCm().multiply(s.widthCm()).multiply(s.heightCm()).multiply(BigDecimal.valueOf(pieces))
                .divide(BigDecimal.valueOf(card.getVolumetricDivisor()), 3, RoundingMode.CEILING);
    }

    /** Freight for {@code weight} on one lane. {@code laneSlabs} need not be sorted. */
    public static Freight freight(List<TariffRateSlab> laneSlabs, BigDecimal weight) {
        if (laneSlabs == null || laneSlabs.isEmpty()) {
            throw new BusinessRuleException("This rate card has no rate for that zone, service and package type.");
        }
        List<TariffRateSlab> sorted = laneSlabs.stream().sorted(Comparator.comparing(TariffRateSlab::getWeightFromKg)).toList();
        TariffRateSlab slab = sorted.stream().filter(x -> x.getWeightToKg().compareTo(weight) >= 0).findFirst()
                .orElseThrow(() -> new BusinessRuleException("%s kg is above this rate's limit of %s kg."
                        .formatted(plain(weight), plain(sorted.get(sorted.size() - 1).getWeightToKg()))));
        if (slab.isFixed()) {
            return new Freight(money(slab.getRate()), slab, null, 0, "Fixed rate for " + slab.band());
        }
        TariffRateSlab base = sorted.stream()
                .filter(x -> x.isFixed() && x.getWeightToKg().compareTo(slab.getWeightFromKg()) < 0)
                .reduce((a, b) -> b)
                .orElseThrow(() -> new BusinessRuleException("Slab " + slab.band() + " adds per kg but has no fixed slab before it."));
        BigDecimal extraWeight = weight.subtract(base.getWeightToKg());
        int steps = extraWeight.divide(slab.getStepKg(), 0, RoundingMode.CEILING).intValueExact();
        BigDecimal amount = money(base.getRate().add(slab.getRate().multiply(BigDecimal.valueOf(steps))));
        String unit = slab.getStepKg().compareTo(BigDecimal.ONE) == 0 ? "kg" : plain(slab.getStepKg()) + " kg";
        return new Freight(amount, slab, base, steps, "%s up to %s kg + %d × %s per extra %s"
                .formatted(plain(base.getRate()), plain(base.getWeightToKg()), steps, plain(slab.getRate()), unit));
    }

    static List<ChargeLine> charges(List<TariffChargeRule> rules, Shipment s, int pieces, BigDecimal chargeable,
                                    BigDecimal freight) {
        Map<String, TariffChargeRule> chosen = new LinkedHashMap<>();
        rules.stream()
                .filter(TariffChargeRule::isActive)
                .filter(r -> r.matches(s.toZoneId(), s.serviceTypeId(), s.packageTypeId()))
                .filter(r -> switch (r.getAppliesWhen()) {
                    case ALWAYS -> true;
                    case COD_ONLY -> s.cod();
                    case ODA_ONLY -> s.oda();
                })
                .forEach(r -> chosen.merge(r.getChargeCode(), r, TariffPriceCalculator::moreSpecific));
        List<ChargeLine> lines = new ArrayList<>();
        chosen.values().stream()
                .sorted(Comparator.comparingInt(TariffChargeRule::getDisplayOrder).thenComparing(TariffChargeRule::getChargeCode))
                .forEach(r -> {
                    BigDecimal amount = amount(r, s, pieces, chargeable, freight);
                    if (amount.signum() > 0) {
                        lines.add(new ChargeLine(r.getChargeCode(), r.getChargeName(), amount, r.isTaxable(),
                                basis(r, s, pieces, chargeable), r.getId()));
                    }
                });
        return lines;
    }

    private static TariffChargeRule moreSpecific(TariffChargeRule a, TariffChargeRule b) {
        if (a.specificity() != b.specificity()) {
            return a.specificity() > b.specificity() ? a : b;
        }
        // Same filters: a rule for a condition (COD, ODA) beats the catch-all.
        return a.getAppliesWhen() == ChargeAppliesWhen.ALWAYS ? b : a;
    }

    private static BigDecimal amount(TariffChargeRule r, Shipment s, int pieces, BigDecimal chargeable, BigDecimal freight) {
        BigDecimal v = r.getChargeValue();
        BigDecimal raw = switch (r.getCalcMethod()) {
            case FLAT -> v;
            case PERCENT_OF_FREIGHT -> freight.multiply(v).divide(HUNDRED);
            case PER_KG -> chargeable.setScale(0, RoundingMode.CEILING).multiply(v);
            case PER_PIECE -> BigDecimal.valueOf(pieces).multiply(v);
            case PERCENT_OF_VALUE -> (s.declaredValue() == null ? BigDecimal.ZERO : s.declaredValue()).multiply(v).divide(HUNDRED);
        };
        if (r.getMinAmount() != null && raw.compareTo(r.getMinAmount()) < 0) {
            raw = r.getMinAmount();
        }
        if (r.getMaxAmount() != null && raw.compareTo(r.getMaxAmount()) > 0) {
            raw = r.getMaxAmount();
        }
        return money(raw);
    }

    private static String basis(TariffChargeRule r, Shipment s, int pieces, BigDecimal chargeable) {
        String v = plain(r.getChargeValue());
        return switch (r.getCalcMethod()) {
            case FLAT -> "Flat " + v;
            case PERCENT_OF_FREIGHT -> v + "% of freight";
            case PER_KG -> v + " × " + chargeable.setScale(0, RoundingMode.CEILING) + " kg";
            case PER_PIECE -> v + " × " + pieces + " piece(s)";
            case PERCENT_OF_VALUE -> v + "% of declared value " + plain(s.declaredValue() == null ? BigDecimal.ZERO : s.declaredValue());
        } + (r.getMinAmount() != null ? ", min " + plain(r.getMinAmount()) : "")
                + (r.getMaxAmount() != null ? ", max " + plain(r.getMaxAmount()) : "");
    }

    static List<TaxLine> taxes(BigDecimal gstPercent, BigDecimal taxable, boolean sameState) {
        if (gstPercent == null || gstPercent.signum() == 0) {
            return List.of();
        }
        if (sameState) {
            BigDecimal half = gstPercent.divide(new BigDecimal("2"));
            BigDecimal each = money(taxable.multiply(half).divide(HUNDRED));
            return List.of(new TaxLine("CGST", half, each), new TaxLine("SGST", half, each));
        }
        return List.of(new TaxLine("IGST", gstPercent, money(taxable.multiply(gstPercent).divide(HUNDRED))));
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
