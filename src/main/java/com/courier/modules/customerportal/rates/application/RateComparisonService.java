package com.courier.modules.customerportal.rates.application;

import com.courier.modules.customerportal.application.ActingCustomer;
import com.courier.modules.customerportal.application.CurrentCustomerResolver;
import com.courier.modules.customerportal.rates.application.MarkupCalculator.Markup;
import com.courier.modules.customerportal.rates.application.MarkupCalculator.Priced;
import com.courier.modules.customerportal.rates.domain.ChargeLine;
import com.courier.modules.customerportal.rates.domain.CourierProviderAdapter;
import com.courier.modules.customerportal.rates.domain.ProviderRate;
import com.courier.modules.customerportal.rates.domain.InternationalRateProvider;
import com.courier.modules.customerportal.rates.domain.ProviderResult;
import com.courier.modules.customerportal.rates.domain.RateQuote;
import com.courier.modules.customerportal.rates.domain.RateQuoteOption;
import com.courier.modules.customerportal.rates.domain.RateRequest;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Compares courier rates for the signed-in customer: asks every provider (OCCPL network on this thread,
 * external ones in parallel with a timeout), prices each service (markup for external providers, tax),
 * and stores the whole comparison as an immutable quote. Nothing the browser sends is a price.
 */
@Slf4j
@Service
public class RateComparisonService {

    static final Pattern PINCODE = Pattern.compile("^[1-9][0-9]{5}$");
    private static final BigDecimal INCH_TO_CM = new BigDecimal("2.54");
    private static final BigDecimal MAX_WEIGHT_KG = new BigDecimal("1000");
    private static final BigDecimal MAX_DIMENSION_CM = new BigDecimal("1270"); // 500 inches
    private static final BigDecimal MAX_VALUE = new BigDecimal("10000000");

    private final CurrentCustomerResolver resolver;
    private final List<CourierProviderAdapter> adapters;
    private final MarkupRuleService markups;
    private final RateQuoteStore store;
    private final CompareRateLimiter limiter;
    private final CustomerPortalProperties properties;
    private final ExecutorService executor;
    private final CustomerCourierAccessService access;

    public RateComparisonService(CurrentCustomerResolver resolver, List<CourierProviderAdapter> adapters,
                                 MarkupRuleService markups, RateQuoteStore store, CompareRateLimiter limiter,
                                 CustomerPortalProperties properties,
                                 @Qualifier("courierRateExecutor") ExecutorService executor,
                                 CustomerCourierAccessService access) {
        this.resolver = resolver;
        this.adapters = adapters.stream().sorted(Comparator.comparing(a -> a.external() ? 1 : 0)).toList();
        this.markups = markups;
        this.store = store;
        this.limiter = limiter;
        this.properties = properties;
        this.executor = executor;
        this.access = access;
    }

    public QuoteView compare(CompareCommand c) {
        return compareInternal(c, true).view();
    }

    /** One row of a bulk upload: the quote, and the option to book. The per-minute comparison cap does not apply (the batch size is capped instead). */
    public BulkQuote compareForBulk(CompareCommand c) {
        Compared r = compareInternal(c, false);
        RateQuoteOption chosen = chooseForBulk(r.options());
        return new BulkQuote(r.view(), chosen == null ? null : chosen.getId(),
                chosen == null ? null : (isBusinessRate(chosen) ? "BUSINESS_RATE" : "CHEAPEST"));
    }

    /**
     * The customer's own business rate when OCCPL Network priced one (the cheapest of them if there is one per service type);
     * otherwise the cheapest available option. Null when nothing is available.
     */
    static RateQuoteOption chooseForBulk(List<RateQuoteOption> options) {
        List<RateQuoteOption> ok = options.stream().filter(RateQuoteOption::isAvailable).toList();
        Comparator<RateQuoteOption> byTotal = Comparator.comparing(RateQuoteOption::getTotalAmount);
        return ok.stream().filter(RateComparisonService::isBusinessRate).min(byTotal)
                .orElseGet(() -> ok.stream().min(byTotal).orElse(null));
    }

    private static boolean isBusinessRate(RateQuoteOption o) {
        return "OCCPL_NETWORK".equals(o.getProviderCode()) && "BUSINESS_RATE".equals(o.getProviderQuoteRef());
    }

    private record Compared(QuoteView view, List<RateQuoteOption> options) {
    }

    public record BulkQuote(QuoteView view, UUID optionId, String basis) {
    }

    private Compared compareInternal(CompareCommand c, boolean limited) {
        ActingCustomer acting = resolver.require();
        requireDifferentAddresses(c);
        if (limited) {
            limiter.check(acting.userId());
        }
        RateRequest request = toRequest(acting, c);
        BigDecimal volumetric = request.lengthCm().multiply(request.breadthCm()).multiply(request.heightCm())
                .multiply(BigDecimal.valueOf(request.packageCount()))
                .divide(BigDecimal.valueOf(properties.getVolumetricDivisor()), 3, RoundingMode.HALF_UP);

        List<ProviderResult> results = askProviders(request);
        Instant now = Instant.now();
        Instant expires = now.plus(properties.getQuoteValidity());

        RateQuote quote = RateQuote.builder()
                .customerId(acting.customerId())
                .requestedBy(acting.userId())
                .pickupPincode(request.pickupPincode())
                .deliveryPincode(request.deliveryPincode())
                .request(snapshot(c))
                .actualWeightKg(request.actualWeightKg())
                .volumetricWeightKg(volumetric)
                .packageCount(request.packageCount())
                .declaredValue(request.declaredValue())
                .paymentMode(request.paymentMode().name())
                .status("OPEN")
                .expiresAt(expires)
                .build();

        List<RateQuoteOption> options = priceOptions(acting, results, now, expires);
        RateQuoteStore.Saved saved = store.save(quote, options);
        log.info("Rate comparison {} for customer {}: {} option(s), {} provider(s) unavailable",
                saved.quote().getId(), acting.customerId(),
                saved.options().stream().filter(RateQuoteOption::isAvailable).count(),
                saved.options().stream().filter(o -> !o.isAvailable()).count());
        return new Compared(view(saved.quote(), saved.options(), now), saved.options());
    }

    /** Prices every provider's answer (markup and tax included); the unavailable ones are kept with their reason. */
    private List<RateQuoteOption> priceOptions(ActingCustomer acting, List<ProviderResult> results, Instant now, Instant expires) {
        List<RateQuoteOption> options = new ArrayList<>();
        for (ProviderResult result : results) {
            if (!result.available()) {
                options.add(RateQuoteOption.builder().providerCode(result.providerCode()).availability("UNAVAILABLE")
                        .unavailableReason(truncate(result.reason(), 255)).currency("INR").build());
                continue;
            }
            CourierProviderAdapter adapter = adapter(result.providerCode());
            Markup markup = adapter.external()
                    ? markups.resolve(acting.companyId(), adapter.code(), acting.customerId(), now) : null;
            BigDecimal untaxedRate = adapter.external() ? null : properties.getOccplNetworkGstPercent();
            for (ProviderRate rate : result.rates()) {
                Priced p = MarkupCalculator.price(rate.charges(), markup, untaxedRate);
                options.add(RateQuoteOption.builder()
                        .providerCode(result.providerCode())
                        .serviceCode(truncate(rate.serviceCode(), 80))
                        .serviceName(truncate(rate.serviceName(), 150))
                        .transportMode(rate.transportMode())
                        .availability("AVAILABLE")
                        .etaMinDays(rate.etaMinDays())
                        .etaMaxDays(rate.etaMaxDays())
                        .chargeableWeightKg(rate.chargeableWeightKg())
                        .baseFreight(p.baseFreight())
                        .otherCharges(p.otherCharges())
                        .markupRuleId(markup == null ? null : markup.ruleId())
                        .markupPercent(markup == null || !markup.enabled() ? null : markup.percent())
                        .markupBase(p.markupBase())
                        .markupAmount(p.markupAmount())
                        .taxAmount(p.taxAmount())
                        .totalAmount(p.total())
                        .currency("INR")
                        .breakdown(p.lines().stream().map(RateComparisonService::line).toList())
                        .providerQuoteRef(truncate(rate.providerQuoteRef(), 120))
                        .validUntil(rate.validUntil() == null || rate.validUntil().isAfter(expires) ? expires : rate.validUntil())
                        .build());
            }
        }
        return options;
    }

    /**
     * A price check from pincodes and a package only — nothing is stored and the result cannot be booked as it stands (no
     * addresses yet), so its option ids are not valid for booking. Without dimensions the chargeable weight is the actual weight.
     */
    public QuoteView estimate(EstimateCommand e) {
        ActingCustomer acting = resolver.require();
        limiter.check(acting.userId());
        boolean dims = e.length() != null && e.breadth() != null && e.height() != null;
        PackageInput pkg = new PackageInput(e.actualWeightKg(), dims ? e.length() : BigDecimal.ONE,
                dims ? e.breadth() : BigDecimal.ONE, dims ? e.height() : BigDecimal.ONE, dims ? e.dimensionUnit() : "CM",
                e.packageCount(), "estimate", e.declaredValue(), e.paymentMode());
        RateRequest request = toRequest(acting, new CompareCommand(new PartyInput(null, null, null, e.pickupPincode(), null, null),
                new PartyInput(null, null, null, e.deliveryPincode(), null, null), pkg));
        BigDecimal volumetric = request.lengthCm().multiply(request.breadthCm()).multiply(request.heightCm())
                .multiply(BigDecimal.valueOf(request.packageCount()))
                .divide(BigDecimal.valueOf(properties.getVolumetricDivisor()), 3, RoundingMode.HALF_UP);
        List<ProviderResult> results = askProviders(request);
        Instant now = Instant.now();
        Instant expires = now.plus(properties.getQuoteValidity());
        List<RateQuoteOption> options = priceOptions(acting, results, now, expires);
        RateQuote quote = RateQuote.builder().pickupPincode(request.pickupPincode()).deliveryPincode(request.deliveryPincode())
                .actualWeightKg(request.actualWeightKg()).volumetricWeightKg(dims ? volumetric : request.actualWeightKg())
                .packageCount(request.packageCount()).declaredValue(request.declaredValue())
                .paymentMode(request.paymentMode().name()).status("OPEN").expiresAt(expires).build();
        return view(quote, options, now);
    }

    /**
     * International price check (sKart): from an Indian pincode to a country and postcode. Same markup and tax handling as the
     * domestic estimate; nothing is stored. {@code deliveryPincode} in the answer is the destination postcode.
     */
    public QuoteView estimateInternational(InternationalEstimateCommand e) {
        ActingCustomer acting = resolver.require();
        limiter.check(acting.userId());
        if (e.weightKg() == null || e.weightKg().signum() <= 0 || e.weightKg().compareTo(BigDecimal.valueOf(500)) > 0) {
            throw new BusinessRuleException("Weight must be more than 0 and at most 500 kg.");
        }
        if (e.originPincode() == null || !e.originPincode().matches("\\d{6}")) {
            throw new BusinessRuleException("Enter the 6-digit pickup pincode.");
        }
        InternationalRateProvider.Request request = new InternationalRateProvider.Request(acting.companyId(), e.originPincode(),
                e.destinationCountry().trim(), e.destinationPostcode() == null ? "" : e.destinationPostcode().trim(), e.kind(),
                e.weightKg(), e.pieces() == null ? 1 : Math.max(1, e.pieces()), e.maxLengthCm());
        List<ProviderResult> results = new ArrayList<>();
        Set<String> allowed = access.allowedCodes(acting.companyId(), acting.customerId());
        for (CourierProviderAdapter a : adapters) {
            if (a instanceof InternationalRateProvider provider && allowed.contains(a.code())) {
                if (!a.configured(acting.companyId())) {
                    results.add(ProviderResult.unavailable(a.code(), a.displayName(), a.displayName() + " is not connected yet."));
                    continue;
                }
                try {
                    results.add(provider.internationalRates(request));
                } catch (RuntimeException ex) {
                    log.warn("International rates from {} failed unexpectedly: {}", a.code(), ex.getClass().getSimpleName());
                    results.add(ProviderResult.unavailable(a.code(), a.displayName(), a.displayName() + " could not be asked."));
                }
            }
        }
        Instant now = Instant.now();
        Instant expires = now.plus(properties.getQuoteValidity());
        List<RateQuoteOption> options = priceOptions(acting, results, now, expires);
        RateQuote quote = RateQuote.builder().pickupPincode(e.originPincode()).deliveryPincode(request.destinationPostcode())
                .actualWeightKg(e.weightKg()).volumetricWeightKg(e.weightKg()).packageCount(request.pieces())
                .declaredValue(BigDecimal.ZERO).paymentMode("PREPAID").status("OPEN").expiresAt(expires).build();
        return view(quote, options, now);
    }

    /** Destination countries of the connected international provider(s), A–Z. */
    public List<InternationalRateProvider.Country> internationalCountries() {
        ActingCustomer acting = resolver.require();
        Set<String> allowed = access.allowedCodes(acting.companyId(), acting.customerId());
        return adapters.stream().filter(a -> a instanceof InternationalRateProvider && allowed.contains(a.code()) && a.configured(acting.companyId()))
                .findFirst().map(a -> ((InternationalRateProvider) a).countries(acting.companyId())).orElse(List.of());
    }

    public QuoteView get(UUID quoteId) {
        ActingCustomer acting = resolver.require();
        RateQuoteStore.Saved saved = store.load(quoteId, acting)
                .orElseThrow(() -> new ResourceNotFoundException("Quote", quoteId));
        return view(saved.quote(), saved.options(), Instant.now());
    }

    // ------------------------------------------------------------------ internals

    private List<ProviderResult> askProviders(RateRequest request) {
        List<ProviderResult> results = new ArrayList<>();
        Map<CourierProviderAdapter, CompletableFuture<ProviderResult>> pending = new LinkedHashMap<>();
        Set<String> allowed = access.allowedCodes(request.companyId(), request.customerId());
        for (CourierProviderAdapter a : adapters) {
            // a courier this customer was not given is not shown at all, not even as "not available"
            if (!a.domestic() || !allowed.contains(a.code())) {
                continue;
            }
            if (!a.configured(request.companyId())) {
                results.add(ProviderResult.unavailable(a.code(), a.displayName(), a.displayName() + " is not connected yet."));
            } else if (a.external() && request.topay()) {
                // only OCCPL's own network can collect the freight from the receiver
                results.add(ProviderResult.unavailable(a.code(), a.displayName(), "To Pay is available on OCCPL Network only."));
            } else if (a.external()) {
                pending.put(a, CompletableFuture.supplyAsync(() -> a.getRates(request), executor));
            }
        }
        // OCCPL's own network needs the bound company and principal, so it runs here while the others work.
        adapters.stream().filter(a -> !a.external() && allowed.contains(a.code()) && a.configured(request.companyId())).forEach(a -> results.add(0, safe(a, request)));
        long timeoutMs = properties.getProviderTimeout().toMillis() + 2000;
        for (Map.Entry<CourierProviderAdapter, CompletableFuture<ProviderResult>> e : pending.entrySet()) {
            CourierProviderAdapter a = e.getKey();
            try {
                results.add(e.getValue().get(timeoutMs, TimeUnit.MILLISECONDS));
            } catch (java.util.concurrent.TimeoutException ex) {
                e.getValue().cancel(true);
                results.add(ProviderResult.unavailable(a.code(), a.displayName(), a.displayName() + " took too long to answer."));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                results.add(ProviderResult.unavailable(a.code(), a.displayName(), a.displayName() + " could not be asked."));
            } catch (java.util.concurrent.ExecutionException ex) {
                log.warn("Provider {} failed: {}", a.code(), ex.getCause() == null ? ex : ex.getCause().getClass().getSimpleName());
                results.add(ProviderResult.unavailable(a.code(), a.displayName(), a.displayName() + " could not be reached."));
            }
        }
        return results;
    }

    private static ProviderResult safe(CourierProviderAdapter a, RateRequest request) {
        try {
            return a.getRates(request);
        } catch (RuntimeException e) {
            log.warn("Provider {} failed: {}", a.code(), e.getClass().getSimpleName());
            return ProviderResult.unavailable(a.code(), a.displayName(), a.displayName() + " could not price this shipment.");
        }
    }

    private CourierProviderAdapter adapter(String code) {
        return adapters.stream().filter(a -> a.code().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown provider " + code));
    }

    private String providerName(String code) {
        return adapters.stream().filter(a -> a.code().equals(code)).map(CourierProviderAdapter::displayName)
                .findFirst().orElse(code);
    }

    static RateRequest toRequest(ActingCustomer acting, CompareCommand c) {
        if (c == null || c.pickup() == null || c.delivery() == null || c.pkg() == null) {
            throw new BusinessRuleException("Pickup, delivery and package details are required.");
        }
        String from = pincode(c.pickup().pincode(), "Pickup");
        String to = pincode(c.delivery().pincode(), "Delivery");
        PackageInput p = c.pkg();
        BigDecimal weight = positive(p.actualWeightKg(), "Actual weight", MAX_WEIGHT_KG, 3);
        boolean inches = "IN".equalsIgnoreCase(p.dimensionUnit());
        BigDecimal l = cm(positive(p.length(), "Length", MAX_DIMENSION_CM, 1), inches);
        BigDecimal b = cm(positive(p.breadth(), "Breadth", MAX_DIMENSION_CM, 1), inches);
        BigDecimal h = cm(positive(p.height(), "Height", MAX_DIMENSION_CM, 1), inches);
        if (p.packageCount() == null || p.packageCount() < 1 || p.packageCount() > 999) {
            throw new BusinessRuleException("Number of packages must be 1 to 999.");
        }
        BigDecimal value = positive(p.declaredValue(), "Shipment value", MAX_VALUE, 2);
        RateRequest.PaymentMode mode;
        try {
            mode = RateRequest.PaymentMode.valueOf(p.paymentMode() == null ? "PREPAID" : p.paymentMode().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("Payment mode must be PREPAID, COD or TOPAY.");
        }
        return new RateRequest(acting.companyId(), acting.customerId(), from, to, weight, l, b, h,
                p.packageCount(), value, mode);
    }

    private static String pincode(String raw, String which) {
        String v = raw == null ? "" : raw.trim();
        if (!PINCODE.matcher(v).matches()) {
            throw new BusinessRuleException(which + " pincode must be 6 digits and not start with 0.");
        }
        return v;
    }

    private static BigDecimal positive(BigDecimal v, String label, BigDecimal max, int scale) {
        if (v == null || v.signum() <= 0) {
            throw new BusinessRuleException(label + " must be greater than zero.");
        }
        if (v.compareTo(max) > 0) {
            throw new BusinessRuleException(label + " is too large.");
        }
        if (v.stripTrailingZeros().scale() > scale) {
            throw new BusinessRuleException(label + " may have at most " + scale + " decimal place(s).");
        }
        return v;
    }

    private static BigDecimal cm(BigDecimal v, boolean inches) {
        return inches ? v.multiply(INCH_TO_CM).setScale(1, RoundingMode.HALF_UP) : v;
    }

    private static Map<String, Object> snapshot(CompareCommand c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pickup", party(c.pickup()));
        m.put("delivery", party(c.delivery()));
        PackageInput p = c.pkg();
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("actualWeightKg", p.actualWeightKg());
        pkg.put("length", p.length());
        pkg.put("breadth", p.breadth());
        pkg.put("height", p.height());
        pkg.put("dimensionUnit", "IN".equalsIgnoreCase(p.dimensionUnit()) ? "IN" : "CM");
        pkg.put("packageCount", p.packageCount());
        pkg.put("contents", truncate(p.contents(), 200));
        pkg.put("declaredValue", p.declaredValue());
        pkg.put("paymentMode", p.paymentMode());
        m.put("package", pkg);
        return m;
    }

    private static Map<String, Object> party(PartyInput p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", truncate(p.name(), 100));
        m.put("phone", truncate(p.phone(), 20));
        m.put("address", truncate(p.address(), 300));
        m.put("pincode", p.pincode() == null ? null : p.pincode().trim());
        m.put("city", truncate(p.city(), 80));
        m.put("state", truncate(p.state(), 80));
        return m;
    }

    private static Map<String, Object> line(ChargeLine l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", l.code());
        m.put("label", l.label());
        m.put("component", l.component().name());
        m.put("amount", l.amount());
        return m;
    }

    private QuoteView view(RateQuote q, List<RateQuoteOption> options, Instant now) {
        boolean expired = !q.getExpiresAt().isAfter(now);
        List<OptionView> available = options.stream().filter(RateQuoteOption::isAvailable)
                .map(o -> new OptionView(o.getId(), o.getProviderCode(), providerName(o.getProviderCode()), o.getServiceName(),
                        o.getTransportMode(), o.getEtaMinDays(), o.getEtaMaxDays(), o.getChargeableWeightKg(),
                        o.getBaseFreight(), o.getOtherCharges(), o.getMarkupPercent(), o.getMarkupAmount(),
                        o.getTaxAmount(), o.getTotalAmount(), o.getCurrency(), lines(o), o.getValidUntil()))
                .sorted(Comparator.comparing(OptionView::totalAmount))
                .toList();
        List<UnavailableView> unavailable = options.stream().filter(o -> !o.isAvailable())
                .map(o -> new UnavailableView(o.getProviderCode(), providerName(o.getProviderCode()), o.getUnavailableReason()))
                .toList();
        return new QuoteView(q.getId(), expired ? "EXPIRED" : q.getStatus(), q.getExpiresAt(), q.getPickupPincode(),
                q.getDeliveryPincode(), q.getActualWeightKg(), q.getVolumetricWeightKg(), q.getPackageCount(),
                q.getDeclaredValue(), q.getPaymentMode(), available, unavailable);
    }

    static List<BreakdownLine> lines(RateQuoteOption o) {
        if (o.getBreakdown() == null) return List.of();
        return o.getBreakdown().stream().map(m -> new BreakdownLine(String.valueOf(m.get("label")),
                String.valueOf(m.get("component")), new BigDecimal(String.valueOf(m.get("amount"))))).toList();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    // ------------------------------------------------------------------ shapes

    /** Pickup and delivery must be two different places: same pincode and the same address text (ignoring case, spacing and punctuation). */
    static void requireDifferentAddresses(CompareCommand c) {
        if (c.pickup() == null || c.delivery() == null) {
            return;
        }
        if (sameText(c.pickup().pincode(), c.delivery().pincode()) && sameText(c.pickup().address(), c.delivery().address())) {
            throw new BusinessRuleException("Pickup and delivery address cannot be the same. Enter the address the parcel is going to.");
        }
    }

    private static boolean sameText(String a, String b) {
        return normalise(a).equals(normalise(b));
    }

    private static String normalise(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    public record PartyInput(String name, String phone, String address, String pincode, String city, String state) {
    }

    public record PackageInput(BigDecimal actualWeightKg, BigDecimal length, BigDecimal breadth, BigDecimal height,
                               String dimensionUnit, Integer packageCount, String contents, BigDecimal declaredValue,
                               String paymentMode) {
    }

    public record EstimateCommand(String pickupPincode, String deliveryPincode, Integer packageCount, BigDecimal actualWeightKg,
                                  BigDecimal declaredValue, BigDecimal length, BigDecimal breadth, BigDecimal height,
                                  String dimensionUnit, String paymentMode) {
    }

    public record InternationalEstimateCommand(String originPincode, String destinationCountry, String destinationPostcode,
                                               InternationalRateProvider.Kind kind, BigDecimal weightKg, Integer pieces,
                                               BigDecimal maxLengthCm) {
    }

    public record CompareCommand(PartyInput pickup, PartyInput delivery, PackageInput pkg) {
    }

    public record BreakdownLine(String label, String component, BigDecimal amount) {
    }

    public record OptionView(UUID optionId, String providerCode, String providerName, String serviceName,
                             String transportMode, Integer etaMinDays, Integer etaMaxDays, BigDecimal chargeableWeightKg,
                             BigDecimal baseFreight, BigDecimal otherCharges, BigDecimal markupPercent,
                             BigDecimal markupAmount, BigDecimal taxAmount, BigDecimal totalAmount, String currency,
                             List<BreakdownLine> breakdown, Instant validUntil) {
    }

    public record UnavailableView(String providerCode, String providerName, String reason) {
    }

    public record QuoteView(UUID quoteId, String status, Instant expiresAt, String pickupPincode, String deliveryPincode,
                            BigDecimal actualWeightKg, BigDecimal volumetricWeightKg, int packageCount,
                            BigDecimal declaredValue, String paymentMode, List<OptionView> options,
                            List<UnavailableView> unavailable) {
    }
}
