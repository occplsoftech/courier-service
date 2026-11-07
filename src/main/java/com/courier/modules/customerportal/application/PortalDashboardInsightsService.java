package com.courier.modules.customerportal.application;

import com.courier.modules.customerportal.rates.domain.CourierProviderAdapter;
import com.courier.modules.customerportal.rates.domain.DeliveryStage;
import com.courier.modules.customerportal.rates.domain.PortalBooking;
import com.courier.modules.customerportal.rates.domain.PortalBookingRepository;
import com.courier.modules.customerportal.rates.domain.RateQuoteOption;
import com.courier.modules.customerportal.rates.domain.RateQuoteOptionRepository;
import com.courier.modules.customerportal.rates.domain.RateQuoteRepository;
import com.courier.modules.shipment.application.ShipmentPortalQueries;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The customer dashboard (portal roadmap 12.2): every booking the signed-in customer made in a period, whatever the courier (OCCPL
 * Network and outside couriers alike), as spend, where the parcels are, delivered-on-time, courier split and the shipments that need
 * attention (late, or a failed delivery waiting for the customer's answer). Read-only; bookings count by the day they were made (India).
 */
@Service
public class PortalDashboardInsightsService {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final int MAX_BOOKINGS = 5000;
    private static final int MAX_LIST = 10;
    private static final Set<DeliveryStage> FINISHED = EnumSet.of(DeliveryStage.DELIVERED, DeliveryStage.RETURNED, DeliveryStage.CANCELLED);

    private final CurrentCustomerResolver resolver;
    private final PortalBookingRepository bookings;
    private final RateQuoteOptionRepository options;
    private final RateQuoteRepository quotes;
    private final ShipmentPortalQueries shipments;
    private final List<CourierProviderAdapter> adapters;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public PortalDashboardInsightsService(CurrentCustomerResolver resolver, PortalBookingRepository bookings, RateQuoteOptionRepository options,
                                          RateQuoteRepository quotes, ShipmentPortalQueries shipments, List<CourierProviderAdapter> adapters) {
        this(resolver, bookings, options, quotes, shipments, adapters, Clock.systemUTC());
    }

    PortalDashboardInsightsService(CurrentCustomerResolver resolver, PortalBookingRepository bookings, RateQuoteOptionRepository options,
                                   RateQuoteRepository quotes, ShipmentPortalQueries shipments, List<CourierProviderAdapter> adapters, Clock clock) {
        this.resolver = resolver;
        this.bookings = bookings;
        this.options = options;
        this.quotes = quotes;
        this.shipments = shipments;
        this.adapters = adapters;
        this.clock = clock;
    }

    public enum Period { LAST_7_DAYS, LAST_30_DAYS, LAST_90_DAYS, THIS_MONTH }

    @Transactional(readOnly = true)
    public Insights insights(Period requested) {
        ActingCustomer acting = resolver.require();
        Period period = requested == null ? Period.LAST_30_DAYS : requested;
        LocalDate today = LocalDate.now(clock.withZone(INDIA));
        LocalDate from = switch (period) {
            case LAST_7_DAYS -> today.minusDays(6);
            case LAST_30_DAYS -> today.minusDays(29);
            case LAST_90_DAYS -> today.minusDays(89);
            case THIS_MONTH -> today.withDayOfMonth(1);
        };
        Instant start = from.atStartOfDay(INDIA).toInstant();
        Instant end = today.plusDays(1).atStartOfDay(INDIA).toInstant();
        Instant now = clock.instant();

        List<PortalBooking> booked = bookings.findPage(acting.companyId(), acting.customerId(), EnumSet.of(PortalBooking.Status.BOOKED), "",
                start, end, true, EnumSet.allOf(DeliveryStage.class), true, PageRequest.of(0, MAX_BOOKINGS)).getContent();
        Map<UUID, RateQuoteOption> optionById = booked.isEmpty() ? Map.of()
                : options.findAllWithin(booked.stream().map(PortalBooking::getQuoteOptionId).distinct().toList(), acting.companyId())
                        .stream().collect(Collectors.toMap(RateQuoteOption::getId, Function.identity(), (a, b) -> a));
        Function<PortalBooking, Integer> eta = b -> {
            RateQuoteOption o = optionById.get(b.getQuoteOptionId());
            return o == null ? null : o.getEtaMaxDays();
        };

        // ---- tiles
        BigDecimal spend = booked.stream().map(PortalDashboardInsightsService::spendOf).reduce(BigDecimal.ZERO, BigDecimal::add);
        OnTime onTime = onTime(booked, eta);
        List<LateShipment> late = booked.stream()
                .map(b -> late(b, eta.apply(b), now))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingLong(LateShipment::daysLate).reversed())
                .toList();
        Set<UUID> awaiting = shipments.awaitingDeliveryChoice(acting.companyId(),
                booked.stream().map(PortalBooking::getShipmentId).filter(Objects::nonNull).toList());
        List<NeedsAnswer> needAnswer = booked.stream().filter(b -> b.getShipmentId() != null && awaiting.contains(b.getShipmentId()))
                .map(b -> new NeedsAnswer(b.getId(), b.getAwbNumber(), providerName(b.getProviderCode()), deliveryName(b)))
                .toList();

        // ---- where the parcels are (a booking the courier has not reported on yet is awaiting pickup)
        Map<String, Long> stages = new LinkedHashMap<>();
        for (DeliveryStage s : List.of(DeliveryStage.AWAITING_PICKUP, DeliveryStage.PICKED_UP, DeliveryStage.IN_TRANSIT,
                DeliveryStage.OUT_FOR_DELIVERY, DeliveryStage.DELIVERED, DeliveryStage.RETURNED)) {
            stages.put(s.name(), 0L);
        }
        for (PortalBooking b : booked) {
            DeliveryStage s = b.getDeliveryStatus() == null ? DeliveryStage.AWAITING_PICKUP : b.getDeliveryStatus();
            if (s != DeliveryStage.CANCELLED) {
                stages.merge(s.name(), 1L, Long::sum);
            }
        }

        // ---- courier split, biggest spend first
        List<CourierShare> couriers = booked.stream().collect(Collectors.groupingBy(PortalBooking::getProviderCode, LinkedHashMap::new,
                        Collectors.toList()))
                .entrySet().stream()
                .map(e -> new CourierShare(e.getKey(), providerName(e.getKey()), e.getValue().size(),
                        e.getValue().stream().map(PortalDashboardInsightsService::spendOf).reduce(BigDecimal.ZERO, BigDecimal::add)
                                .setScale(2, RoundingMode.HALF_UP),
                        onTime(e.getValue(), eta).percent()))
                .sorted(Comparator.comparing(CourierShare::spend).reversed())
                .toList();

        late = late.stream().limit(MAX_LIST).map(l -> new LateShipment(l.bookingId(), l.trackingNumber(), l.providerName(),
                recipient(acting, booked, l.bookingId()), l.lastStatus(), l.promisedBy(), l.daysLate())).toList();
        Set<UUID> lateIds = booked.stream().map(b -> late(b, eta.apply(b), now)).filter(Objects::nonNull)
                .map(LateShipment::bookingId).collect(Collectors.toSet());
        int lateCount = lateIds.size();
        // a shipment both late and waiting for an answer needs attention once
        Set<UUID> attention = new java.util.HashSet<>(lateIds);
        needAnswer.forEach(n -> attention.add(n.bookingId()));
        List<NeedsAnswer> answers = needAnswer.stream().limit(MAX_LIST).map(n -> new NeedsAnswer(n.bookingId(), n.trackingNumber(),
                n.providerName(), recipient(acting, booked, n.bookingId()))).toList();
        return new Insights(period, from, today, booked.size(), spend.setScale(2, RoundingMode.HALF_UP),
                booked.isEmpty() ? BigDecimal.ZERO.setScale(2) : spend.divide(BigDecimal.valueOf(booked.size()), 2, RoundingMode.HALF_UP),
                onTime.percent(), onTime.measured(), attention.size(),
                spendSeries(booked, from, today, period == Period.LAST_90_DAYS), stages, couriers,
                late, lateCount, answers, needAnswer.size());
    }

    /** What a booking cost the customer: its price plus any return (RTO) charge. */
    static BigDecimal spendOf(PortalBooking b) {
        BigDecimal total = b.getTotalAmount() == null ? BigDecimal.ZERO : b.getTotalAmount();
        return b.getRtoCharge() == null ? total : total.add(b.getRtoCharge());
    }

    /** When the courier promised it: the end of the booking day plus its longest delivery estimate; null when it gave none. */
    static Instant promisedBy(PortalBooking b, Integer etaMaxDays) {
        Instant booked = b.getBookedAt() != null ? b.getBookedAt() : b.getCreatedAt();
        if (booked == null || etaMaxDays == null || etaMaxDays <= 0) {
            return null;
        }
        return booked.atZone(INDIA).toLocalDate().plusDays(etaMaxDays + 1L).atStartOfDay(INDIA).toInstant();
    }

    /** Delivered within the promise, over the delivered bookings that had one. */
    static OnTime onTime(List<PortalBooking> list, Function<PortalBooking, Integer> eta) {
        int measured = 0;
        int inTime = 0;
        for (PortalBooking b : list) {
            Instant promise = promisedBy(b, eta.apply(b));
            if (b.getDeliveryStatus() == DeliveryStage.DELIVERED && promise != null && b.getDeliveryStatusAt() != null) {
                measured++;
                if (!b.getDeliveryStatusAt().isAfter(promise)) {
                    inTime++;
                }
            }
        }
        return new OnTime(measured == 0 ? null : (int) Math.round(inTime * 100.0 / measured), measured);
    }

    /** Past its promised day and not delivered, returned or cancelled; null otherwise. */
    LateShipment late(PortalBooking b, Integer etaMaxDays, Instant now) {
        Instant promise = promisedBy(b, etaMaxDays);
        if (promise == null || !now.isAfter(promise) || (b.getDeliveryStatus() != null && FINISHED.contains(b.getDeliveryStatus()))) {
            return null;
        }
        long daysLate = Math.max(1, Duration.between(promise, now).toDays() + 1);
        return new LateShipment(b.getId(), b.getAwbNumber(), providerName(b.getProviderCode()), deliveryName(b),
                b.getDeliveryStatusText(), promise.atZone(INDIA).toLocalDate().minusDays(1), daysLate);
    }

    /** Spend per day (or per week starting Monday), every bucket present so the chart has no gaps. */
    static List<SpendPoint> spendSeries(List<PortalBooking> list, LocalDate from, LocalDate to, boolean weekly) {
        Map<LocalDate, BigDecimal> spend = new LinkedHashMap<>();
        Map<LocalDate, Integer> count = new LinkedHashMap<>();
        LocalDate first = weekly ? from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) : from;
        for (LocalDate d = first; !d.isAfter(to); d = d.plus(1, weekly ? ChronoUnit.WEEKS : ChronoUnit.DAYS)) {
            spend.put(d, BigDecimal.ZERO);
            count.put(d, 0);
        }
        for (PortalBooking b : list) {
            Instant at = b.getCreatedAt();
            if (at == null) {
                continue;
            }
            LocalDate day = at.atZone(INDIA).toLocalDate();
            LocalDate key = weekly ? day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) : day;
            if (spend.containsKey(key)) {
                spend.merge(key, spendOf(b), BigDecimal::add);
                count.merge(key, 1, Integer::sum);
            }
        }
        List<SpendPoint> out = new ArrayList<>();
        spend.forEach((d, v) -> out.add(new SpendPoint(d, v.setScale(2, RoundingMode.HALF_UP), count.get(d))));
        return out;
    }

    private String providerName(String code) {
        return adapters.stream().filter(a -> a.code().equals(code)).map(CourierProviderAdapter::displayName).findFirst().orElse(code);
    }

    private static String deliveryName(PortalBooking b) {
        return null; // filled in for the listed bookings only (see recipient)
    }

    /** The recipient the customer entered, from the booking's quote (only for the few bookings listed). */
    private String recipient(ActingCustomer acting, List<PortalBooking> booked, UUID bookingId) {
        return booked.stream().filter(b -> b.getId().equals(bookingId)).findFirst()
                .flatMap(b -> quotes.findOwned(b.getQuoteId(), acting.companyId(), acting.customerId()))
                .map(q -> q.getRequest() == null ? null : q.getRequest().get("delivery"))
                .filter(d -> d instanceof Map<?, ?>)
                .map(d -> ((Map<?, ?>) d).get("name"))
                .map(String::valueOf)
                .orElse(null);
    }

    record OnTime(Integer percent, int measured) {
    }

    public record SpendPoint(LocalDate date, BigDecimal spend, int shipments) {
    }

    public record CourierShare(String providerCode, String providerName, int shipments, BigDecimal spend, Integer onTimePercent) {
    }

    public record LateShipment(UUID bookingId, String trackingNumber, String providerName, String deliveryName, String lastStatus,
                               LocalDate promisedBy, long daysLate) {
    }

    public record NeedsAnswer(UUID bookingId, String trackingNumber, String providerName, String deliveryName) {
    }

    /**
     * {@code onTimePercent}: delivered within the courier's promise, over {@code onTimeMeasured} delivered bookings that had one (null
     * when none). {@code stages}: bookings by where the parcel is. {@code late}/{@code needAnswer}: up to ten each, with the full counts.
     */
    public record Insights(Period period, LocalDate from, LocalDate to, int shipments, BigDecimal spend, BigDecimal averageSpend,
                           Integer onTimePercent, int onTimeMeasured, int needAttention, List<SpendPoint> spendSeries,
                           Map<String, Long> stages, List<CourierShare> couriers, List<LateShipment> late, int lateCount,
                           List<NeedsAnswer> needAnswer, int needAnswerCount) {
    }
}
