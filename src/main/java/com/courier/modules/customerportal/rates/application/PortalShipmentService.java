package com.courier.modules.customerportal.rates.application;

import com.courier.modules.customerportal.application.ActingCustomer;
import com.courier.modules.customerportal.application.CurrentCustomerResolver;
import com.courier.modules.customerportal.rates.application.PortalBookingStore.Row;
import com.courier.modules.customerportal.rates.application.RateComparisonService.BreakdownLine;
import com.courier.modules.customerportal.rates.domain.BookingRequest;
import com.courier.modules.customerportal.rates.domain.CourierProviderAdapter;
import com.courier.modules.customerportal.rates.domain.LabelResult;
import com.courier.modules.customerportal.rates.domain.DeliveryStage;
import com.courier.modules.customerportal.rates.domain.PortalBooking;
import com.courier.modules.customerportal.rates.domain.RateQuote;
import com.courier.modules.customerportal.rates.domain.RateQuoteOption;
import com.courier.modules.customerportal.rates.domain.ShipmentRef;
import com.courier.modules.customerportal.rates.domain.TrackingEvent;
import com.courier.modules.customerportal.rates.domain.TrackingResult;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * After booking: the customer's booking history, one booking in full, live tracking and the shipping label.
 * Every read is of the signed-in customer's own bookings (404 for anyone else's). Tracking is asked of the
 * provider on each view and not stored; labels are downloaded by the server from the provider and streamed,
 * so the provider's URL never reaches the browser.
 */
@Slf4j
@Service
public class PortalShipmentService {

    static final int MAX_PAGE_SIZE = 50;
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    /** Upper bound when no end date is given; far beyond any booking. */
    private static final Instant END_OF_TIME = Instant.parse("9999-01-01T00:00:00Z");

    private final CurrentCustomerResolver resolver;
    private final PortalBookingStore store;
    private final List<CourierProviderAdapter> adapters;
    private final ProviderCallLimiter limiter;
    private final LabelFetcher labels;
    private final TrackingSyncService trackingSync;

    public PortalShipmentService(CurrentCustomerResolver resolver, PortalBookingStore store,
                                 List<CourierProviderAdapter> adapters, ProviderCallLimiter limiter, LabelFetcher labels,
                                 TrackingSyncService trackingSync) {
        this.trackingSync = trackingSync;
        this.resolver = resolver;
        this.store = store;
        this.adapters = adapters;
        this.limiter = limiter;
        this.labels = labels;
    }

    /**
     * Newest first. {@code status} is one booking status or several comma-separated, {@code provider} one provider code (both null/blank for all);
     * {@code from} and {@code to} are booking dates in India time, both inclusive, either may be null.
     */
    public Page<BookingSummary> list(String status, String provider, LocalDate from, LocalDate to, int page, int size) {
        return list(status, null, provider, from, to, page, size);
    }

    /**
     * As {@link #list(String, String, LocalDate, LocalDate, int, int)}, also narrowed to delivery stages ({@code delivery}:
     * one {@link DeliveryStage} or several comma-separated; AWAITING_PICKUP also matches booked shipments not yet checked).
     * A delivery filter implies booked shipments.
     */
    public Page<BookingSummary> list(String status, String delivery, String provider, LocalDate from, LocalDate to, int page, int size) {
        ActingCustomer acting = resolver.require();
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessRuleException("The start date must be on or before the end date.");
        }
        String code = provider == null || provider.isBlank() ? null : provider.trim().toUpperCase(Locale.ROOT);
        Set<DeliveryStage> stages = stages(delivery);
        Set<PortalBooking.Status> statuses = stages == null ? statuses(status) : EnumSet.of(PortalBooking.Status.BOOKED);
        PortalBookingStore.HistoryFilter filter = new PortalBookingStore.HistoryFilter(statuses, code,
                from == null ? Instant.EPOCH : from.atStartOfDay(INDIA).toInstant(),
                to == null ? END_OF_TIME : to.plusDays(1).atStartOfDay(INDIA).toInstant(),
                stages, stages != null && stages.contains(DeliveryStage.AWAITING_PICKUP));
        PageRequest request = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, MAX_PAGE_SIZE)));
        return store.page(acting, filter, request).map(this::summary);
    }

    /** The couriers this customer has booked with, by name, for the history filter. */
    public List<Courier> couriers() {
        ActingCustomer acting = resolver.require();
        return store.providerCodes(acting).stream().map(c -> new Courier(c, providerName(c)))
                .sorted(java.util.Comparator.comparing(Courier::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    public BookingDetail detail(UUID bookingId) {
        ActingCustomer acting = resolver.require();
        return detail(store.row(acting, bookingId).orElseThrow(() -> new ResourceNotFoundException("Booking", bookingId)));
    }

    public TrackingView tracking(UUID bookingId) {
        ActingCustomer acting = resolver.require();
        PortalBooking b = store.findOwned(acting, bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingId));
        CourierProviderAdapter adapter = adapter(b.getProviderCode());
        if (b.getStatus() != PortalBooking.Status.BOOKED) {
            return new TrackingView(b.getId(), adapter.displayName(), b.getAwbNumber(), false, null, null, List.of(),
                    b.getStatus() == PortalBooking.Status.FAILED ? "This booking was not placed, so there is nothing to track."
                            : b.getStatus() == PortalBooking.Status.CANCELLED ? "This booking was cancelled."
                            : "Tracking starts once the booking is confirmed.", Instant.now());
        }
        limiter.check(acting.userId());
        TrackingResult result;
        try {
            result = adapter.track(ref(acting, b));
        } catch (RuntimeException e) {
            log.error("Tracking booking {} with {} failed unexpectedly", b.getId(), adapter.code(), e);
            result = TrackingResult.unavailable("Tracking is not available right now. Please try again later.");
        }
        if (result.trackingNumber() != null && b.getAwbNumber() == null) {
            store.recordTrackingNumber(acting, b.getId(), result.trackingNumber());
        }
        try {
            trackingSync.record(b.getId(), acting.companyId(), result, Instant.now(), adapter.external());
        } catch (RuntimeException e) {
            log.warn("Saving the delivery status of booking {} failed: {}", b.getId(), e.getClass().getSimpleName());
        }
        return new TrackingView(b.getId(), adapter.displayName(),
                result.trackingNumber() != null ? result.trackingNumber() : b.getAwbNumber(), result.available(),
                result.status(), result.currentLocation(), result.events(), result.message(), Instant.now());
    }

    /**
     * The label PDF. A stored provider URL is tried first; if it no longer answers (expired link) it is
     * forgotten and the provider asked once for a fresh one.
     */
    public Label label(UUID bookingId) {
        ActingCustomer acting = resolver.require();
        PortalBooking b = store.findOwned(acting, bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingId));
        if (b.getStatus() != PortalBooking.Status.BOOKED) {
            throw new BusinessRuleException("A label is available only for a confirmed booking.");
        }
        CourierProviderAdapter adapter = adapter(b.getProviderCode());
        limiter.check(acting.userId());
        String fileName = "label-" + (b.getAwbNumber() != null ? b.getAwbNumber().replaceAll("[^A-Za-z0-9_-]", "")
                : b.getId().toString()) + ".pdf";
        if (b.getLabelUrl() != null) {
            Optional<byte[]> pdf = labels.fetch(b.getLabelUrl(), adapter.labelHosts());
            if (pdf.isPresent()) {
                return new Label(fileName, pdf.get());
            }
            store.recordLabelUrl(acting, b.getId(), null);
        }
        LabelResult fresh;
        try {
            fresh = adapter.label(ref(acting, b));
        } catch (RuntimeException e) {
            log.error("Label for booking {} with {} failed unexpectedly", b.getId(), adapter.code(), e);
            fresh = LabelResult.notAvailable("The label is not available right now. Please try again later.");
        }
        if (!fresh.available()) {
            throw new BusinessRuleException(fresh.message());
        }
        byte[] pdf = labels.fetch(fresh.url(), adapter.labelHosts())
                .orElseThrow(() -> new BusinessRuleException("The courier's label could not be downloaded. Please try again later."));
        store.recordLabelUrl(acting, b.getId(), fresh.url());
        return new Label(fileName, pdf);
    }

    // ------------------------------------------------------------------ mapping

    private BookingSummary summary(Row row) {
        PortalBooking b = row.booking();
        Map<String, Object> snapshot = row.quote() == null ? Map.of() : row.quote().getRequest();
        BookingRequest.Party pickup = BookingRequest.Party.of(snapshot.get("pickup"));
        BookingRequest.Party delivery = BookingRequest.Party.of(snapshot.get("delivery"));
        RateQuoteOption o = row.option();
        return new BookingSummary(b.getId(), b.getStatus().name(), b.getProviderCode(), providerName(b.getProviderCode()),
                o == null ? null : o.getServiceName(), o == null ? null : o.getTransportMode(), b.getProviderOrderRef(),
                b.getAwbNumber(), pickup.city(), pickup.pincode(), delivery.name(), delivery.city(), delivery.pincode(),
                row.quote() == null ? null : row.quote().getPackageCount(), b.getTotalAmount(), b.getCurrency(),
                b.getCreatedAt(), b.getBookedAt(), delivery(b));
    }

    private static Delivery delivery(PortalBooking b) {
        return b.getDeliveryStatus() == null ? null : new Delivery(b.getDeliveryStatus().name(), b.getDeliveryStatusText(),
                b.getDeliveryLocation(), b.getDeliveryStatusAt(), b.getTrackingSyncedAt());
    }

    private com.courier.modules.customerportal.rates.domain.PickupSchedule schedule(PortalBooking b) {
        if (b.getStatus() != PortalBooking.Status.BOOKED) {
            return com.courier.modules.customerportal.rates.domain.PickupSchedule.NONE;
        }
        try {
            return adapter(b.getProviderCode()).pickupSchedule(b.getCompanyId(), b.getCustomerId(), b.getProviderOrderRef());
        } catch (RuntimeException e) {
            return com.courier.modules.customerportal.rates.domain.PickupSchedule.NONE;
        }
    }

    private com.courier.modules.customerportal.rates.domain.DeliveryIssue deliveryIssue(PortalBooking b) {
        if (b.getStatus() != PortalBooking.Status.BOOKED) {
            return null;
        }
        try {
            return adapter(b.getProviderCode()).deliveryIssue(b.getCompanyId(), b.getCustomerId(), b.getProviderOrderRef());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A confirmed booking whose provider can still cancel it (for OCCPL Network: the parcel has not been collected). */
    private boolean cancellable(PortalBooking b) {
        if (b.getStatus() != PortalBooking.Status.BOOKED) {
            return false;
        }
        try {
            return adapter(b.getProviderCode()).canCancelBooking(b.getCompanyId(), b.getCustomerId(), b.getProviderOrderRef());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private BookingDetail detail(Row row) {
        PortalBooking b = row.booking();
        RateQuote q = row.quote();
        RateQuoteOption o = row.option();
        Map<String, Object> snapshot = q == null ? Map.of() : q.getRequest();
        @SuppressWarnings("unchecked")
        Map<String, Object> pkg = snapshot.get("package") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Parcel parcel = q == null ? null : new Parcel(q.getActualWeightKg(), q.getVolumetricWeightKg(),
                o == null ? null : o.getChargeableWeightKg(), q.getPackageCount(), num(pkg.get("length")),
                num(pkg.get("breadth")), num(pkg.get("height")), str(pkg.get("dimensionUnit")), str(pkg.get("contents")),
                q.getDeclaredValue(), q.getPaymentMode());
        var sched = schedule(b);
        return new BookingDetail(b.getId(), b.getQuoteId(), b.getStatus().name(), b.getProviderCode(), providerName(b.getProviderCode()),
                o == null ? null : o.getServiceName(), o == null ? null : o.getTransportMode(),
                o == null ? null : o.getEtaMinDays(), o == null ? null : o.getEtaMaxDays(), b.getProviderOrderRef(),
                b.getAwbNumber(), b.getStatus() == PortalBooking.Status.BOOKED && labelsOffered(b.getProviderCode()),
                b.getFailureMessage(),
                BookingRequest.Party.of(snapshot.get("pickup")), BookingRequest.Party.of(snapshot.get("delivery")), parcel,
                o == null ? List.of() : RateComparisonService.lines(o), b.getTotalAmount(), b.getCurrency(),
                b.getSubmittedAt(), b.getBookedAt(), b.getCreatedAt(), delivery(b), cancellable(b), sched.date(), sched.slot(), PortalBookingService.PICKUP_SLOTS, deliveryIssue(b));
    }

    private static Set<PortalBooking.Status> statuses(String raw) {
        if (raw == null || raw.isBlank()) {
            return EnumSet.allOf(PortalBooking.Status.class);
        }
        Set<PortalBooking.Status> out = EnumSet.noneOf(PortalBooking.Status.class);
        for (String one : raw.split(",")) {
            if (one.isBlank()) continue;
            try {
                out.add(PortalBooking.Status.valueOf(one.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new BusinessRuleException("Unknown booking status: " + one.trim());
            }
        }
        return out.isEmpty() ? EnumSet.allOf(PortalBooking.Status.class) : out;
    }

    private static Set<DeliveryStage> stages(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Set<DeliveryStage> out = EnumSet.noneOf(DeliveryStage.class);
        for (String one : raw.split(",")) {
            if (one.isBlank()) continue;
            try {
                out.add(DeliveryStage.valueOf(one.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new BusinessRuleException("Unknown delivery status: " + one.trim());
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static ShipmentRef ref(ActingCustomer acting, PortalBooking b) {
        return new ShipmentRef(acting.companyId(), acting.customerId(), b.getId().toString(), b.getProviderOrderRef(),
                b.getAwbNumber(), b.getLabelUrl());
    }

    private CourierProviderAdapter adapter(String code) {
        return adapters.stream().filter(a -> a.code().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown provider " + code));
    }

    /** Providers without label hosts (OCCPL's own network prints labels at the branch) never offer a download. */
    private boolean labelsOffered(String code) {
        return adapters.stream().filter(a -> a.code().equals(code)).anyMatch(a -> !a.labelHosts().isEmpty());
    }

    private String providerName(String code) {
        return adapters.stream().filter(a -> a.code().equals(code)).map(CourierProviderAdapter::displayName)
                .findFirst().orElse(code);
    }

    private static BigDecimal num(Object o) {
        try {
            return o == null ? null : new BigDecimal(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    // ------------------------------------------------------------------ shapes

    public record Courier(String code, String name) {
    }

    /** One row of the history list. {@code bookingReference} is the provider's order or pickup number. */
    public record BookingSummary(UUID bookingId, String status, String providerCode, String providerName,
                                 String serviceName, String transportMode, String bookingReference, String trackingNumber,
                                 String pickupCity, String pickupPincode, String deliveryName, String deliveryCity,
                                 String deliveryPincode, Integer packageCount, BigDecimal totalAmount, String currency,
                                 Instant createdAt, Instant bookedAt, Delivery deliveryStatus) {
    }

    /**
     * The courier's latest delivery status as last saved (null until first known): {@code stage} is one of
     * {@link DeliveryStage}, {@code text} the courier's own words, {@code checkedAt} when it was last asked.
     */
    public record Delivery(String stage, String text, String location, Instant at, Instant checkedAt) {
    }

    /** One booking as it was quoted and booked; the amounts are the frozen quote's. */
    public record BookingDetail(UUID bookingId, UUID quoteId, String status, String providerCode, String providerName,
                                String serviceName, String transportMode, Integer etaMinDays, Integer etaMaxDays,
                                String bookingReference, String trackingNumber, boolean labelDownloadable, String message,
                                BookingRequest.Party pickup, BookingRequest.Party delivery, Parcel parcel,
                                List<BreakdownLine> charges, BigDecimal totalAmount, String currency,
                                Instant submittedAt, Instant bookedAt, Instant createdAt, Delivery deliveryStatus,
                                /** The customer may cancel it now (OCCPL Network, parcel not yet collected). */
                                boolean cancellable,
                                /** The pickup date and slot the customer chose (null when none), and the slots they may pick from. */
                                java.time.LocalDate pickupDate, String pickupSlot, List<String> pickupSlots,
                                /** The last failed delivery attempt and the customer's choice; null when none failed. */
                                com.courier.modules.customerportal.rates.domain.DeliveryIssue deliveryIssue) {
    }

    public record Parcel(BigDecimal actualWeightKg, BigDecimal volumetricWeightKg, BigDecimal chargeableWeightKg,
                         Integer packageCount, BigDecimal length, BigDecimal breadth, BigDecimal height,
                         String dimensionUnit, String contents, BigDecimal declaredValue, String paymentMode) {
    }

    public record TrackingView(UUID bookingId, String providerName, String trackingNumber, boolean available,
                               String status, String currentLocation, List<TrackingEvent> events, String message,
                               Instant retrievedAt) {
    }

    public record Label(String fileName, byte[] pdf) {
    }
}
