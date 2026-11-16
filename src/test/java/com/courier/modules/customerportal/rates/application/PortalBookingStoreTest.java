package com.courier.modules.customerportal.rates.application;

import com.courier.modules.customerportal.application.ActingCustomer;
import com.courier.modules.customerportal.rates.domain.BookingOutcome;
import com.courier.modules.customerportal.rates.domain.PortalBooking;
import com.courier.modules.customerportal.rates.domain.PortalBookingRepository;
import com.courier.modules.customerportal.rates.domain.RateQuote;
import com.courier.modules.customerportal.rates.domain.RateQuoteOption;
import com.courier.modules.customerportal.rates.domain.RateQuoteOptionRepository;
import com.courier.modules.customerportal.rates.domain.RateQuoteRepository;
import com.courier.shared.exception.ApiException;
import com.courier.shared.exception.ErrorCode;
import com.courier.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PortalBookingStoreTest {

    private static final ActingCustomer ACTING = new ActingCustomer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private final PortalBookingRepository bookings = mock(PortalBookingRepository.class);
    private final RateQuoteRepository quotes = mock(RateQuoteRepository.class);
    private final RateQuoteOptionRepository options = mock(RateQuoteOptionRepository.class);
    private final PortalBookingStore store = new PortalBookingStore(bookings, quotes, options);
    private final UUID quoteId = UUID.randomUUID();
    private final UUID optionId = UUID.randomUUID();
    private final Instant now = Instant.now();

    @BeforeEach
    void setUp() {
        when(bookings.findByKey(any(), any(), any())).thenReturn(Optional.empty());
        when(bookings.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    }

    private void quote(Instant expires, String availability) {
        RateQuote q = RateQuote.builder().customerId(ACTING.customerId()).expiresAt(expires).status("OPEN").build();
        q.setId(quoteId);
        RateQuoteOption o = RateQuoteOption.builder().quoteId(quoteId).providerCode("SHIPROCKET").availability(availability)
                .totalAmount(new BigDecimal("100.62")).currency("INR").build();
        o.setId(optionId);
        when(quotes.findOwnedForUpdate(quoteId, ACTING.companyId(), ACTING.customerId())).thenReturn(Optional.of(q));
        when(options.findByQuote(quoteId, ACTING.companyId())).thenReturn(List.of(o));
    }

    private static ErrorCode code(Throwable e) {
        return ((ApiException) e).getErrorCode();
    }

    @Test
    @DisplayName("opens a SUBMITTING booking carrying the frozen amount of the chosen option")
    void starts() {
        quote(now.plusSeconds(600), "AVAILABLE");

        var start = store.start(ACTING, quoteId, optionId, "k", "h", now);

        assertThat(start.created()).isTrue();
        assertThat(start.booking().getStatus()).isEqualTo(PortalBooking.Status.SUBMITTING);
        assertThat(start.booking().getTotalAmount()).isEqualByComparingTo("100.62");
    }

    @Test
    @DisplayName("a refused last check (13.1) creates no booking")
    void refusedCreatesNothing() {
        quote(now.plusSeconds(600), "AVAILABLE");

        assertThatThrownBy(() -> store.start(ACTING, quoteId, optionId, "k", "h", now, (q, o) -> {
            throw new com.courier.shared.exception.BusinessRuleException("Shiprocket is no longer available to you.");
        })).hasMessageContaining("no longer available");
        org.mockito.Mockito.verify(bookings, org.mockito.Mockito.never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("an expired quote must be refreshed")
    void expired() {
        quote(now.minusSeconds(1), "AVAILABLE");
        assertThatThrownBy(() -> store.start(ACTING, quoteId, optionId, "k", "h", now))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.QUOTE_EXPIRED));
    }

    @Test
    @DisplayName("a quote with an active booking cannot be booked again under another key")
    void alreadyBooked() {
        quote(now.plusSeconds(600), "AVAILABLE");
        when(bookings.existsForQuote(eq(quoteId), eq(ACTING.companyId()), any())).thenReturn(true);
        assertThatThrownBy(() -> store.start(ACTING, quoteId, optionId, "k2", "h", now))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.QUOTE_ALREADY_BOOKED));
        verify(bookings, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("someone else's quote is not found")
    void notOwned() {
        when(quotes.findOwnedForUpdate(any(), any(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> store.start(ACTING, quoteId, optionId, "k", "h", now)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("an unavailable option is not bookable")
    void unavailableOption() {
        quote(now.plusSeconds(600), "UNAVAILABLE");
        assertThatThrownBy(() -> store.start(ACTING, quoteId, optionId, "k", "h", now)).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("finish maps the outcome; a booked one closes the quote")
    void finish() {
        RateQuote q = RateQuote.builder().status("OPEN").build();
        PortalBooking b = PortalBooking.builder().quoteId(quoteId).status(PortalBooking.Status.SUBMITTING).build();
        b.setId(UUID.randomUUID());
        when(bookings.findOwned(b.getId(), ACTING.companyId(), ACTING.customerId())).thenReturn(Optional.of(b));
        when(quotes.findOwned(quoteId, ACTING.companyId(), ACTING.customerId())).thenReturn(Optional.of(q));
        when(bookings.save(any())).thenAnswer(i -> i.getArgument(0));

        PortalBooking done = store.finish(ACTING, b.getId(), BookingOutcome.booked("P-1", "AWB", null, "ok"), now);

        assertThat(done.getStatus()).isEqualTo(PortalBooking.Status.BOOKED);
        assertThat(done.getAwbNumber()).isEqualTo("AWB");
        assertThat(q.getStatus()).isEqualTo("BOOKED");
    }
}
