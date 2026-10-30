package com.courier.modules.customerportal.rates.application;

import com.courier.modules.customerportal.application.ActingCustomer;
import com.courier.modules.customerportal.application.CurrentCustomerResolver;
import com.courier.modules.customerportal.rates.application.MarkupCalculator.Markup;
import com.courier.modules.customerportal.rates.application.RateComparisonService.CompareCommand;
import com.courier.modules.customerportal.rates.application.RateComparisonService.PackageInput;
import com.courier.modules.customerportal.rates.application.RateComparisonService.PartyInput;
import com.courier.modules.customerportal.rates.application.RateComparisonService.QuoteView;
import com.courier.modules.customerportal.rates.domain.ChargeComponent;
import com.courier.modules.customerportal.rates.domain.ChargeLine;
import com.courier.modules.customerportal.rates.domain.CourierProviderAdapter;
import com.courier.modules.customerportal.rates.domain.ProviderRate;
import com.courier.modules.customerportal.rates.domain.ProviderResult;
import com.courier.modules.customerportal.rates.domain.RateQuote;
import com.courier.modules.customerportal.rates.domain.RateQuoteOption;
import com.courier.modules.customerportal.rates.domain.RateRequest;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateComparisonServiceTest {

    private static final ActingCustomer ACTING = new ActingCustomer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    private final CurrentCustomerResolver resolver = mock(CurrentCustomerResolver.class);
    private final MarkupRuleService markups = mock(MarkupRuleService.class);
    private final RateQuoteStore store = mock(RateQuoteStore.class);
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final CustomerPortalProperties properties = new CustomerPortalProperties();
    private final CustomerCourierAccessService access = mock(CustomerCourierAccessService.class);
    /** The couriers the customer is allowed in a test; everything by default, so only the access tests narrow it. */
    private java.util.Set<String> allowed = java.util.Set.of("OCCPL_NETWORK", "SHIPROCKET", "DELHIVERY", "BLUEDART", "EKART", "SHIPYAARI", "SKART");

    @BeforeEach
    void setUp() {
        when(resolver.require()).thenReturn(ACTING);
        when(access.allowedCodes(any(), any())).thenAnswer(i -> allowed);
        when(markups.resolve(any(), any(), any(), any())).thenReturn(
                new Markup(UUID.randomUUID(), true, new BigDecimal("10"), EnumSet.of(ChargeComponent.FREIGHT), new BigDecimal("18")));
        when(store.save(any(), anyList())).thenAnswer(i -> {
            RateQuote q = i.getArgument(0);
            q.setId(UUID.randomUUID());
            List<RateQuoteOption> rows = i.getArgument(1);
            rows.forEach(o -> o.setId(UUID.randomUUID()));
            return new RateQuoteStore.Saved(q, rows);
        });
        properties.setProviderTimeout(Duration.ofMillis(300));
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private static CourierProviderAdapter adapter(String code, boolean external, boolean configured,
                                                  Function<RateRequest, ProviderResult> rates) {
        return new CourierProviderAdapter() {
            @Override public String code() { return code; }
            @Override public String displayName() { return code; }
            @Override public boolean external() { return external; }
            @Override public boolean configured() { return configured; }
            @Override public ProviderResult getRates(RateRequest r) { return rates.apply(r); }
        };
    }

    private static ProviderResult priced(String code, String freight) {
        return ProviderResult.available(code, code, List.of(new ProviderRate("S1", code + " Standard", "SURFACE", 2, 3,
                new BigDecimal("2.5"), List.of(new ChargeLine("FREIGHT", "Freight", ChargeComponent.FREIGHT, new BigDecimal(freight))),
                null, null)));
    }

    private RateComparisonService service(CourierProviderAdapter... adapters) {
        return new RateComparisonService(resolver, List.of(adapters), markups, store, new CompareRateLimiter(),
                properties, executor, access);
    }

    static CompareCommand command(String deliveryPincode, String weight) {
        PartyInput pickup = new PartyInput("Asha", "9876543210", "Office 4, Thorat Chowk", "411016", "Pune", "Maharashtra");
        PartyInput delivery = new PartyInput("Ravi", "9876543211", "12 Connaught Place", deliveryPincode, "New Delhi", "Delhi");
        return new CompareCommand(pickup, delivery, new PackageInput(new BigDecimal(weight), new BigDecimal("30"),
                new BigDecimal("20"), new BigDecimal("10"), "CM", 1, "Garments", new BigDecimal("1500"), "PREPAID"));
    }

    @Test
    @DisplayName("markup on external providers only; OCCPL network gets tax but no markup; sorted by total")
    void pricesAndSorts() {
        QuoteView q = service(
                adapter("OCCPL_NETWORK", false, true, r -> priced("OCCPL_NETWORK", "100")),
                adapter("SHIPROCKET", true, true, r -> priced("SHIPROCKET", "90"))).compare(command("110001", "2.5"));

        assertThat(q.options()).extracting(RateComparisonService.OptionView::providerCode)
                .containsExactly("SHIPROCKET", "OCCPL_NETWORK");
        var shiprocket = q.options().get(0);
        assertThat(shiprocket.markupAmount()).isEqualByComparingTo("9.00");
        assertThat(shiprocket.totalAmount()).isEqualByComparingTo("100.62"); // 90 + 9 + 1.62 GST on the markup
        var occpl = q.options().get(1);
        assertThat(occpl.markupAmount()).isEqualByComparingTo("0.00");
        assertThat(occpl.totalAmount()).isEqualByComparingTo("118.00");      // 100 + 18 % GST
        verify(markups).resolve(eq(ACTING.companyId()), eq("SHIPROCKET"), eq(ACTING.customerId()), any());
    }

    @Test
    @DisplayName("a customer given only one courier sees only that courier, and the others are not even listed as unavailable")
    void onlyAssignedCouriers() {
        allowed = java.util.Set.of("DELHIVERY");

        QuoteView q = service(
                adapter("OCCPL_NETWORK", false, true, r -> priced("OCCPL_NETWORK", "100")),
                adapter("SHIPROCKET", true, true, r -> priced("SHIPROCKET", "90")),
                adapter("DELHIVERY", true, true, r -> priced("DELHIVERY", "95")),
                adapter("BLUEDART", true, false, r -> null)).compare(command("110001", "2.5"));

        assertThat(q.options()).extracting(RateComparisonService.OptionView::providerCode).containsExactly("DELHIVERY");
        assertThat(q.unavailable()).isEmpty();
    }

    @Test
    @DisplayName("a customer with nothing assigned sees OCCPL Network only (the service's default)")
    void defaultIsOccplOnly() {
        allowed = java.util.Set.of("OCCPL_NETWORK");

        QuoteView q = service(
                adapter("OCCPL_NETWORK", false, true, r -> priced("OCCPL_NETWORK", "100")),
                adapter("SHIPROCKET", true, true, r -> priced("SHIPROCKET", "90"))).compare(command("110001", "2.5"));

        assertThat(q.options()).extracting(RateComparisonService.OptionView::providerCode).containsExactly("OCCPL_NETWORK");
    }

    @Test
    @DisplayName("failing, slow and unconnected providers are listed as unavailable; the others still price")
    void partialAvailability() {
        QuoteView q = service(
                adapter("OCCPL_NETWORK", false, true, r -> priced("OCCPL_NETWORK", "100")),
                adapter("DELHIVERY", true, true, r -> { throw new IllegalStateException("boom"); }),
                adapter("SHIPROCKET", true, true, r -> {
                    try { Thread.sleep(5_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return priced("SHIPROCKET", "1");
                }),
                adapter("BLUEDART", true, false, r -> priced("BLUEDART", "1"))).compare(command("110001", "2.5"));

        assertThat(q.options()).hasSize(1);
        assertThat(q.unavailable()).extracting(RateComparisonService.UnavailableView::providerCode)
                .containsExactlyInAnyOrder("DELHIVERY", "SHIPROCKET", "BLUEDART");
        assertThat(q.unavailable()).filteredOn(u -> u.providerCode().equals("SHIPROCKET"))
                .extracting(RateComparisonService.UnavailableView::reason).first().asString().contains("too long");
    }

    @Test
    @DisplayName("the quote snapshot holds the request, weights and the customer, never a browser price")
    void snapshot() {
        service(adapter("SHIPROCKET", true, true, r -> priced("SHIPROCKET", "90"))).compare(command("110001", "2.5"));

        ArgumentCaptor<RateQuote> captor = ArgumentCaptor.forClass(RateQuote.class);
        verify(store).save(captor.capture(), anyList());
        RateQuote q = captor.getValue();
        assertThat(q.getCustomerId()).isEqualTo(ACTING.customerId());
        assertThat(q.getVolumetricWeightKg()).isEqualByComparingTo("1.200"); // 30 x 20 x 10 / 5000
        assertThat(q.getRequest()).containsKeys("pickup", "delivery", "package");
        assertThat(q.getExpiresAt()).isAfter(java.time.Instant.now());
    }

    @Test
    @DisplayName("invalid input is refused before any provider is asked")
    void validation() {
        RateComparisonService s = service(adapter("SHIPROCKET", true, true, r -> { throw new AssertionError("asked"); }));

        assertThatThrownBy(() -> s.compare(command("011001", "2.5"))).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> s.compare(command("110001", "0"))).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> s.compare(command("110001", "2.5555"))).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("inches are converted to centimetres before providers are asked")
    void inches() {
        RateComparisonService.CompareCommand c = command("110001", "2.5");
        var pkg = c.pkg();
        var inInches = new CompareCommand(c.pickup(), c.delivery(), new PackageInput(pkg.actualWeightKg(),
                new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("10"), "IN", 1, "Box", pkg.declaredValue(), "PREPAID"));

        RateRequest r = RateComparisonService.toRequest(ACTING, inInches);

        assertThat(r.lengthCm()).isEqualByComparingTo("25.4");
    }

    @Test
    @DisplayName("another customer's quote is not found")
    void otherCustomersQuote() {
        when(store.load(any(), eq(ACTING))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().get(UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("comparisons are rate-limited per account")
    void rateLimited() {
        CompareRateLimiter limiter = new CompareRateLimiter();
        UUID user = UUID.randomUUID();
        for (int i = 0; i < CompareRateLimiter.MAX_PER_WINDOW; i++) limiter.check(user);
        assertThatThrownBy(() -> limiter.check(user)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("estimate prices from pincodes alone, stores nothing, and uses the actual weight when there are no dimensions")
    void estimate() {
        var captured = new java.util.concurrent.atomic.AtomicReference<RateRequest>();
        QuoteView q = service(adapter("SHIPROCKET", true, true, r -> { captured.set(r); return priced("SHIPROCKET", "90"); }))
                .estimate(new RateComparisonService.EstimateCommand("400001", "411016", 10, new BigDecimal("200"),
                        new BigDecimal("500"), null, null, null, null, "PREPAID"));

        assertThat(q.options()).hasSize(1);
        assertThat(q.volumetricWeightKg()).isEqualByComparingTo("200");
        assertThat(captured.get().pickupPincode()).isEqualTo("400001");
        verify(store, org.mockito.Mockito.never()).save(any(), anyList());
    }

    /** An international-only provider, like sKart. */
    private static CourierProviderAdapter international(boolean configured,
            Function<com.courier.modules.customerportal.rates.domain.InternationalRateProvider.Request, ProviderResult> rates) {
        abstract class Intl implements CourierProviderAdapter, com.courier.modules.customerportal.rates.domain.InternationalRateProvider { }
        return new Intl() {
            @Override public String code() { return "SKART"; }
            @Override public String displayName() { return "sKart International"; }
            @Override public boolean external() { return true; }
            @Override public boolean domestic() { return false; }
            @Override public boolean configured() { return configured; }
            @Override public ProviderResult getRates(RateRequest r) { throw new AssertionError("asked for domestic rates"); }
            @Override public ProviderResult internationalRates(Request r) { return rates.apply(r); }
            @Override public List<Country> countries(UUID companyId) {
                return List.of(new Country(12, "AU", "AUSTRALIA", "South West Pacific", true));
            }
        };
    }

    @Test
    @DisplayName("an international-only provider is neither asked nor listed in the domestic comparison")
    void internationalNotDomestic() {
        QuoteView q = service(adapter("SHIPROCKET", true, true, r -> priced("SHIPROCKET", "90")),
                international(true, r -> priced("SKART", "500"))).compare(command("110001", "2.5"));

        assertThat(q.options()).extracting(o -> o.providerCode()).containsExactly("SHIPROCKET");
        assertThat(q.unavailable()).isEmpty();
    }

    @Test
    @DisplayName("international estimate: asks the international provider with the country, marks it up like any external rate")
    void internationalEstimate() {
        var asked = new java.util.concurrent.atomic.AtomicReference<com.courier.modules.customerportal.rates.domain.InternationalRateProvider.Request>();
        RateComparisonService s = service(adapter("SHIPROCKET", true, true, r -> { throw new AssertionError("domestic asked"); }),
                international(true, r -> { asked.set(r); return priced("SKART", "1000"); }));

        QuoteView q = s.estimateInternational(new RateComparisonService.InternationalEstimateCommand("110049", " AUSTRALIA ", "3001",
                com.courier.modules.customerportal.rates.domain.InternationalRateProvider.Kind.PARCEL, new BigDecimal("2"), 0,
                new BigDecimal("30")));

        assertThat(asked.get().destinationCountry()).isEqualTo("AUSTRALIA");
        assertThat(asked.get().pieces()).isEqualTo(1);
        assertThat(asked.get().companyId()).isEqualTo(ACTING.companyId());
        assertThat(q.options()).singleElement().satisfies(o -> {
            assertThat(o.providerCode()).isEqualTo("SKART");
            assertThat(o.markupAmount()).isEqualByComparingTo("100");
        });
        assertThat(q.deliveryPincode()).isEqualTo("3001");
        assertThat(s.internationalCountries()).extracting(c -> c.name()).containsExactly("AUSTRALIA");
        verify(store, org.mockito.Mockito.never()).save(any(), anyList());
    }

    @Test
    @DisplayName("international estimate: not connected is listed as unavailable; bad pincode or weight is refused")
    void internationalEstimateEdges() {
        RateComparisonService s = service(international(false, r -> { throw new AssertionError("asked"); }));
        var parcel = com.courier.modules.customerportal.rates.domain.InternationalRateProvider.Kind.PARCEL;

        QuoteView q = s.estimateInternational(new RateComparisonService.InternationalEstimateCommand("110049", "AUSTRALIA", "3001",
                parcel, BigDecimal.ONE, 1, null));
        assertThat(q.options()).isEmpty();
        assertThat(q.unavailable()).singleElement().satisfies(u -> assertThat(u.reason()).contains("not connected"));
        assertThat(s.internationalCountries()).isEmpty();

        assertThatThrownBy(() -> s.estimateInternational(new RateComparisonService.InternationalEstimateCommand("1100", "AUSTRALIA",
                "3001", parcel, BigDecimal.ONE, 1, null))).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> s.estimateInternational(new RateComparisonService.InternationalEstimateCommand("110049", "AUSTRALIA",
                "3001", parcel, BigDecimal.ZERO, 1, null))).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("estimate still validates the pincodes and package")
    void estimateValidates() {
        assertThatThrownBy(() -> service().estimate(new RateComparisonService.EstimateCommand("0123", "411016", 1,
                new BigDecimal("1"), new BigDecimal("100"), null, null, null, null, "PREPAID")))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("pincode");
    }
}
