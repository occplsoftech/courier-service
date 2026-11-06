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
    private final com.courier.modules.customer.application.CustomerPortalLookup customers = mock(com.courier.modules.customer.application.CustomerPortalLookup.class);
    private String quotedPaymentMode = "PREPAID";

    private void customerPays(com.courier.modules.customer.domain.CustomerPaymentMode mode) {
        com.courier.modules.customer.domain.Customer customer = mock(com.courier.modules.customer.domain.Customer.class);
        when(customer.getDefaultPaymentMode()).thenReturn(mode);
        when(customers.findActiveBusinessCustomer(ACTING.companyId(), ACTING.customerId())).thenReturn(java.util.Optional.of(customer));
    }

    @BeforeEach
    void setUp() {
        when(resolver.require()).thenReturn(ACTING);
        customerPays(com.courier.modules.customer.domain.CustomerPaymentMode.PAID);
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
        return new PortalBookingService(resolver, store, List.of(adapter), mock(AuditService.class), walletService, properties, customers);
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
                .packageCount(1).declaredValue(new BigDecimal("1500")).paymentMode(quotedPaymentMode).expiresAt(Instant.now().plusSeconds(600))
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
    @DisplayName("map pins placed at booking reach the courier on the pickup and delivery; a pin outside India is refused")
    void pins() {
        PortalBooking b = booking(PortalBooking.Status.SUBMITTING, PortalBookingService.hash(quoteId + ":" + optionId));
        startsNew(b);
        when(store.finish(eq(ACTING), eq(b.getId()), any(), any())).thenReturn(b);
        var pins = new PortalBookingService.Pins(new PortalBookingService.Pin(new BigDecimal("18.5204"), new BigDecimal("73.8567")), null);

        service(adapter(true, r -> {
            calls.incrementAndGet();
            assertThat(r.pickup().lat()).isEqualByComparingTo("18.5204");
            assertThat(r.pickup().lng()).isEqualByComparingTo("73.8567");
            assertThat(r.pickup().address()).isEqualTo("Office 4, Thorat Chowk");
            assertThat(r.delivery().pinned()).isFalse();
            return BookingOutcome.booked("CRN1", "CRN1", null, "ok");
        })).book(quoteId, optionId, KEY, pins);
        assertThat(calls.get()).isEqualTo(1);

        var swapped = new PortalBookingService.Pins(new PortalBookingService.Pin(new BigDecimal("73.8567"), new BigDecimal("18.5204")), null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service(adapter(true, r -> {
                    throw new AssertionError("courier must not be asked");
                })).book(quoteId, optionId, KEY, swapped))
                .isInstanceOf(com.courier.shared.exception.BusinessRuleException.class)
                .hasMessageContaining("not in India");
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

    private CourierProviderAdapter occplNetwork() {
        return new CourierProviderAdapter() {
            @Override public String code() { return "OCCPL_NETWORK"; }
            @Override public String displayName() { return "OCCPL Network"; }
            @Override public boolean external() { return false; }
            @Override public boolean configured() { return true; }
            @Override public ProviderResult getRates(RateRequest r) { return null; }
            @Override public BookingOutcome book(BookingRequest r) { return BookingOutcome.booked("PU-1", null, null, "ok"); }
        };
    }

    @Test
    @DisplayName("OCCPL's own network is debited from the wallet at booking like any other courier")
    void occplNetworkDebited() {
        properties.getWalletPayment().setEnabled(true);
        PortalBooking b = submitting();
        b.setProviderCode("OCCPL_NETWORK");

        service(occplNetwork()).book(quoteId, optionId, KEY);

        verify(walletService).debitForCustomerBooking(any());
    }

    @Test
    @DisplayName("To Pay: the receiver pays the freight, so nothing is taken from the wallet")
    void topayNotDebited() {
        properties.getWalletPayment().setEnabled(true);
        quotedPaymentMode = "TOPAY";
        PortalBooking b = submitting();
        b.setProviderCode("OCCPL_NETWORK");

        var view = service(occplNetwork()).book(quoteId, optionId, KEY);

        assertThat(view.status()).isEqualTo("BOOKED");
        verify(walletService, never()).debitForCustomerBooking(any());
    }

    @Test
    @DisplayName("a credit customer is billed on their invoice: no wallet debit, whatever the balance")
    void creditCustomerNotDebited() {
        properties.getWalletPayment().setEnabled(true);
        customerPays(com.courier.modules.customer.domain.CustomerPaymentMode.CREDIT);
        PortalBooking b = submitting();
        b.setProviderCode("OCCPL_NETWORK");

        var view = service(occplNetwork()).book(quoteId, optionId, KEY);

        assertThat(view.status()).isEqualTo("BOOKED");
        verify(walletService, never()).debitForCustomerBooking(any());
        verify(walletService, never()).refundCustomerBooking(any());
    }

    // ---------------------------------------------------------------- cancel by the customer

    private CourierProviderAdapter cancellingAdapter(com.courier.modules.customerportal.rates.domain.CancelOutcome outcome) {
        CourierProviderAdapter a = mock(CourierProviderAdapter.class);
        when(a.code()).thenReturn("SHIPROCKET");
        when(a.displayName()).thenReturn("Shiprocket");
        when(a.cancelBooking(any(), any(), any(), any())).thenReturn(outcome);
        return a;
    }

    private PortalBooking bookedForCancel() {
        PortalBooking b = booking(PortalBooking.Status.BOOKED, "h");
        b.setProviderOrderRef("PU-1");
        when(store.findOwned(ACTING, b.getId())).thenReturn(java.util.Optional.of(b));
        when(store.markCancelled(ACTING, b.getId())).thenAnswer(i -> { b.setStatus(PortalBooking.Status.CANCELLED); return b; });
        return b;
    }

    @Test
    @DisplayName("cancel: the courier cancels first, then the booking is marked cancelled and the wallet credited back")
    void cancelRefunds() {
        PortalBooking b = bookedForCancel();
        var view = service(cancellingAdapter(com.courier.modules.customerportal.rates.domain.CancelOutcome.cancelled("ok")))
                .cancel(b.getId(), "wrong address");

        assertThat(view.status()).isEqualTo("CANCELLED");
        verify(walletService).refundCustomerBooking(any(CustomerBookingRefundCommand.class));
    }

    @Test
    @DisplayName("cancel: a refusal (parcel already collected) changes nothing and refunds nothing")
    void cancelRefusedKeepsEverything() {
        PortalBooking b = bookedForCancel();
        var svc = service(cancellingAdapter(com.courier.modules.customerportal.rates.domain.CancelOutcome.failed("already collected")));

        assertThatThrownBy(() -> svc.cancel(b.getId(), null)).isInstanceOf(BusinessRuleException.class).hasMessageContaining("already collected");
        verify(store, never()).markCancelled(any(), any());
        verify(walletService, never()).refundCustomerBooking(any());
    }

    @Test
    @DisplayName("cancel: a credit customer is not refunded to a wallet they never paid from")
    void cancelCreditCustomerNoRefund() {
        customerPays(com.courier.modules.customer.domain.CustomerPaymentMode.CREDIT);
        PortalBooking b = bookedForCancel();

        service(cancellingAdapter(com.courier.modules.customerportal.rates.domain.CancelOutcome.cancelled("ok"))).cancel(b.getId(), null);

        verify(walletService, never()).refundCustomerBooking(any());
    }

    // ---------------------------------------------------------------- reschedule by the customer

    private CourierProviderAdapter reschedulingAdapter(com.courier.modules.customerportal.rates.domain.CancelOutcome outcome) {
        CourierProviderAdapter a = mock(CourierProviderAdapter.class);
        when(a.code()).thenReturn("SHIPROCKET");
        when(a.displayName()).thenReturn("Shiprocket");
        when(a.reschedulePickup(any(), any(), any(), any(), any())).thenReturn(outcome);
        return a;
    }

    private static java.time.LocalDate today() {
        return java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
    }

    @Test
    @DisplayName("reschedule: a valid date and slot are passed to the pickup")
    void reschedules() {
        PortalBooking b = bookedForCancel();
        CourierProviderAdapter a = reschedulingAdapter(com.courier.modules.customerportal.rates.domain.CancelOutcome.cancelled("ok"));

        var view = service(a).reschedule(b.getId(), today().plusDays(2), " Morning (9-12) ");

        assertThat(view.message()).contains("Morning (9-12)");
        verify(a).reschedulePickup(ACTING.companyId(), ACTING.customerId(), "PU-1", today().plusDays(2), "Morning (9-12)");
    }

    @Test
    @DisplayName("reschedule: a past date, a date too far ahead and an unknown slot are refused before the pickup is touched")
    void rescheduleValidation() {
        PortalBooking b = bookedForCancel();
        CourierProviderAdapter a = reschedulingAdapter(com.courier.modules.customerportal.rates.domain.CancelOutcome.cancelled("ok"));
        var svc = service(a);

        assertThatThrownBy(() -> svc.reschedule(b.getId(), today().minusDays(1), null)).isInstanceOf(BusinessRuleException.class).hasMessageContaining("between today");
        assertThatThrownBy(() -> svc.reschedule(b.getId(), today().plusDays(15), null)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> svc.reschedule(b.getId(), null, null)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> svc.reschedule(b.getId(), today(), "Midnight")).isInstanceOf(BusinessRuleException.class).hasMessageContaining("slots");
        verify(a, never()).reschedulePickup(any(), any(), any(), any(), any());
        // an empty slot means "any time"
        svc.reschedule(b.getId(), today(), "");
        verify(a).reschedulePickup(any(), any(), any(), eq(today()), eq(null));
    }

    @Test
    @DisplayName("reschedule: a refusal (parcel collected) surfaces its reason; only a confirmed booking can be rescheduled")
    void rescheduleRefusals() {
        PortalBooking b = bookedForCancel();
        var svc = service(reschedulingAdapter(com.courier.modules.customerportal.rates.domain.CancelOutcome.failed("already collected")));
        assertThatThrownBy(() -> svc.reschedule(b.getId(), today(), null)).hasMessageContaining("already collected");

        PortalBooking failed = booking(PortalBooking.Status.FAILED, "h");
        when(store.findOwned(ACTING, failed.getId())).thenReturn(java.util.Optional.of(failed));
        assertThatThrownBy(() -> svc.reschedule(failed.getId(), today(), null)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("cancel: only a confirmed booking can be cancelled, and cancelling twice is harmless")
    void cancelStates() {
        PortalBooking failed = booking(PortalBooking.Status.FAILED, "h");
        when(store.findOwned(ACTING, failed.getId())).thenReturn(java.util.Optional.of(failed));
        var svc = service(cancellingAdapter(com.courier.modules.customerportal.rates.domain.CancelOutcome.cancelled("ok")));
        assertThatThrownBy(() -> svc.cancel(failed.getId(), null)).isInstanceOf(BusinessRuleException.class);

        PortalBooking done = booking(PortalBooking.Status.CANCELLED, "h");
        when(store.findOwned(ACTING, done.getId())).thenReturn(java.util.Optional.of(done));
        assertThat(svc.cancel(done.getId(), null).status()).isEqualTo("CANCELLED");
        verify(walletService, never()).refundCustomerBooking(any());
    }
}
