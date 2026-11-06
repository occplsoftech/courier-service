package com.courier.modules.customerportal.rates.application;

import com.courier.modules.customerportal.application.ActingCustomer;
import com.courier.modules.customerportal.rates.domain.BookingOutcome;
import com.courier.modules.customerportal.rates.domain.DeliveryStage;
import com.courier.modules.customerportal.rates.domain.PortalBooking;
import com.courier.modules.customerportal.rates.domain.PortalBookingRepository;
import com.courier.modules.customerportal.rates.domain.RateQuote;
import com.courier.modules.customerportal.rates.domain.RateQuoteOption;
import com.courier.modules.customerportal.rates.domain.RateQuoteOptionRepository;
import com.courier.modules.customerportal.rates.domain.RateQuoteRepository;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ErrorCode;
import com.courier.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.UUID;

/**
 * The two short transactions around a booking: {@link #start} (checks + SUBMITTING row, quote row-locked so
 * two bookings of one quote cannot both pass) and {@link #finish} (record the provider's outcome). The provider
 * call happens between them, outside any transaction.
 */
@Component
@RequiredArgsConstructor
public class PortalBookingStore {

    /** The background tracking sync first asks the courier this long after booking. */
    static final java.time.Duration FIRST_TRACKING_SYNC_AFTER = java.time.Duration.ofMinutes(10);

    private final PortalBookingRepository bookings;
    private final RateQuoteRepository quotes;
    private final RateQuoteOptionRepository options;

    @Transactional
    public Start start(ActingCustomer acting, UUID quoteId, UUID optionId, String key, String hash, Instant now) {
        Optional<PortalBooking> existing = bookings.findByKey(acting.companyId(), acting.customerId(), key);
        if (existing.isPresent()) {
            return new Start(existing.get(), null, null, false);
        }
        RateQuote quote = quotes.findOwnedForUpdate(quoteId, acting.companyId(), acting.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Quote", quoteId));
        RateQuoteOption option = options.findByQuote(quoteId, acting.companyId()).stream()
                .filter(o -> o.getId().equals(optionId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Quote option", optionId));
        if (!option.isAvailable()) {
            throw new BusinessRuleException("That courier service cannot be booked.");
        }
        if (!quote.getExpiresAt().isAfter(now) || (option.getValidUntil() != null && !option.getValidUntil().isAfter(now))) {
            throw new BusinessRuleException(ErrorCode.QUOTE_EXPIRED, ErrorCode.QUOTE_EXPIRED.getDefaultMessage());
        }
        if (bookings.existsForQuote(quoteId, acting.companyId(), PortalBooking.Status.ACTIVE)) {
            throw new BusinessRuleException(ErrorCode.QUOTE_ALREADY_BOOKED, ErrorCode.QUOTE_ALREADY_BOOKED.getDefaultMessage());
        }
        PortalBooking booking = bookings.saveAndFlush(PortalBooking.builder()
                .customerId(acting.customerId())
                .quoteId(quoteId)
                .quoteOptionId(optionId)
                .idempotencyKey(key)
                .requestHash(hash)
                .providerCode(option.getProviderCode())
                .status(PortalBooking.Status.SUBMITTING)
                .totalAmount(option.getTotalAmount())
                .currency(option.getCurrency())
                .submittedAt(now)
                .build());
        return new Start(booking, quote, option, true);
    }

    @Transactional
    public PortalBooking finish(ActingCustomer acting, UUID bookingId, BookingOutcome outcome, Instant now) {
        PortalBooking b = bookings.findOwned(bookingId, acting.companyId(), acting.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingId));
        b.setProviderOrderRef(trim(outcome.providerOrderRef(), 120));
        b.setAwbNumber(trim(outcome.awbNumber(), 60));
        b.setLabelUrl(trim(outcome.labelUrl(), 1024));
        switch (outcome.result()) {
            case BOOKED -> {
                b.setStatus(PortalBooking.Status.BOOKED);
                b.setBookedAt(now);
                b.setNextTrackingSyncAt(now.plus(FIRST_TRACKING_SYNC_AFTER));
                quotes.findOwned(b.getQuoteId(), acting.companyId(), acting.customerId()).ifPresent(q -> q.setStatus("BOOKED"));
            }
            case REJECTED -> {
                b.setStatus(PortalBooking.Status.FAILED);
                b.setFailureCode(trim(outcome.failureCode(), 60));
                b.setFailureMessage(trim(outcome.message(), 500));
            }
            case UNKNOWN -> {
                b.setStatus(PortalBooking.Status.OUTCOME_UNKNOWN);
                b.setFailureCode(trim(outcome.failureCode(), 60));
                b.setFailureMessage(trim(outcome.message(), 500));
            }
        }
        return bookings.save(b);
    }

    /** The customer cancelled a booked shipment before it was collected: no further tracking checks. */
    @Transactional
    public PortalBooking markCancelled(ActingCustomer acting, UUID bookingId) {
        PortalBooking b = bookings.findOwned(bookingId, acting.companyId(), acting.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingId));
        b.setStatus(PortalBooking.Status.CANCELLED);
        b.setNextTrackingSyncAt(null);
        return bookings.save(b);
    }

    @Transactional(readOnly = true)
    public Optional<PortalBooking> findByKey(ActingCustomer acting, String key) {
        return bookings.findByKey(acting.companyId(), acting.customerId(), key);
    }

    @Transactional(readOnly = true)
    public Optional<PortalBooking> findOwned(ActingCustomer acting, UUID id) {
        return bookings.findOwned(id, acting.companyId(), acting.customerId());
    }

    /** One page of the customer's bookings matching {@code filter}, with the quote and the option each was booked from. */
    @Transactional(readOnly = true)
    public Page<Row> page(ActingCustomer acting, HistoryFilter filter, Pageable pageable) {
        boolean anyStage = filter.stages() == null;
        Page<PortalBooking> page = bookings.findPage(acting.companyId(), acting.customerId(), filter.statuses(),
                filter.providerCode() == null ? "" : filter.providerCode(), filter.from(), filter.to(), anyStage,
                anyStage ? java.util.EnumSet.allOf(DeliveryStage.class) : filter.stages(), filter.includeUnchecked(), pageable);
        Set<UUID> quoteIds = page.stream().map(PortalBooking::getQuoteId).collect(Collectors.toSet());
        Set<UUID> optionIds = page.stream().map(PortalBooking::getQuoteOptionId).collect(Collectors.toSet());
        Map<UUID, RateQuote> quoteById = quoteIds.isEmpty() ? Map.of()
                : quotes.findAllOwned(quoteIds, acting.companyId(), acting.customerId()).stream()
                        .collect(Collectors.toMap(RateQuote::getId, Function.identity()));
        Map<UUID, RateQuoteOption> optionById = optionIds.isEmpty() ? Map.of()
                : options.findAllWithin(optionIds, acting.companyId()).stream()
                        .collect(Collectors.toMap(RateQuoteOption::getId, Function.identity()));
        return page.map(b -> new Row(b, quoteById.get(b.getQuoteId()), optionById.get(b.getQuoteOptionId())));
    }

    @Transactional(readOnly = true)
    public List<String> providerCodes(ActingCustomer acting) {
        return bookings.findProviderCodes(acting.companyId(), acting.customerId());
    }

    /**
     * History filter: these statuses, one provider (null for all), booked within [from, to), these delivery stages (null
     * for any) — {@code includeUnchecked} also lets through booked shipments whose delivery status is not known yet.
     */
    public record HistoryFilter(Collection<PortalBooking.Status> statuses, String providerCode, Instant from, Instant to,
                                Collection<DeliveryStage> stages, boolean includeUnchecked) {

        public HistoryFilter(Collection<PortalBooking.Status> statuses, String providerCode, Instant from, Instant to) {
            this(statuses, providerCode, from, to, null, false);
        }
    }

    /** One of the customer's bookings with its quote and option; empty for anyone else's. */
    @Transactional(readOnly = true)
    public Optional<Row> row(ActingCustomer acting, UUID id) {
        return bookings.findOwned(id, acting.companyId(), acting.customerId()).map(b -> new Row(b,
                quotes.findOwned(b.getQuoteId(), acting.companyId(), acting.customerId()).orElse(null),
                options.findByQuote(b.getQuoteId(), acting.companyId()).stream()
                        .filter(o -> o.getId().equals(b.getQuoteOptionId())).findFirst().orElse(null)));
    }

    /** Remembers where the provider keeps the label, or forgets it (null) once the link stopped working. */
    @Transactional
    public void recordLabelUrl(ActingCustomer acting, UUID id, String url) {
        bookings.findOwned(id, acting.companyId(), acting.customerId()).ifPresent(b -> {
            b.setLabelUrl(trim(url, 1024));
            bookings.save(b);
        });
    }

    /** Fills in the tracking number a provider issued after booking (OCCPL: when the branch books the parcel). */
    @Transactional
    public void recordTrackingNumber(ActingCustomer acting, UUID id, String awb) {
        bookings.findOwned(id, acting.companyId(), acting.customerId())
                .filter(b -> b.getAwbNumber() == null && awb != null && !awb.isBlank())
                .ifPresent(b -> {
                    b.setAwbNumber(trim(awb, 60));
                    bookings.save(b);
                });
    }

    public record Row(PortalBooking booking, RateQuote quote, RateQuoteOption option) {
    }

    private static String trim(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max));
    }

    /** {@code created=false}: the key was already used — {@code booking} is that earlier booking. */
    public record Start(PortalBooking booking, RateQuote quote, RateQuoteOption option, boolean created) {
    }
}
