package com.courier.modules.customerportal.rates.application;

import com.courier.modules.customerportal.application.ActingCustomer;
import com.courier.modules.customerportal.application.CurrentCustomerResolver;
import com.courier.modules.customerportal.rates.domain.BookingOutcome;
import com.courier.modules.customerportal.rates.domain.BookingRequest;
import com.courier.modules.customerportal.rates.domain.CourierProviderAdapter;
import com.courier.modules.customerportal.rates.domain.PortalBooking;
import com.courier.modules.customerportal.rates.domain.RateQuote;
import com.courier.modules.customerportal.rates.domain.RateRequest;
import com.courier.modules.finance.application.WalletService;
import com.courier.modules.finance.application.command.CustomerBookingDebitCommand;
import com.courier.modules.finance.application.command.CustomerBookingRefundCommand;
import com.courier.shared.audit.application.AuditService;
import com.courier.modules.customerportal.rates.domain.CancelOutcome;
import com.courier.shared.audit.domain.AuditAction;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Books a selected service of a quote, exactly once per idempotency key:
 * <ol>
 *   <li>same key again -> the existing booking (same request) or 409 (different request);</li>
 *   <li>quote owned, option available, not expired, no other active booking of the quote (row lock);</li>
 *   <li>SUBMITTING row committed, then the provider is called outside any transaction;</li>
 *   <li>BOOKED / FAILED (provider certainly refused) / OUTCOME_UNKNOWN (may have been accepted: never
 *       retried automatically, operations reconcile).</li>
 * </ol>
 * The amount is the frozen quote option's; nothing the browser sends is a price.
 */
@Slf4j
@Service
public class PortalBookingService {

    static final Pattern KEY = Pattern.compile("^[A-Za-z0-9_-]{16,80}$");

    private final CurrentCustomerResolver resolver;
    private final PortalBookingStore store;
    private final List<CourierProviderAdapter> adapters;
    private final AuditService auditService;
    private final WalletService walletService;
    private final CustomerPortalProperties properties;
    private final com.courier.modules.customer.application.CustomerPortalLookup customers;
    private final org.springframework.context.ApplicationEventPublisher events;

    /** Without notifications (tests). */
    public PortalBookingService(CurrentCustomerResolver resolver, PortalBookingStore store,
                                List<CourierProviderAdapter> adapters, AuditService auditService,
                                WalletService walletService, CustomerPortalProperties properties,
                                com.courier.modules.customer.application.CustomerPortalLookup customers) {
        this(resolver, store, adapters, auditService, walletService, properties, customers, e -> { });
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PortalBookingService(CurrentCustomerResolver resolver, PortalBookingStore store,
                                List<CourierProviderAdapter> adapters, AuditService auditService,
                                WalletService walletService, CustomerPortalProperties properties,
                                com.courier.modules.customer.application.CustomerPortalLookup customers,
                                org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
        this.resolver = resolver;
        this.store = store;
        this.adapters = adapters;
        this.auditService = auditService;
        this.walletService = walletService;
        this.properties = properties;
        this.customers = customers;
    }

    public BookingView book(UUID quoteId, UUID optionId, String idempotencyKey) {
        return book(quoteId, optionId, idempotencyKey, Pins.NONE);
    }

    /** A point the customer placed on the map. */
    public record Pin(BigDecimal lat, BigDecimal lng) {
    }

    /** The pickup and delivery map pins placed at booking; either may be null. */
    public record Pins(Pin pickup, Pin delivery) {
        public static final Pins NONE = new Pins(null, null);
    }

    public BookingView book(UUID quoteId, UUID optionId, String idempotencyKey, Pins pins) {
        ActingCustomer acting = resolver.require();
        Pins where = pins == null ? Pins.NONE : pins;
        checkPin(where.pickup(), "pickup");
        checkPin(where.delivery(), "delivery");
        if (quoteId == null || optionId == null) {
            throw new BusinessRuleException("Choose a courier service to book.");
        }
        if (idempotencyKey == null || !KEY.matcher(idempotencyKey).matches()) {
            throw new BusinessRuleException("A valid Idempotency-Key header (16-80 letters, digits, - or _) is required.");
        }
        String hash = hash(quoteId + ":" + optionId);

        PortalBookingStore.Start start;
        try {
            start = store.start(acting, quoteId, optionId, idempotencyKey, hash, Instant.now());
        } catch (DataIntegrityViolationException raced) {
            // The same key arrived twice at the same moment; the other request created the row.
            start = new PortalBookingStore.Start(store.findByKey(acting, idempotencyKey)
                    .orElseThrow(() -> raced), null, null, false);
        }
        if (!start.created()) {
            if (!start.booking().getRequestHash().equals(hash)) {
                throw new BusinessRuleException(ErrorCode.IDEMPOTENCY_KEY_REUSED, ErrorCode.IDEMPOTENCY_KEY_REUSED.getDefaultMessage());
            }
            return view(start.booking(), null);
        }

        PortalBooking booking = start.booking();
        auditService.record(AuditAction.PORTAL_BOOKING_SUBMITTED, "PortalBooking", booking.getId(),
                Map.of("quoteId", quoteId.toString(), "provider", booking.getProviderCode(),
                        "total", booking.getTotalAmount().toPlainString()));
        CourierProviderAdapter adapter = adapter(booking.getProviderCode());
        // Ledger first, courier second: the money leaves the wallet before anything is promised to the courier, and
        // comes back only if the courier certainly refused. A booking whose outcome is unknown stays paid.
        boolean paidFromWallet = properties.getWalletPayment().covers(booking.getProviderCode())
                && !"TOPAY".equals(start.quote().getPaymentMode()) && !creditCustomer(acting);
        if (paidFromWallet) {
            payFromWallet(acting, booking);
        }
        BookingRequest request = request(acting, booking, start.quote(), start.option(), where);

        BookingOutcome outcome;
        try {
            outcome = adapter.book(request);
        } catch (RuntimeException e) {
            log.error("Booking {} with {} failed unexpectedly", booking.getId(), adapter.code(), e);
            // An external call may have reached the provider; a local one rolled back with nothing created.
            outcome = adapter.external()
                    ? BookingOutcome.unknown(null, "We could not confirm this booking with " + adapter.displayName() + ". Our team will check it.")
                    : BookingOutcome.rejected("INTERNAL_ERROR", "The booking could not be created. Nothing was booked; please try again.");
        }
        if (paidFromWallet && outcome.result() == BookingOutcome.Result.REJECTED) {
            refundToWallet(booking, "Courier did not accept the booking");
        }
        PortalBooking finished = store.finish(acting, booking.getId(), outcome, Instant.now());
        AuditAction action = switch (finished.getStatus()) {
            case BOOKED -> AuditAction.PORTAL_BOOKING_BOOKED;
            case FAILED -> AuditAction.PORTAL_BOOKING_FAILED;
            default -> AuditAction.PORTAL_BOOKING_OUTCOME_UNKNOWN;
        };
        auditService.record(action, "PortalBooking", finished.getId(), Map.of("provider", finished.getProviderCode(),
                "providerRef", String.valueOf(finished.getProviderOrderRef()), "awb", String.valueOf(finished.getAwbNumber())));
        log.info("Portal booking {} -> {} ({})", finished.getId(), finished.getStatus(), finished.getProviderCode());
        if (finished.getStatus() == PortalBooking.Status.BOOKED) {
            events.publishEvent(new com.courier.modules.customerportal.rates.domain.PortalBookingNotice(
                    finished.getId(), acting.companyId(), "BOOKED"));
        }
        return view(finished, outcome.message());
    }

    /**
     * The customer cancels their own booking before the parcel is collected (OCCPL Network). The courier side is cancelled first (the
     * shipment, freeing its number, then the pickup); only when that succeeds is the booking marked cancelled and the wallet debit
     * (if any) credited back. A refusal changes nothing and says why.
     */
    public BookingView cancel(UUID bookingId, String reason) {
        ActingCustomer acting = resolver.require();
        PortalBooking booking = store.findOwned(acting, bookingId)
                .orElseThrow(() -> new com.courier.shared.exception.ResourceNotFoundException("Booking", bookingId));
        if (booking.getStatus() == PortalBooking.Status.CANCELLED) {
            return view(booking, "This booking is already cancelled.");
        }
        if (booking.getStatus() != PortalBooking.Status.BOOKED) {
            throw new BusinessRuleException("Only a confirmed booking can be cancelled.");
        }
        CourierProviderAdapter adapter = adapter(booking.getProviderCode());
        String why = reason == null || reason.isBlank() ? "Cancelled by the customer" : "Cancelled by the customer: " + reason.trim();
        CancelOutcome outcome = adapter.cancelBooking(acting.companyId(), acting.customerId(), booking.getProviderOrderRef(),
                why.length() > 200 ? why.substring(0, 200) : why);
        if (outcome.result() == CancelOutcome.Result.FAILED) {
            throw new BusinessRuleException(outcome.message());
        }
        PortalBooking cancelled = store.markCancelled(acting, bookingId);
        if (!creditCustomer(acting)) {
            refundToWallet(cancelled, "Booking cancelled by the customer");
        }
        auditService.record(AuditAction.PORTAL_BOOKING_CANCELLED, "PortalBooking", cancelled.getId(),
                Map.of("provider", cancelled.getProviderCode(), "providerRef", String.valueOf(cancelled.getProviderOrderRef())));
        log.info("Portal booking {} cancelled by the customer ({})", cancelled.getId(), cancelled.getProviderCode());
        return view(cancelled, "Booking cancelled." + (creditCustomer(acting) ? "" : " The amount was credited back to your wallet."));
    }

    /** The pickup slots the branch network works to (the same ones staff use). */
    public static final List<String> PICKUP_SLOTS = List.of("Morning (9-12)", "Afternoon (12-4)", "Evening (4-8)");
    static final int MAX_DAYS_AHEAD = 14;
    private static final java.time.ZoneId INDIA = java.time.ZoneId.of("Asia/Kolkata");

    /**
     * The customer changes the pickup date and slot of their own booking before the parcel is collected (OCCPL Network). The date is
     * today or later, at most {@value #MAX_DAYS_AHEAD} days ahead; the slot is one of {@link #PICKUP_SLOTS} or empty (any time).
     */
    public BookingView reschedule(UUID bookingId, java.time.LocalDate date, String slot) {
        ActingCustomer acting = resolver.require();
        PortalBooking booking = store.findOwned(acting, bookingId)
                .orElseThrow(() -> new com.courier.shared.exception.ResourceNotFoundException("Booking", bookingId));
        if (booking.getStatus() != PortalBooking.Status.BOOKED) {
            throw new BusinessRuleException("Only a confirmed booking can be rescheduled.");
        }
        java.time.LocalDate today = java.time.LocalDate.now(INDIA);
        if (date == null || date.isBefore(today) || date.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
            throw new BusinessRuleException("Choose a pickup date between today and " + today.plusDays(MAX_DAYS_AHEAD) + ".");
        }
        String cleanSlot = slot == null || slot.isBlank() ? null : slot.trim();
        if (cleanSlot != null && !PICKUP_SLOTS.contains(cleanSlot)) {
            throw new BusinessRuleException("Choose one of the pickup slots: " + String.join(", ", PICKUP_SLOTS) + ".");
        }
        CancelOutcome outcome = adapter(booking.getProviderCode()).reschedulePickup(acting.companyId(), acting.customerId(),
                booking.getProviderOrderRef(), date, cleanSlot);
        if (outcome.result() != CancelOutcome.Result.CANCELLED) {
            throw new BusinessRuleException(outcome.message());
        }
        log.info("Portal booking {} pickup rescheduled to {} {}", booking.getId(), date, cleanSlot);
        return view(booking, "Pickup time changed to " + date + (cleanSlot == null ? "" : ", " + cleanSlot) + ".");
    }

    /**
     * The customer answers a failed delivery attempt on their booking: deliver again on a date, deliver to a new address (and phone), or
     * return it. The branch sees the answer in its queue; a new address is written onto the shipment at once.
     */
    public BookingView chooseDelivery(UUID bookingId, String choice, java.time.LocalDate date, String address, String phone) {
        ActingCustomer acting = resolver.require();
        PortalBooking booking = store.findOwned(acting, bookingId)
                .orElseThrow(() -> new com.courier.shared.exception.ResourceNotFoundException("Booking", bookingId));
        if (booking.getStatus() != PortalBooking.Status.BOOKED) {
            throw new BusinessRuleException("This booking has no delivery to arrange.");
        }
        CancelOutcome outcome = adapter(booking.getProviderCode()).chooseDelivery(acting.companyId(), acting.customerId(),
                booking.getProviderOrderRef(), choice, date, address, phone);
        if (outcome.result() != CancelOutcome.Result.CANCELLED) {
            throw new BusinessRuleException(outcome.message());
        }
        log.info("Portal booking {} delivery choice {}", booking.getId(), choice);
        return view(booking, outcome.message());
    }

    /**
     * A customer set up with the Credit payment mode is billed on their invoice, so a booking neither debits nor credits their
     * wallet, whatever balance it holds.
     */
    private boolean creditCustomer(ActingCustomer acting) {
        return customers.findActiveBusinessCustomer(acting.companyId(), acting.customerId())
                .map(c -> c.getDefaultPaymentMode() == com.courier.modules.customer.domain.CustomerPaymentMode.CREDIT).orElse(false);
    }

    /** Debits the wallet for this booking. A refusal (short balance, suspended wallet) ends the booking as FAILED, unpaid. */
    private void payFromWallet(ActingCustomer acting, PortalBooking booking) {
        try {
            walletService.debitForCustomerBooking(new CustomerBookingDebitCommand(acting.customerId(), booking.getId(),
                    booking.getTotalAmount(), "Courier booking " + booking.getProviderCode()));
        } catch (BusinessRuleException e) {
            PortalBooking failed = store.finish(acting, booking.getId(),
                    BookingOutcome.rejected("WALLET_PAYMENT_REFUSED", e.getMessage()), Instant.now());
            auditService.record(AuditAction.PORTAL_BOOKING_FAILED, "PortalBooking", failed.getId(),
                    Map.of("provider", failed.getProviderCode(), "reason", "WALLET_PAYMENT_REFUSED"));
            throw e;
        }
    }

    /** Credits the debit back. If that fails the booking is still marked FAILED; the money is for operations to return. */
    private void refundToWallet(PortalBooking booking, String why) {
        try {
            walletService.refundCustomerBooking(new CustomerBookingRefundCommand(booking.getId(),
                    why + " — booking " + booking.getId()));
        } catch (RuntimeException e) {
            log.error("Booking {} was refused by {} but its wallet debit could not be refunded; return the money manually",
                    booking.getId(), booking.getProviderCode(), e);
        }
    }

    /** A pin must be a real point in India: a swapped or zero latitude/longitude would send a rider somewhere else. */
    private static void checkPin(Pin pin, String which) {
        if (pin == null) {
            return;
        }
        if (pin.lat() == null || pin.lng() == null
                || pin.lat().compareTo(BigDecimal.valueOf(6)) < 0 || pin.lat().compareTo(BigDecimal.valueOf(37.5)) > 0
                || pin.lng().compareTo(BigDecimal.valueOf(68)) < 0 || pin.lng().compareTo(BigDecimal.valueOf(97.5)) > 0) {
            throw new BusinessRuleException("The " + which + " pin is not in India. Please place it on the map again.");
        }
    }

    private static BookingRequest.Party pinned(BookingRequest.Party p, Pin pin) {
        return pin == null ? p
                : new BookingRequest.Party(p.name(), p.phone(), p.address(), p.pincode(), p.city(), p.state(), pin.lat(), pin.lng());
    }

    private BookingRequest request(ActingCustomer acting, PortalBooking booking, RateQuote q,
                                   com.courier.modules.customerportal.rates.domain.RateQuoteOption option, Pins pins) {
        String serviceCode = option.getServiceCode();
        Map<String, Object> snapshot = q.getRequest();
        @SuppressWarnings("unchecked")
        Map<String, Object> pkg = (Map<String, Object>) snapshot.getOrDefault("package", Map.of());
        // Dimensions are re-read from the snapshot in the unit the customer used, then normalised exactly as the
        // comparison did, so the booking describes the same parcel that was priced.
        RateRequest rate = RateComparisonService.toRequest(acting, new RateComparisonService.CompareCommand(
                party(snapshot.get("pickup")), party(snapshot.get("delivery")),
                new RateComparisonService.PackageInput(num(pkg.get("actualWeightKg")), num(pkg.get("length")),
                        num(pkg.get("breadth")), num(pkg.get("height")), str(pkg.get("dimensionUnit")),
                        q.getPackageCount(), str(pkg.get("contents")), q.getDeclaredValue(), q.getPaymentMode(),
                        num(pkg.get("codAmount")))));
        return new BookingRequest(acting.companyId(), acting.customerId(), booking.getId().toString(), serviceCode, rate,
                pinned(BookingRequest.Party.of(snapshot.get("pickup")), pins.pickup()),
                pinned(BookingRequest.Party.of(snapshot.get("delivery")), pins.delivery()),
                str(pkg.get("contents")), booking.getTotalAmount(),
                RateComparisonService.lines(option).stream().map(l -> new com.courier.modules.customerportal.rates.domain.ChargeLine(
                        l.label(), l.label(), component(l.component()), l.amount())).toList(),
                option.getChargeableWeightKg());
    }

    private static com.courier.modules.customerportal.rates.domain.ChargeComponent component(String name) {
        try {
            return com.courier.modules.customerportal.rates.domain.ChargeComponent.valueOf(name);
        } catch (IllegalArgumentException e) {
            return com.courier.modules.customerportal.rates.domain.ChargeComponent.OTHER;
        }
    }

    private static RateComparisonService.PartyInput party(Object raw) {
        BookingRequest.Party p = BookingRequest.Party.of(raw);
        return new RateComparisonService.PartyInput(p.name(), p.phone(), p.address(), p.pincode(), p.city(), p.state());
    }

    private static BigDecimal num(Object o) {
        return o == null ? null : new BigDecimal(String.valueOf(o));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private CourierProviderAdapter adapter(String code) {
        return adapters.stream().filter(a -> a.code().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown provider " + code));
    }

    private BookingView view(PortalBooking b, String message) {
        String name = adapters.stream().filter(a -> a.code().equals(b.getProviderCode()))
                .map(CourierProviderAdapter::displayName).findFirst().orElse(b.getProviderCode());
        return new BookingView(b.getId(), b.getQuoteId(), b.getQuoteOptionId(), b.getStatus().name(), b.getProviderCode(),
                name, b.getTotalAmount(), b.getCurrency(), b.getProviderOrderRef(), b.getAwbNumber(),
                b.getLabelUrl() != null, message != null ? message : b.getFailureMessage(), b.getSubmittedAt(), b.getBookedAt());
    }

    static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** labelUrl itself is never sent to the browser: labels are downloaded through the portal ({@link PortalShipmentService}). */
    public record BookingView(UUID bookingId, UUID quoteId, UUID optionId, String status, String providerCode,
                              String providerName, BigDecimal totalAmount, String currency, String bookingReference,
                              String trackingNumber, boolean labelAvailable, String message, Instant submittedAt,
                              Instant bookedAt) {
    }
}
