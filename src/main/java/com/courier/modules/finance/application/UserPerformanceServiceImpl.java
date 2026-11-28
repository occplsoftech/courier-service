package com.courier.modules.finance.application;

import com.courier.modules.finance.application.UserPerformance.Attribution;
import com.courier.modules.finance.application.UserPerformance.DetailRow;
import com.courier.modules.finance.application.UserPerformance.Metric;
import com.courier.modules.finance.application.UserPerformance.Reconciliation;
import com.courier.modules.finance.application.UserPerformance.Report;
import com.courier.modules.finance.application.UserPerformance.Row;
import com.courier.modules.finance.application.WalletAttribution.Bucket;
import com.courier.modules.finance.application.WalletAttribution.Share;
import com.courier.modules.finance.domain.BranchUserActivityPort;
import com.courier.modules.finance.domain.BranchUserActivityPort.Booking;
import com.courier.modules.finance.domain.BranchUserActivityPort.Delivery;
import com.courier.modules.finance.domain.BranchUserActivityPort.PaymentKind;
import com.courier.modules.finance.domain.BranchUserActivityPort.ShipmentOwners;
import com.courier.modules.finance.domain.CashHandover;
import com.courier.modules.finance.domain.CashHandoverRepository;
import com.courier.modules.finance.domain.ReferenceType;
import com.courier.modules.finance.domain.WalletTransaction;
import com.courier.modules.finance.domain.WalletTransactionCriteria;
import com.courier.shared.domain.TimeOrderedUuid;
import com.courier.shared.security.AuthenticatedUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.courier.modules.finance.application.CashHandoverServiceImpl.IST;

/**
 * See {@link UserPerformanceService}. Reads, never writes. Shipment facts come through {@link BranchUserActivityPort};
 * wallet entries through {@link WalletService#searchTransactions} (which applies its own branch scoping as well);
 * handovers from {@link CashHandoverRepository}.
 *
 * <p><b>Outstanding cash</b> counts from {@code trackingFrom} (V191): cash taken at the counter (Paid bookings paid in
 * cash, not cancelled) plus To-Pay / COD collected on delivery, minus live (not voided) handovers. Opening = up to the
 * day before the window, closing = up to its last day. UPI and Razorpay counter payments go to the bank and are never
 * outstanding.
 */
@Service
public class UserPerformanceServiceImpl implements UserPerformanceService {

    /** Map key for work done without a signed-in user. */
    private static final UUID SYSTEM = new UUID(0L, 0L);
    private static final String SYSTEM_NAME = "System / customer portal";

    private final BranchUserActivityPort activity;
    private final WalletService walletService;
    private final CashHandoverRepository handovers;
    private final FinanceBranchScope scope;
    private final Clock clock;

    @Autowired
    public UserPerformanceServiceImpl(BranchUserActivityPort activity, WalletService walletService,
                                      CashHandoverRepository handovers, FinanceBranchScope scope) {
        this(activity, walletService, handovers, scope, Clock.systemUTC());
    }

    UserPerformanceServiceImpl(BranchUserActivityPort activity, WalletService walletService,
                               CashHandoverRepository handovers, FinanceBranchScope scope, Clock clock) {
        this.activity = activity;
        this.walletService = walletService;
        this.handovers = handovers;
        this.scope = scope;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------------------ report

    @Override
    @Transactional
    @PreAuthorize(FinanceBranchScope.COMPANY_USERS_ONLY)
    public Report report(UUID branchId, LocalDate from, LocalDate to, Attribution attribution) {
        Window w = window(branchId, from, to, attribution);
        Facts facts = load(w);

        Map<UUID, Acc> acc = new LinkedHashMap<>();
        for (Booking b : facts.bookings) {
            if (!w.inPeriod(b.bookingDate())) continue;
            Acc a = acc.computeIfAbsent(key(b.bookedBy()), k -> new Acc());
            a.booked++;
            if (b.cancelled()) {
                a.cancelled++;
                continue;
            }
            a.bookingValue = a.bookingValue.add(b.netAmount());
            a.bookedWeight = a.bookedWeight.add(b.chargeableWeight());
            switch (b.kind()) {
                case PAID -> a.paidCount++;
                case TO_PAY -> a.toPayCount++;
                case COD -> a.codCount++;
                case CREDIT -> a.creditCount++;
            }
            if (b.kind() == PaymentKind.PAID) {
                if (!b.counterPaid()) {
                    a.counterPending = a.counterPending.add(b.netAmount());
                } else if (isCash(b)) {
                    a.counterCash = a.counterCash.add(b.counterAmount());
                } else {
                    a.counterOnline = a.counterOnline.add(b.counterAmount());
                }
            }
        }
        for (Delivery d : facts.deliveries) {
            boolean deliveredInPeriod = d.deliveredAt() != null && w.inPeriod(day(d.deliveredAt()));
            boolean pendingInPeriod = d.deliveredAt() == null && d.assignedAt() != null && w.inPeriod(day(d.assignedAt()));
            if (!deliveredInPeriod && !pendingInPeriod) continue;
            Acc a = acc.computeIfAbsent(d.deliveryUserId(), k -> new Acc());
            a.failedAttempts += d.failedAttempts();
            if (deliveredInPeriod) {
                a.delivered++;
                a.deliveryCash = a.deliveryCash.add(d.amountToCollect());
            } else {
                a.deliveryPending++;
            }
        }
        BigDecimal walletDebits = BigDecimal.ZERO;
        BigDecimal walletCredits = BigDecimal.ZERO;
        BigDecimal unattributed = BigDecimal.ZERO;
        for (Attributed e : facts.entries) {
            WalletTransaction t = e.entry();
            Share s = e.share();
            if (t.getTransactionType().isCredit()) {
                walletCredits = walletCredits.add(t.getAmount());
            } else {
                walletDebits = walletDebits.add(t.getAmount());
            }
            if (s.bucket() == Bucket.NONE) continue;
            if (s.userId() == null) {
                if (s.bucket() == Bucket.DEBIT) unattributed = unattributed.add(s.amount());
                continue;
            }
            Acc a = acc.computeIfAbsent(s.userId(), k -> new Acc());
            switch (s.bucket()) {
                case DEBIT -> a.walletDebit = a.walletDebit.add(s.amount());
                case REFUND -> a.walletRefund = a.walletRefund.add(s.amount());
                case COMMISSION -> a.commission = a.commission.add(s.amount());
                default -> { }
            }
        }
        for (CashHandover h : facts.liveHandovers) {
            if (w.inPeriod(h.getHandoverDate())) {
                Acc a = acc.computeIfAbsent(h.getUserId(), k -> new Acc());
                a.handedOver = a.handedOver.add(h.getAmount());
            }
        }
        applyOutstanding(w, facts, acc);

        AuthenticatedUser caller = w.caller;
        boolean team = scope.seesTeam(caller);
        if (!team) {
            Acc own = acc.getOrDefault(caller.userId(), new Acc());
            acc.clear();
            acc.put(caller.userId(), own);
        }

        List<Row> rows = acc.entrySet().stream()
                .filter(e -> !team || e.getValue().hasAnything())
                .map(e -> toRow(e.getKey(), e.getValue(), w.companyId))
                .sorted(Comparator.comparing(Row::system)
                        .thenComparing(Row::cashCollected, Comparator.reverseOrder())
                        .thenComparing(Row::booked, Comparator.reverseOrder())
                        .thenComparing(Row::delivered, Comparator.reverseOrder())
                        .thenComparing(Row::userName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        Acc sum = new Acc();
        acc.values().forEach(sum::add);
        Row total = toRow(null, sum, w.companyId);

        BigDecimal attributedDebits = sum.walletDebit;
        Reconciliation reconciliation = team
                ? new Reconciliation(money(walletDebits), money(walletCredits), money(attributedDebits), money(unattributed))
                : null;
        return new Report(w.branchId, w.from, w.to, w.attribution, w.trackingFrom, team,
                scope.canRecordHandover(caller), rows, total, reconciliation);
    }

    // ------------------------------------------------------------------------------------------------ details

    @Override
    @Transactional
    @PreAuthorize(FinanceBranchScope.COMPANY_USERS_ONLY)
    public List<DetailRow> details(UUID branchId, LocalDate from, LocalDate to, UUID userId, Metric metric,
                                   Attribution attribution) {
        Window w = window(branchId, from, to, attribution);
        UUID who = scope.seesTeam(w.caller) ? userId : w.caller.userId();
        UUID key = key(who);
        Facts facts = load(w);
        Metric m = metric == null ? Metric.BOOKINGS : metric;
        List<DetailRow> out = new ArrayList<>();
        switch (m) {
            case BOOKINGS -> facts.bookings.stream()
                    .filter(b -> w.inPeriod(b.bookingDate()) && key(b.bookedBy()).equals(key))
                    .forEach(b -> out.add(new DetailRow(b.bookingDate(), b.bookedAt(), b.shipmentNumber(),
                            b.kind().name(), b.paymentMode(), b.status(), money(b.netAmount()))));
            case COUNTER -> facts.bookings.stream()
                    .filter(b -> w.inPeriod(b.bookingDate()) && key(b.bookedBy()).equals(key)
                            && b.kind() == PaymentKind.PAID)
                    .forEach(b -> out.add(new DetailRow(b.bookingDate(), b.bookedAt(), b.shipmentNumber(),
                            b.counterMethod(), b.paymentMode(),
                            b.cancelled() ? "CANCELLED" : b.counterPaid() ? "PAID" : "PENDING",
                            money(b.counterPaid() ? b.counterAmount() : b.netAmount()))));
            case DELIVERIES -> facts.deliveries.stream()
                    .filter(d -> d.deliveryUserId().equals(key))
                    .filter(d -> d.deliveredAt() != null ? w.inPeriod(day(d.deliveredAt()))
                            : d.assignedAt() != null && w.inPeriod(day(d.assignedAt())))
                    .forEach(d -> {
                        Instant at = d.deliveredAt() != null ? d.deliveredAt() : d.assignedAt();
                        String detail = (d.drsNumber() == null ? "" : "DRS " + d.drsNumber() + " · ")
                                + d.paymentMode() + (d.failedAttempts() > 0 ? " · " + d.failedAttempts() + " failed attempt(s)" : "");
                        out.add(new DetailRow(day(at), at, d.shipmentNumber(), d.kind().name(), detail,
                                d.deliveredAt() != null ? "DELIVERED" : "PENDING", money(d.amountToCollect())));
                    });
            case WALLET -> facts.entries.forEach(e -> {
                WalletTransaction t = e.entry();
                Share s = e.share();
                boolean mine = who == null
                        ? s.userId() == null && s.bucket() == Bucket.DEBIT
                        : who.equals(s.userId()) && s.bucket() != Bucket.NONE;
                if (mine) {
                    String detail = t.getSubTransactionType().getLabel()
                            + (t.getReferenceType() == ReferenceType.SHIPMENT && t.getReferenceId() != null
                                ? " · " + t.getReferenceId() : "")
                            + (t.getRemarks() == null ? "" : " · " + t.getRemarks());
                    out.add(new DetailRow(day(t.getCreatedAt()), t.getCreatedAt(), t.getTransactionNo(),
                            t.getTransactionType().name(), detail, s.bucket().name(), money(t.getAmount())));
                }
            });
            case HANDOVERS -> facts.allHandovers.stream()
                    .filter(h -> w.inPeriod(h.getHandoverDate()) && h.getUserId().equals(key))
                    .forEach(h -> out.add(new DetailRow(h.getHandoverDate(), h.getReceivedAt(), h.getId().toString(),
                            h.getMode().name(), detailOf(h, w.companyId), h.isVoided() ? "VOIDED" : "RECEIVED",
                            money(h.getAmount()))));
        }
        out.sort(Comparator.comparing(DetailRow::at, Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    // ------------------------------------------------------------------------------------------------ internals

    private Window window(UUID branchId, LocalDate from, LocalDate to, Attribution attribution) {
        UUID companyId = scope.requireCompany();
        AuthenticatedUser caller = scope.caller();
        UUID branch = scope.resolveBranch(branchId, companyId, caller);
        LocalDate[] range = CashHandoverServiceImpl.range(from, to, clock);
        LocalDate trackingFrom = handovers.findTrackingFrom(TimeOrderedUuid.toBytes(companyId)).stream()
                .filter(Objects::nonNull).findFirst().map(UserPerformanceServiceImpl::toLocalDate).orElse(null);
        return new Window(companyId, caller, branch, range[0], range[1],
                attribution == null ? Attribution.WORK : attribution, trackingFrom);
    }

    private Facts load(Window w) {
        // Outstanding needs every collection since trackingFrom, so the shipment reads reach back that far.
        LocalDate start = w.trackingFrom != null && w.trackingFrom.isBefore(w.from) ? w.trackingFrom : w.from;
        List<Booking> bookings = activity.bookings(w.companyId, w.branchId, start, w.to);
        List<Delivery> deliveries = activity.deliveries(w.companyId, w.branchId, startOf(start), startOf(w.to.plusDays(1)));
        List<CashHandover> all = handovers.findForBranchBetween(w.companyId, w.branchId, start, w.to);
        List<CashHandover> live = all.stream().filter(h -> !h.isVoided()).toList();

        WalletTransactionCriteria criteria = new WalletTransactionCriteria(null, null, null, null, null, null, null,
                null, startOf(w.from), startOf(w.to.plusDays(1)), null, null, null);
        List<WalletTransaction> entries = walletService.searchTransactions(w.branchId, criteria, Pageable.unpaged())
                .getContent();
        Set<String> numbers = entries.stream()
                .filter(t -> t.getReferenceType() == ReferenceType.SHIPMENT && t.getReferenceId() != null)
                .map(WalletTransaction::getReferenceId).collect(Collectors.toSet());
        Map<String, ShipmentOwners> owners = activity.ownersOf(w.companyId, numbers);
        List<Attributed> attributed = entries.stream()
                .map(t -> new Attributed(t, WalletAttribution.of(t, w.branchId, owners, w.attribution)))
                .toList();
        return new Facts(bookings, deliveries, all, live, attributed);
    }

    /** Opening and closing outstanding per user; see the class comment. */
    private void applyOutstanding(Window w, Facts facts, Map<UUID, Acc> acc) {
        LocalDate tf = w.trackingFrom;
        if (tf == null || tf.isAfter(w.to)) {
            return;
        }
        Map<UUID, BigDecimal> opening = new HashMap<>();
        Map<UUID, BigDecimal> closing = new HashMap<>();
        java.util.function.BiConsumer<UUID, Map.Entry<LocalDate, BigDecimal>> add = (user, dayAmount) -> {
            LocalDate d = dayAmount.getKey();
            if (d.isBefore(tf) || d.isAfter(w.to)) return;
            closing.merge(user, dayAmount.getValue(), BigDecimal::add);
            if (d.isBefore(w.from)) opening.merge(user, dayAmount.getValue(), BigDecimal::add);
        };
        for (Booking b : facts.bookings) {
            if (b.kind() == PaymentKind.PAID && !b.cancelled() && b.counterPaid() && isCash(b) && b.bookedBy() != null) {
                add.accept(b.bookedBy(), Map.entry(b.bookingDate(), b.counterAmount()));
            }
        }
        for (Delivery d : facts.deliveries) {
            if (d.deliveredAt() != null && d.amountToCollect().signum() > 0) {
                add.accept(d.deliveryUserId(), Map.entry(day(d.deliveredAt()), d.amountToCollect()));
            }
        }
        for (CashHandover h : facts.liveHandovers) {
            add.accept(h.getUserId(), Map.entry(h.getHandoverDate(), h.getAmount().negate()));
        }
        Set<UUID> users = new java.util.HashSet<>(closing.keySet());
        users.addAll(opening.keySet());
        for (UUID user : users) {
            Acc a = acc.computeIfAbsent(user, k -> new Acc());
            a.openingOutstanding = opening.getOrDefault(user, BigDecimal.ZERO);
            a.closingOutstanding = closing.getOrDefault(user, BigDecimal.ZERO);
        }
    }

    private Row toRow(UUID key, Acc a, UUID companyId) {
        boolean system = SYSTEM.equals(key);
        UUID userId = key == null || system ? null : key;
        String name = key == null ? "Branch total" : system ? SYSTEM_NAME : scope.displayName(key, companyId);
        long assigned = a.delivered + a.deliveryPending;
        BigDecimal success = assigned == 0 ? null
                : BigDecimal.valueOf(a.delivered * 100L).divide(BigDecimal.valueOf(assigned), 1, RoundingMode.HALF_UP);
        BigDecimal collected = a.counterCash.add(a.deliveryCash);
        return new Row(userId, name, system, a.booked, a.cancelled, money(a.bookingValue), weight(a.bookedWeight),
                a.paidCount, a.toPayCount, a.codCount, a.creditCount,
                money(a.counterCash), money(a.counterOnline), money(a.counterPending),
                a.delivered, a.deliveryPending, a.failedAttempts, money(a.deliveryCash), success,
                money(a.walletDebit), money(a.walletRefund), money(a.walletDebit.subtract(a.walletRefund)),
                money(a.commission), money(collected), money(a.openingOutstanding), money(a.handedOver),
                money(a.closingOutstanding));
    }

    private String detailOf(CashHandover h, UUID companyId) {
        StringBuilder sb = new StringBuilder("Received by ").append(scope.displayName(h.getReceivedBy(), companyId));
        if (h.getReference() != null) sb.append(" · Ref ").append(h.getReference());
        if (h.getRemarks() != null) sb.append(" · ").append(h.getRemarks());
        if (h.isVoided()) sb.append(" · Voided: ").append(h.getVoidReason());
        return sb.toString();
    }

    private static boolean isCash(Booking b) {
        return "CASH".equals(b.counterMethod());
    }

    private static UUID key(UUID userId) {
        return userId == null ? SYSTEM : userId;
    }

    private static LocalDate day(Instant at) {
        return LocalDate.ofInstant(at, IST);
    }

    private static Instant startOf(LocalDate day) {
        return day.atStartOfDay(IST).toInstant();
    }

    private static BigDecimal money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal weight(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(3, RoundingMode.HALF_UP);
    }

    static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate d) return d;
        if (value instanceof java.sql.Date d) return d.toLocalDate();
        if (value instanceof java.util.Date d) return LocalDate.ofInstant(d.toInstant(), IST);
        return LocalDate.parse(value.toString().substring(0, 10));
    }

    private record Window(UUID companyId, AuthenticatedUser caller, UUID branchId, LocalDate from, LocalDate to,
                          Attribution attribution, LocalDate trackingFrom) {
        boolean inPeriod(LocalDate day) {
            return day != null && !day.isBefore(from) && !day.isAfter(to);
        }
    }

    private record Facts(List<Booking> bookings, List<Delivery> deliveries, List<CashHandover> allHandovers,
                         List<CashHandover> liveHandovers, List<Attributed> entries) {
    }

    private record Attributed(WalletTransaction entry, Share share) {
    }

    /** Running totals for one user. */
    private static final class Acc {
        long booked, cancelled, paidCount, toPayCount, codCount, creditCount, delivered, deliveryPending, failedAttempts;
        BigDecimal bookingValue = BigDecimal.ZERO, bookedWeight = BigDecimal.ZERO;
        BigDecimal counterCash = BigDecimal.ZERO, counterOnline = BigDecimal.ZERO, counterPending = BigDecimal.ZERO;
        BigDecimal deliveryCash = BigDecimal.ZERO;
        BigDecimal walletDebit = BigDecimal.ZERO, walletRefund = BigDecimal.ZERO, commission = BigDecimal.ZERO;
        BigDecimal handedOver = BigDecimal.ZERO, openingOutstanding = BigDecimal.ZERO, closingOutstanding = BigDecimal.ZERO;

        boolean hasAnything() {
            return booked > 0 || delivered > 0 || deliveryPending > 0
                    || walletDebit.signum() != 0 || walletRefund.signum() != 0 || commission.signum() != 0
                    || handedOver.signum() != 0 || openingOutstanding.signum() != 0 || closingOutstanding.signum() != 0;
        }

        void add(Acc o) {
            booked += o.booked; cancelled += o.cancelled; paidCount += o.paidCount; toPayCount += o.toPayCount;
            codCount += o.codCount; creditCount += o.creditCount; delivered += o.delivered;
            deliveryPending += o.deliveryPending; failedAttempts += o.failedAttempts;
            bookingValue = bookingValue.add(o.bookingValue); bookedWeight = bookedWeight.add(o.bookedWeight);
            counterCash = counterCash.add(o.counterCash); counterOnline = counterOnline.add(o.counterOnline);
            counterPending = counterPending.add(o.counterPending); deliveryCash = deliveryCash.add(o.deliveryCash);
            walletDebit = walletDebit.add(o.walletDebit); walletRefund = walletRefund.add(o.walletRefund);
            commission = commission.add(o.commission); handedOver = handedOver.add(o.handedOver);
            openingOutstanding = openingOutstanding.add(o.openingOutstanding);
            closingOutstanding = closingOutstanding.add(o.closingOutstanding);
        }
    }
}
