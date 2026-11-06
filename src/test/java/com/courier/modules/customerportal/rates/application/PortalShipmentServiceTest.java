package com.courier.modules.customerportal.rates.application;

import com.courier.modules.customerportal.application.ActingCustomer;
import com.courier.modules.customerportal.application.CurrentCustomerResolver;
import com.courier.modules.customerportal.rates.application.PortalShipmentService.BookingDetail;
import com.courier.modules.customerportal.rates.application.PortalShipmentService.BookingSummary;
import com.courier.modules.customerportal.rates.application.PortalShipmentService.TrackingView;
import com.courier.modules.customerportal.rates.domain.CourierProviderAdapter;
import com.courier.modules.customerportal.rates.domain.LabelResult;
import com.courier.modules.customerportal.rates.domain.PortalBooking;
import com.courier.modules.customerportal.rates.domain.ProviderResult;
import com.courier.modules.customerportal.rates.domain.RateQuote;
import com.courier.modules.customerportal.rates.domain.RateQuoteOption;
import com.courier.modules.customerportal.rates.domain.RateRequest;
import com.courier.modules.customerportal.rates.domain.ShipmentRef;
import com.courier.modules.customerportal.rates.domain.TrackingEvent;
import com.courier.modules.customerportal.rates.domain.TrackingResult;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PortalShipmentServiceTest {

    private static final ActingCustomer ACTING = new ActingCustomer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private static final byte[] PDF = "%PDF-1.4 label".getBytes();

    private final CurrentCustomerResolver resolver = mock(CurrentCustomerResolver.class);
    private final PortalBookingStore store = mock(PortalBookingStore.class);
    private final ProviderCallLimiter limiter = mock(ProviderCallLimiter.class);
    private final LabelFetcher fetcher = mock(LabelFetcher.class);
    private final TrackingSyncService trackingSync = mock(TrackingSyncService.class);
    private final AtomicInteger labelCalls = new AtomicInteger();
    private TrackingResult tracking = TrackingResult.unavailable("none");
    private LabelResult label = LabelResult.notAvailable("not yet");

    @BeforeEach
    void setUp() {
        when(resolver.require()).thenReturn(ACTING);
    }

    private final CourierProviderAdapter shiprocket = new CourierProviderAdapter() {
        @Override public String code() { return "SHIPROCKET"; }
        @Override public String displayName() { return "Shiprocket"; }
        @Override public boolean external() { return true; }
        @Override public boolean configured() { return true; }
        @Override public ProviderResult getRates(RateRequest r) { return null; }
        @Override public TrackingResult track(ShipmentRef ref) { return tracking; }
        @Override public LabelResult label(ShipmentRef ref) { labelCalls.incrementAndGet(); return label; }
        @Override public Set<String> labelHosts() { return Set.of(".amazonaws.com"); }
    };

    private final CourierProviderAdapter occpl = new CourierProviderAdapter() {
        @Override public String code() { return "OCCPL_NETWORK"; }
        @Override public String displayName() { return "OCCPL Network"; }
        @Override public boolean external() { return false; }
        @Override public boolean configured() { return true; }
        @Override public ProviderResult getRates(RateRequest r) { return null; }
    };

    private PortalShipmentService service() {
        return new PortalShipmentService(resolver, store, List.of(shiprocket, occpl), limiter, fetcher, trackingSync);
    }

    private static PortalBooking booking(String provider, PortalBooking.Status status) {
        PortalBooking b = PortalBooking.builder().customerId(ACTING.customerId()).quoteId(UUID.randomUUID())
                .quoteOptionId(UUID.randomUUID()).providerCode(provider).status(status)
                .totalAmount(new BigDecimal("250.00")).currency("INR").providerOrderRef("SR-1").awbNumber("AWB1").build();
        b.setId(UUID.randomUUID());
        return b;
    }

    private static RateQuote quote() {
        Map<String, Object> pickup = Map.of("name", "Shop", "phone", "9876543210", "address", "A-1", "pincode", "411016", "city", "Pune");
        Map<String, Object> delivery = Map.of("name", "Ravi", "phone", "9123456780", "address", "B-2", "pincode", "110001", "city", "Delhi");
        Map<String, Object> pkg = Map.of("length", "30", "breadth", "20", "height", "10", "dimensionUnit", "CM", "contents", "Books");
        return RateQuote.builder().customerId(ACTING.customerId()).request(Map.of("pickup", pickup, "delivery", delivery, "package", pkg))
                .actualWeightKg(new BigDecimal("2.5")).volumetricWeightKg(new BigDecimal("1.2")).packageCount(1)
                .declaredValue(new BigDecimal("1500")).paymentMode("PREPAID").build();
    }

    private static RateQuoteOption option() {
        return RateQuoteOption.builder().serviceName("Xpress Air").transportMode("AIR").etaMinDays(2).etaMaxDays(3)
                .chargeableWeightKg(new BigDecimal("2.5"))
                .breakdown(List.of(Map.of("label", "Freight", "component", "FREIGHT", "amount", "200.00")))
                .build();
    }

    @Test
    @DisplayName("history: newest-first page with route, service and amounts; size capped at 50")
    void lists() {
        PortalBooking b = booking("SHIPROCKET", PortalBooking.Status.BOOKED);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        when(store.page(eq(ACTING), any(), page.capture())).thenReturn(new PageImpl<>(
                List.of(new PortalBookingStore.Row(b, quote(), option()))));

        List<BookingSummary> rows = service().list(null, null, null, null, 0, 500).getContent();

        assertThat(page.getValue().getPageSize()).isEqualTo(50);
        BookingSummary r = rows.get(0);
        assertThat(r.providerName()).isEqualTo("Shiprocket");
        assertThat(r.serviceName()).isEqualTo("Xpress Air");
        assertThat(r.pickupCity()).isEqualTo("Pune");
        assertThat(r.deliveryName()).isEqualTo("Ravi");
        assertThat(r.deliveryPincode()).isEqualTo("110001");
        assertThat(r.trackingNumber()).isEqualTo("AWB1");
        assertThat(r.totalAmount()).isEqualByComparingTo("250.00");
    }

    @Test
    @DisplayName("history: status filter narrows to one status; an unknown status is a 422")
    void filters() {
        ArgumentCaptor<PortalBookingStore.HistoryFilter> filter = ArgumentCaptor.forClass(PortalBookingStore.HistoryFilter.class);
        when(store.page(eq(ACTING), filter.capture(), any())).thenReturn(new PageImpl<>(List.of()));

        service().list("booked", null, null, null, 0, 20);

        assertThat(filter.getValue().statuses()).containsExactly(PortalBooking.Status.BOOKED);
        assertThat(filter.getValue().providerCode()).isNull();
        service().list("pending,SUBMITTING", null, null, null, 0, 20);
        assertThat(filter.getValue().statuses()).containsExactlyInAnyOrder(PortalBooking.Status.PENDING, PortalBooking.Status.SUBMITTING);
        assertThatThrownBy(() -> service().list("SHIPPED", null, null, null, 0, 20)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("history: courier and India-time date range (both days inclusive); start after end is refused")
    void filtersByCourierAndDates() {
        ArgumentCaptor<PortalBookingStore.HistoryFilter> filter = ArgumentCaptor.forClass(PortalBookingStore.HistoryFilter.class);
        when(store.page(eq(ACTING), filter.capture(), any())).thenReturn(new PageImpl<>(List.of()));

        service().list(null, " delhivery ", java.time.LocalDate.of(2026, 10, 1), java.time.LocalDate.of(2026, 10, 31), 0, 20);

        PortalBookingStore.HistoryFilter f = filter.getValue();
        assertThat(f.statuses()).hasSize(PortalBooking.Status.values().length);
        assertThat(f.providerCode()).isEqualTo("DELHIVERY");
        assertThat(f.from()).isEqualTo(java.time.Instant.parse("2026-09-30T18:30:00Z"));
        assertThat(f.to()).isEqualTo(java.time.Instant.parse("2026-10-31T18:30:00Z"));
        assertThatThrownBy(() -> service().list(null, null, java.time.LocalDate.of(2026, 10, 2), java.time.LocalDate.of(2026, 10, 1), 0, 20))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("history: a delivery filter means booked shipments; awaiting pickup also lets through unchecked ones")
    void filtersByDelivery() {
        ArgumentCaptor<PortalBookingStore.HistoryFilter> filter = ArgumentCaptor.forClass(PortalBookingStore.HistoryFilter.class);
        when(store.page(eq(ACTING), filter.capture(), any())).thenReturn(new PageImpl<>(List.of()));

        service().list("FAILED", "in_transit,DELIVERED", null, null, null, 0, 20);
        assertThat(filter.getValue().statuses()).containsExactly(PortalBooking.Status.BOOKED);
        assertThat(filter.getValue().stages()).containsExactlyInAnyOrder(
                com.courier.modules.customerportal.rates.domain.DeliveryStage.IN_TRANSIT,
                com.courier.modules.customerportal.rates.domain.DeliveryStage.DELIVERED);
        assertThat(filter.getValue().includeUnchecked()).isFalse();

        service().list(null, "AWAITING_PICKUP", null, null, null, 0, 20);
        assertThat(filter.getValue().includeUnchecked()).isTrue();

        service().list(null, null, null, null, null, 0, 20);
        assertThat(filter.getValue().stages()).isNull();
        assertThatThrownBy(() -> service().list(null, "LOST", null, null, null, 0, 20)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("couriers: the providers booked with, by display name")
    void couriers() {
        when(store.providerCodes(ACTING)).thenReturn(List.of("SHIPROCKET"));

        assertThat(service().couriers()).extracting(PortalShipmentService.Courier::name).containsExactly("Shiprocket");
    }

    @Test
    @DisplayName("detail: parties, parcel and frozen charges; OCCPL bookings never offer a label download")
    void detail() {
        PortalBooking sr = booking("SHIPROCKET", PortalBooking.Status.BOOKED);
        PortalBooking own = booking("OCCPL_NETWORK", PortalBooking.Status.BOOKED);
        when(store.row(ACTING, sr.getId())).thenReturn(Optional.of(new PortalBookingStore.Row(sr, quote(), option())));
        when(store.row(ACTING, own.getId())).thenReturn(Optional.of(new PortalBookingStore.Row(own, quote(), option())));

        BookingDetail d = service().detail(sr.getId());

        assertThat(d.labelDownloadable()).isTrue();
        assertThat(d.pickup().city()).isEqualTo("Pune");
        assertThat(d.parcel().length()).isEqualByComparingTo("30");
        assertThat(d.parcel().contents()).isEqualTo("Books");
        assertThat(d.charges()).hasSize(1);
        assertThat(d.etaMaxDays()).isEqualTo(3);
        assertThat(service().detail(own.getId()).labelDownloadable()).isFalse();
    }

    @Test
    @DisplayName("detail: someone else's booking is a 404")
    void detailNotOwned() {
        when(store.row(eq(ACTING), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().detail(UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("tracking: a booking that is not BOOKED does not call the provider or spend the limit")
    void notBookedNotTracked() {
        PortalBooking b = booking("SHIPROCKET", PortalBooking.Status.OUTCOME_UNKNOWN);
        when(store.findOwned(ACTING, b.getId())).thenReturn(Optional.of(b));
        tracking = null; // would NPE if asked

        TrackingView v = service().tracking(b.getId());

        assertThat(v.available()).isFalse();
        verify(limiter, never()).check(any());
    }

    @Test
    @DisplayName("tracking: provider events are passed through and a newly issued tracking number is stored")
    void tracksAndRecordsNumber() {
        PortalBooking b = booking("SHIPROCKET", PortalBooking.Status.BOOKED);
        b.setAwbNumber(null);
        when(store.findOwned(ACTING, b.getId())).thenReturn(Optional.of(b));
        tracking = TrackingResult.of("In Transit", "AWB9", "Delhi",
                List.of(new TrackingEvent(Instant.now(), "In Transit", "Delhi", null)));

        TrackingView v = service().tracking(b.getId());

        assertThat(v.available()).isTrue();
        assertThat(v.trackingNumber()).isEqualTo("AWB9");
        assertThat(v.events()).hasSize(1);
        verify(limiter).check(ACTING.userId());
        verify(store).recordTrackingNumber(ACTING, b.getId(), "AWB9");
        verify(trackingSync).record(eq(b.getId()), eq(ACTING.companyId()), eq(tracking), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("label: a stored URL that still works is used without asking the provider")
    void storedLabel() {
        PortalBooking b = booking("SHIPROCKET", PortalBooking.Status.BOOKED);
        b.setLabelUrl("https://x.s3.amazonaws.com/l.pdf");
        when(store.findOwned(ACTING, b.getId())).thenReturn(Optional.of(b));
        when(fetcher.fetch(eq("https://x.s3.amazonaws.com/l.pdf"), any())).thenReturn(Optional.of(PDF));

        PortalShipmentService.Label l = service().label(b.getId());

        assertThat(l.pdf()).isEqualTo(PDF);
        assertThat(l.fileName()).isEqualTo("label-AWB1.pdf");
        assertThat(labelCalls).hasValue(0);
    }

    @Test
    @DisplayName("label: an expired stored URL is forgotten and a fresh one fetched and stored")
    void expiredLabelRefreshed() {
        PortalBooking b = booking("SHIPROCKET", PortalBooking.Status.BOOKED);
        b.setLabelUrl("https://x.s3.amazonaws.com/old.pdf");
        when(store.findOwned(ACTING, b.getId())).thenReturn(Optional.of(b));
        when(fetcher.fetch(eq("https://x.s3.amazonaws.com/old.pdf"), any())).thenReturn(Optional.empty());
        when(fetcher.fetch(eq("https://x.s3.amazonaws.com/new.pdf"), any())).thenReturn(Optional.of(PDF));
        label = LabelResult.at("https://x.s3.amazonaws.com/new.pdf");

        assertThat(service().label(b.getId()).pdf()).isEqualTo(PDF);
        verify(store).recordLabelUrl(ACTING, b.getId(), null);
        verify(store).recordLabelUrl(ACTING, b.getId(), "https://x.s3.amazonaws.com/new.pdf");
    }

    @Test
    @DisplayName("label: none from the provider is a 422 with its message; unconfirmed bookings have none")
    void noLabel() {
        PortalBooking b = booking("SHIPROCKET", PortalBooking.Status.BOOKED);
        PortalBooking failed = booking("SHIPROCKET", PortalBooking.Status.FAILED);
        when(store.findOwned(ACTING, b.getId())).thenReturn(Optional.of(b));
        when(store.findOwned(ACTING, failed.getId())).thenReturn(Optional.of(failed));

        assertThatThrownBy(() -> service().label(b.getId())).isInstanceOf(BusinessRuleException.class).hasMessage("not yet");
        assertThatThrownBy(() -> service().label(failed.getId())).isInstanceOf(BusinessRuleException.class);
    }
}
