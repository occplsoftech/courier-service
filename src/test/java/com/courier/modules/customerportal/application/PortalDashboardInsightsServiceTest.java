package com.courier.modules.customerportal.application;

import com.courier.modules.customerportal.application.PortalDashboardInsightsService.Insights;
import com.courier.modules.customerportal.application.PortalDashboardInsightsService.Period;
import com.courier.modules.customerportal.rates.domain.CourierProviderAdapter;
import com.courier.modules.customerportal.rates.domain.DeliveryStage;
import com.courier.modules.customerportal.rates.domain.PortalBooking;
import com.courier.modules.customerportal.rates.domain.PortalBookingRepository;
import com.courier.modules.customerportal.rates.domain.RateQuote;
import com.courier.modules.customerportal.rates.domain.RateQuoteOption;
import com.courier.modules.customerportal.rates.domain.RateQuoteOptionRepository;
import com.courier.modules.customerportal.rates.domain.RateQuoteRepository;
import com.courier.modules.shipment.application.ShipmentPortalQueries;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PortalDashboardInsightsServiceTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    /** Sat 7 Nov 2026, 12:00 India. */
    private static final Instant NOW = Instant.parse("2026-11-07T06:30:00Z");

    private final CurrentCustomerResolver resolver = mock(CurrentCustomerResolver.class);
    private final PortalBookingRepository bookings = mock(PortalBookingRepository.class);
    private final RateQuoteOptionRepository options = mock(RateQuoteOptionRepository.class);
    private final RateQuoteRepository quotes = mock(RateQuoteRepository.class);
    private final ShipmentPortalQueries shipments = mock(ShipmentPortalQueries.class);
    private final List<PortalBooking> data = new ArrayList<>();
    private final List<RateQuoteOption> opts = new ArrayList<>();
    private PortalDashboardInsightsService service;

    @BeforeEach
    void setUp() {
        when(resolver.require()).thenReturn(new ActingCustomer(COMPANY, UUID.randomUUID(), CUSTOMER));
        when(bookings.findPage(eq(COMPANY), eq(CUSTOMER), any(), anyString(), any(), any(), anyBoolean(), any(), anyBoolean(), any()))
                .thenAnswer(i -> new PageImpl<>(data));
        when(options.findAllWithin(any(), eq(COMPANY))).thenAnswer(i -> opts);
        when(shipments.awaitingDeliveryChoice(eq(COMPANY), any())).thenReturn(Set.of());
        CourierProviderAdapter occpl = mock(CourierProviderAdapter.class);
        when(occpl.code()).thenReturn("OCCPL_NETWORK");
        when(occpl.displayName()).thenReturn("OCCPL Network");
        service = new PortalDashboardInsightsService(resolver, bookings, options, quotes, shipments, List.of(occpl),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private PortalBooking booking(String provider, String total, String bookedAt, Integer etaMaxDays, DeliveryStage stage, String stageAt) {
        RateQuoteOption o = RateQuoteOption.builder().etaMaxDays(etaMaxDays).build();
        o.setId(UUID.randomUUID());
        opts.add(o);
        PortalBooking b = PortalBooking.builder().customerId(CUSTOMER).providerCode(provider).status(PortalBooking.Status.BOOKED)
                .totalAmount(new BigDecimal(total)).quoteOptionId(o.getId()).quoteId(UUID.randomUUID()).awbNumber("AWB" + data.size())
                .bookedAt(Instant.parse(bookedAt)).deliveryStatus(stage)
                .deliveryStatusAt(stageAt == null ? null : Instant.parse(stageAt)).build();
        b.setId(UUID.randomUUID());
        b.setCreatedAt(Instant.parse(bookedAt));
        data.add(b);
        return b;
    }

    @Test
    @DisplayName("spend, average, on-time, late, stages and courier split over the period's bookings")
    void insights() {
        // delivered on time (promise: 2 Nov + 3 days -> by end of 5 Nov)
        booking("OCCPL_NETWORK", "100.00", "2026-11-02T05:00:00Z", 3, DeliveryStage.DELIVERED, "2026-11-04T10:00:00Z");
        // delivered late (promise by end of 3 Nov, delivered 6 Nov)
        booking("SHIPROCKET", "200.00", "2026-11-01T05:00:00Z", 2, DeliveryStage.DELIVERED, "2026-11-06T10:00:00Z");
        // in transit and late (promise by end of 4 Nov; today 7 Nov)
        PortalBooking late = booking("SHIPROCKET", "150.00", "2026-11-02T05:00:00Z", 2, DeliveryStage.IN_TRANSIT, "2026-11-03T10:00:00Z");
        // returned with an RTO charge (not late: finished)
        PortalBooking rto = booking("OCCPL_NETWORK", "80.00", "2026-10-20T05:00:00Z", 2, DeliveryStage.RETURNED, "2026-10-25T10:00:00Z");
        rto.setRtoCharge(new BigDecimal("50.00"));
        // just booked, no news yet, no estimate
        booking("OCCPL_NETWORK", "60.00", "2026-11-07T04:00:00Z", null, null, null);
        // cancelled by the courier and refunded: left out of everything
        booking("SHIPROCKET", "999.00", "2026-11-03T05:00:00Z", 2, DeliveryStage.CANCELLED, "2026-11-03T08:00:00Z");
        when(quotes.findOwned(late.getQuoteId(), COMPANY, CUSTOMER)).thenReturn(Optional.of(
                RateQuote.builder().request(Map.of("delivery", Map.of("name", "Ravi"))).build()));

        Insights i = service.insights(Period.LAST_30_DAYS);

        assertThat(i.from()).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(i.to()).isEqualTo(LocalDate.of(2026, 11, 7));
        assertThat(i.shipments()).isEqualTo(5);
        assertThat(i.spend()).isEqualByComparingTo("640.00");          // 100 + 200 + 150 + 80 + 50 RTO + 60
        assertThat(i.averageSpend()).isEqualByComparingTo("128.00");
        assertThat(i.onTimePercent()).isEqualTo(50);                    // 1 of 2 delivered with an estimate
        assertThat(i.onTimeMeasured()).isEqualTo(2);
        assertThat(i.lateCount()).isEqualTo(1);
        assertThat(i.late().get(0).trackingNumber()).isEqualTo(late.getAwbNumber());
        assertThat(i.late().get(0).deliveryName()).isEqualTo("Ravi");
        assertThat(i.late().get(0).promisedBy()).isEqualTo(LocalDate.of(2026, 11, 4));
        assertThat(i.late().get(0).daysLate()).isEqualTo(3);
        assertThat(i.needAttention()).isEqualTo(1);
        assertThat(i.stages()).containsEntry("AWAITING_PICKUP", 1L).containsEntry("IN_TRANSIT", 1L)
                .containsEntry("DELIVERED", 2L).containsEntry("RETURNED", 1L).containsEntry("OUT_FOR_DELIVERY", 0L);
        assertThat(i.couriers()).extracting(PortalDashboardInsightsService.CourierShare::providerCode)
                .containsExactly("SHIPROCKET", "OCCPL_NETWORK");                // 350 spend before 290
        assertThat(i.couriers().get(1).providerName()).isEqualTo("OCCPL Network");
        assertThat(i.couriers().get(0).onTimePercent()).isZero();
        assertThat(i.couriers().get(1).onTimePercent()).isEqualTo(100);
        assertThat(i.spendSeries()).hasSize(30);
        assertThat(i.spendSeries().get(29).date()).isEqualTo(LocalDate.of(2026, 11, 7));
        assertThat(i.spendSeries().get(29).spend()).isEqualByComparingTo("60.00");
    }

    @Test
    @DisplayName("90 days is shown per week (Monday) with no gaps; an empty period is all zeros")
    void weeklyAndEmpty() {
        Insights i = service.insights(Period.LAST_90_DAYS);
        assertThat(i.shipments()).isZero();
        assertThat(i.spend()).isEqualByComparingTo("0");
        assertThat(i.onTimePercent()).isNull();
        assertThat(i.spendSeries()).allSatisfy(p -> assertThat(p.date().getDayOfWeek()).isEqualTo(java.time.DayOfWeek.MONDAY));
        assertThat(i.spendSeries()).hasSizeBetween(13, 14);
        assertThat(service.insights(null).period()).isEqualTo(Period.LAST_30_DAYS);
        assertThat(service.insights(Period.THIS_MONTH).from()).isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    @DisplayName("a failed delivery waiting for the customer's answer needs attention")
    void needsAnswer() {
        PortalBooking b = booking("OCCPL_NETWORK", "70.00", "2026-11-06T05:00:00Z", 5, DeliveryStage.OUT_FOR_DELIVERY, null);
        UUID shipment = UUID.randomUUID();
        b.setShipmentId(shipment);
        when(shipments.awaitingDeliveryChoice(eq(COMPANY), any())).thenReturn(Set.of(shipment));
        Insights i = service.insights(Period.LAST_7_DAYS);
        assertThat(i.needAnswerCount()).isEqualTo(1);
        assertThat(i.needAnswer().get(0).bookingId()).isEqualTo(b.getId());
        assertThat(i.needAttention()).isEqualTo(1);

        // late as well as waiting for an answer: still one shipment needing attention
        b.setBookedAt(Instant.parse("2026-10-30T05:00:00Z"));
        b.setCreatedAt(Instant.parse("2026-11-01T05:00:00Z"));
        Insights both = service.insights(Period.LAST_7_DAYS);
        assertThat(both.lateCount()).isEqualTo(1);
        assertThat(both.needAnswerCount()).isEqualTo(1);
        assertThat(both.needAttention()).isEqualTo(1);
    }
}
