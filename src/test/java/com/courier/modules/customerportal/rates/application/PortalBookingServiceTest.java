package com.courier.modules.customerportal.rates.application;

import com.courier.modules.customerportal.application.ActingCustomer;
import com.courier.modules.customerportal.application.CurrentCustomerResolver;
import com.courier.modules.customerportal.rates.domain.BookingOutcome;
import com.courier.modules.customerportal.rates.domain.BookingRequest;
import com.courier.modules.customerportal.rates.domain.CourierProviderAdapter;
import com.courier.modules.customerportal.rates.domain.PortalBooking;
import com.courier.modules.customerportal.rates.domain.ProviderResult;
import com.courier.modules.customerportal.rates.domain.RateQuote;
import com.courier.modules.customerportal.rates.domain.RateQuoteOption;
import com.courier.modules.customerportal.rates.domain.RateRequest;
import com.courier.modules.finance.application.WalletService;
import com.courier.modules.finance.application.command.CustomerBookingDebitCommand;
import com.courier.modules.finance.application.command.CustomerBookingRefundCommand;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.exception.ApiException;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PortalBookingServiceTest {

    private static final ActingCustomer ACTING = new ActingCustomer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private static final String KEY = "confirm-1234567890-abcdef";

    private final CurrentCustomerResolver resolver = mock(CurrentCustomerResolver.class);
    private final PortalBookingStore store = mock(PortalBookingStore.class);
    private final UUID quoteId = UUID.randomUUID();
    private final UUID optionId = UUID.randomUUID();
    private final AtomicInteger calls = new AtomicInteger();
    private final WalletService walletService = mock(WalletService.class);
    private final CustomerPortalProperties properties = new CustomerPortalProperties();

    @BeforeEach
    void setUp() {
        when(resolver.require()).thenReturn(ACTING);
    }

    private static CourierProviderAdapter adapter(boolean external, Function<BookingRequest, BookingOutcome> book) {
        return new CourierProviderAdapter() {
            @Override public String code() { return "SHIPROCKET"; }
            @Override public String displayName() { return "Shiprocket"; }
            @Override public boolean external() { return external; }
            @Override public boolean configured() { return true; }
            @Override public ProviderResult getRates(RateRequest r) { return null; }
            @Override public BookingOutcome book(BookingRequest r) { return book.apply(r); }
        };
    }

    private PortalBookingService service(CourierProviderAdapter adapter) {
        return new PortalBookingService(resolver, store, List.of(adapter), mock(AuditService.class), walletService, properties);
    }

    private PortalBooking booking(PortalBooking.Status status, String hash) {
        PortalBooking b = PortalBooking.builder().customerId(ACTING.customerId()).quoteId(quoteId).quoteOptionId(optionId)
                .idempotencyKey(KEY).requestHash(hash).providerCode("SHIPROCKET").status(status)
                .totalAmount(new BigDecimal("111.80")).currency("INR").build();
        b.setId(UUID.randomUUID());
        return b;
    }

    private RateQuote quote() {
        Map<String, Object> party = new LinkedHashMap<>(Map.of("name", "Asha", "phone", "9876543210",
                "address", "Office 4, Thorat Chowk", "pincode", "411016", "city", "Pune", "state", "Maharashtra"));
        Map<String, Object> pkg = new LinkedHashMap<>(Map.of("actualWeightKg", "2.5", "length", "10", "breadth", "10",
                "height", "10", "dimensionUnit", "IN", "contents", "Garments"));
        return RateQuote.builder().customerId(ACTING.customerId()).request(Map.of("pickup", party, "delivery", party, "package", pkg))
                .packageCount(1).declaredValue(new BigDecimal("1500")).paymentMode("PREPAID").expiresAt(Instant.now().plusSeconds(600))
                .build();
    }

    private void startsNew(PortalBooking b) {
        RateQuoteOption option = RateQuoteOption.builder().serviceCode("42").availability("AVAILABLE").build();
        when(store.start(eq(ACTING), eq(quoteId), eq(optionId), eq(KEY), any(), any()))
                .thenReturn(new PortalBookingStore.Start(b, quote(), option, true));
    }

    @Test
    @DisplayName("books once and records the provider's reference and tracking number")
    void books() {
        PortalBooking b = booking(PortalBooking.Status.SUBMITTING, PortalBookingService.hash(quoteId + ":" + optionId));
        startsNew(b);
        when(store.finish(eq(ACTING), eq(b.getId()), any(), any())).thenAnswer(i -> {
            BookingOutcome o = i.getArgument(2);
            b.setStatus(PortalBooking.Status.BOOKED);
            b.setAwbNumber(o.awbNumber());
            return b;
        });

        var view = service(adapter(true, r -> {
            calls.incrementAndGet();
            assertThat(r.reference()).isEqualTo(b.getId().toString());
            assertThat(r.rate().lengthCm()).isEqualByComparingTo("25.4"); // same normalisation as the comparison
            assertThat(r.customerTotal()).isEqualByComparingTo("111.80");
            return BookingOutcome.booked("SR-1", "AWB123", null, "ok");
        })).book(quoteId, optionId, KEY);

        assertThat(view.status()).isEqualTo("BOOKED");
        assertThat(view.trackingNumber()).isEqualTo("AWB123");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a repeat with the same key returns the first booking and never calls the provider again")
    void repeatSameKey() {
        PortalBooking existing = booking(PortalBooking.Status.BOOKED, PortalBookingService.hash(quoteId + ":" + optionId));
        when(store.start(any(), any(), any(), any(), any(), any())).thenReturn(new PortalBookingStore.Start(existing, null, null, false));

        var view = service(adapter(true, r -> { throw new AssertionError("provider called twice"); })).book(quoteId, optionId, KEY);

        assertThat(view.bookingId()).isEqualTo(existing.getId());
        verify(store, never()).finish(any(), any(), any(), any());
    }

    @Test
    @DisplayName("the same key for a different option is refused")
    void keyReused() {
        PortalBooking existing = booking(PortalBooking.Status.BOOKED, PortalBookingService.hash("other"));
        when(store.start(any(), any(), any(), any(), any(), any())).thenReturn(new PortalBookingStore.Start(existing, null, null, false));

        assertThatThrownBy(() -> service(adapter(true, r -> null)).book(quoteId, optionId, KEY))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
    }

    @Test
    @DisplayName("an unexpected failure talking to an external courier is OUTCOME_UNKNOWN, never FAILED")
    void unexpectedExternalFailure() {
        PortalBooking b = booking(PortalBooking.Status.SUBMITTING, PortalBookingService.hash(quoteId + ":" + optionId));
        startsNew(b);
        when(store.finish(eq(ACTING), eq(b.getId()), any(), any())).thenAnswer(i -> {
            BookingOutcome o = i.getArgument(2);
            b.setStatus(o.result() == BookingOutcome.Result.UNKNOWN ? PortalBooking.Status.OUTCOME_UNKNOWN : PortalBooking.Status.FAILED);
            return b;
        });

        var view = service(adapter(true, r -> { throw new IllegalStateException("socket closed"); })).book(quoteId, optionId, KEY);

        assertThat(view.status()).isEqualTo("OUTCOME_UNKNOWN");
    }

    @Test
    @DisplayName("a missing or weak Idempotency-Key is refused before anything is stored")
    void keyRequired() {
        PortalBookingService s = service(adapter(true, r -> null));
        assertThatThrownBy(() -> s.book(quoteId, optionId, null)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> s.book(quoteId, optionId, "short")).isInstanceOf(BusinessRuleException.class);
        verify(store, never()).start(any(), any(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------ paying from the customer's wallet

    private PortalBooking submitting() {
        PortalBooking b = booking(PortalBooking.Status.SUBMITTING, PortalBookingService.hash(quoteId + ":" + optionId));
        startsNew(b);
        when(store.finish(eq(ACTING), eq(b.getId()), any(), any())).thenAnswer(i -> {
            BookingOutcome o = i.getArgument(2);
            b.setStatus(switch (o.result()) {
                case BOOKED -> PortalBooking.Status.BOOKED;
                case REJECTED -> PortalBooking.Status.FAILED;
                case UNKNOWN -> PortalBooking.Status.OUTCOME_UNKNOWN;
            });
            return b;
        });
        return b;
    }

    @Test
    @DisplayName("wallet payment off (the default): the wallet is never touched")
    void walletOffByDefault() {
        submitting();

        service(adapter(true, r -> BookingOutcome.booked("SR-1", "AWB1", null, "ok"))).book(quoteId, optionId, KEY);

        verify(walletService, never()).debitForCustomerBooking(any());
        verify(walletService, never()).refundCustomerBooking(any());
    }

    @Test
    @DisplayName("on: the wallet is debited the booking's frozen total before the courier is asked, and a booking stays paid")
    void debitsBeforeCourier() {
        properties.getWalletPayment().setEnabled(true);
        PortalBooking b = submitting();
        java.util.List<String> order = new java.util.ArrayList<>();
        when(walletService.debitForCustomerBooking(any())).thenAnswer(i -> {
            order.add("debit");
            return null;
        });

        var view = service(adapter(true, r -> {
            order.add("courier");
            return BookingOutcome.booked("SR-1", "AWB1", null, "ok");
        })).book(quoteId, optionId, KEY);

        assertThat(order).containsExactly("debit", "courier");
        assertThat(view.status()).isEqualTo("BOOKED");
        verify(walletService).debitForCustomerBooking(org.mockito.ArgumentMatchers.argThat(
                (CustomerBookingDebitCommand c) -> c.customerId().equals(ACTING.customerId())
                        && c.bookingId().equals(b.getId()) && c.amount().compareTo(new BigDecimal("111.80")) == 0));
        verify(walletService, never()).refundCustomerBooking(any());
    }

    @Test
    @DisplayName("a short balance ends the booking FAILED, unpaid, and the courier is never called")
    void insufficientBalance() {
        properties.getWalletPayment().setEnabled(true);
        PortalBooking b = submitting();
        when(walletService.debitForCustomerBooking(any()))
                .thenThrow(new BusinessRuleException("Insufficient wallet balance. Available INR 10.00, required INR 111.80."));

        assertThatThrownBy(() -> service(adapter(true, r -> {
            throw new AssertionError("courier called without payment");
        })).book(quoteId, optionId, KEY))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Insufficient wallet balance");

        verify(store).finish(eq(ACTING), eq(b.getId()), org.mockito.ArgumentMatchers.argThat(
                (BookingOutcome o) -> o.result() == BookingOutcome.Result.REJECTED
                        && "WALLET_PAYMENT_REFUSED".equals(o.failureCode())), any());
        verify(walletService, never()).refundCustomerBooking(any());
    }

    @Test
    @DisplayName("a courier that certainly refused gives the money back")
    void refusedIsRefunded() {
        properties.getWalletPayment().setEnabled(true);
        PortalBooking b = submitting();

        var view = service(adapter(true, r -> BookingOutcome.rejected("BAD_PINCODE", "Pincode not serviceable")))
                .book(quoteId, optionId, KEY);

        assertThat(view.status()).isEqualTo("FAILED");
        verify(walletService).refundCustomerBooking(org.mockito.ArgumentMatchers.argThat(
                (CustomerBookingRefundCommand c) -> c.bookingId().equals(b.getId())));
    }

    @Test
    @DisplayName("an unknown outcome keeps the money taken: the courier may have accepted the booking")
    void unknownIsNotRefunded() {
        properties.getWalletPayment().setEnabled(true);
        submitting();

        var view = service(adapter(true, r -> { throw new IllegalStateException("socket closed"); }))
                .book(quoteId, optionId, KEY);

        assertThat(view.status()).isEqualTo("OUTCOME_UNKNOWN");
        verify(walletService).debitForCustomerBooking(any());
        verify(walletService, never()).refundCustomerBooking(any());
    }

    @Test
    @DisplayName("a local failure with nothing created (non-external adapter) is refunded")
    void localFailureIsRefunded() {
        properties.getWalletPayment().setEnabled(true);
        submitting();

        var view = service(adapter(false, r -> { throw new IllegalStateException("rolled back"); }))
                .book(quoteId, optionId, KEY);

        assertThat(view.status()).isEqualTo("FAILED");
        verify(walletService).refundCustomerBooking(any());
    }

    @Test
    @DisplayName("a refund that fails still ends the booking FAILED")
    void refundFailureStillFinishes() {
        properties.getWalletPayment().setEnabled(true);
        PortalBooking b = submitting();
        when(walletService.refundCustomerBooking(any())).thenThrow(new IllegalStateException("db down"));

        var view = service(adapter(true, r -> BookingOutcome.rejected("X", "no"))).book(quoteId, optionId, KEY);

        assertThat(view.status()).isEqualTo("FAILED");
        verify(store).finish(eq(ACTING), eq(b.getId()), any(), any());
    }

    @Test
    @DisplayName("OCCPL's own network is excluded: the branch bills it, so the wallet is not debited")
    void occplNetworkNotDebited() {
        properties.getWalletPayment().setEnabled(true);
        PortalBooking b = submitting();
        CourierProviderAdapter occpl = new CourierProviderAdapter() {
            @Override public String code() { return "OCCPL_NETWORK"; }
            @Override public String displayName() { return "OCCPL Network"; }
            @Override public boolean external() { return false; }
            @Override public boolean configured() { return true; }
            @Override public ProviderResult getRates(RateRequest r) { return null; }
            @Override public BookingOutcome book(BookingRequest r) { return BookingOutcome.booked("PU-1", null, null, "ok"); }
        };
        b.setProviderCode("OCCPL_NETWORK");

        service(occpl).book(quoteId, optionId, KEY);

        verify(walletService, never()).debitForCustomerBooking(any());
    }
}
