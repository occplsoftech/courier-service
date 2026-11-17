package com.courier.modules.dashboard.application;

import com.courier.modules.dashboard.api.dto.BranchOverviewResponse;
import com.courier.modules.dashboard.api.dto.ChartPointResponse;
import com.courier.modules.dashboard.api.dto.ChartSeriesResponse;
import com.courier.modules.dashboard.api.dto.CompanyOverviewResponse;
import com.courier.modules.dashboard.api.dto.DashboardActivityResponse;
import com.courier.modules.dashboard.api.dto.DashboardChartsResponse;
import com.courier.modules.dashboard.api.dto.DashboardStatisticsResponse;
import com.courier.modules.dashboard.api.dto.DashboardSummaryResponse;
import com.courier.modules.dashboard.api.dto.DeliveryAgingBucketResponse;
import com.courier.modules.dashboard.api.dto.PipelineStageResponse;
import com.courier.modules.dashboard.api.dto.RecentShipmentResponse;
import com.courier.modules.dashboard.api.dto.TopCustomerResponse;
import com.courier.modules.dashboard.api.dto.TopRouteResponse;
import com.courier.modules.dashboard.api.dto.PodOverviewResponse;
import com.courier.modules.dashboard.api.dto.RejectedPodResponse;
import com.courier.modules.dashboard.domain.DashboardBranchDirectoryPort;
import com.courier.modules.finance.application.WalletService;
import com.courier.modules.finance.domain.Wallet;
import com.courier.modules.finance.domain.WalletRepository;
import com.courier.modules.finance.domain.WalletTransaction;
import com.courier.modules.finance.domain.WalletTransactionRepository;
import com.courier.modules.manifest.domain.ManifestRepository;
import com.courier.modules.manifest.domain.ManifestStatus;
import com.courier.modules.master.domain.PaymentMode;
import com.courier.modules.master.domain.PaymentModeRepository;
import com.courier.modules.pod.application.PodVerificationService;
import com.courier.modules.pod.domain.PodVerification;
import com.courier.modules.pod.domain.PodVerificationStatus;
import com.courier.modules.shipment.domain.Shipment;
import com.courier.modules.shipment.domain.ShipmentCharge;
import com.courier.modules.shipment.domain.ShipmentChargeRepository;
import com.courier.modules.shipment.domain.ShipmentRepository;
import com.courier.modules.shipment.domain.ShipmentStatus;
import com.courier.modules.shipment.domain.ShipmentStatusHistory;
import com.courier.modules.shipment.domain.ShipmentStatusHistoryRepository;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.domain.TimeOrderedUuid;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.security.AuthenticatedUser;
import com.courier.shared.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Backs the operational dashboard's summary card. Every figure is a real aggregate over
 * {@code Shipment}/{@code ShipmentCharge}.
 *
 * <p><b>Company scoping is explicit, not left to the implicit Hibernate filter</b> —
 * every query below takes the caller's company id (or {@code null} for a genuinely
 * cross-tenant {@code SUPER_ADMIN} read) as an argument. This method used to rely
 * solely on {@code CompanyFilterAspect} auto-enabling the filter per repository call,
 * which silently did not apply here (root cause not fully isolated — plausibly this
 * method's own deliberate non-{@code @Transactional} shape, see below, putting each
 * repository call on its own short-lived session): any authenticated company user
 * could read every tenant's shipment counts/revenue. Fixed 2026-08-17 — see
 * {@code perf-tests/ISSUES.md} ISSUE-001 in the repo root for the full writeup.
 */
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private static final Set<ShipmentStatus> IN_TRANSIT = EnumSet.of(
            ShipmentStatus.DISPATCHED, ShipmentStatus.IN_SCAN, ShipmentStatus.OUT_FOR_DELIVERY);

    private static final Set<ShipmentStatus> PENDING = EnumSet.of(
            ShipmentStatus.BOOKED, ShipmentStatus.READY_FOR_MANIFEST, ShipmentStatus.MANIFEST_CREATED);

    /** Mirrors the Pending Delivery page: shipments arrived at (or dispatched for local
     *  delivery from) the caller's own branch, still waiting to go out or be closed. */
    private static final Set<ShipmentStatus> PENDING_DELIVERY = EnumSet.of(
            ShipmentStatus.IN_SCAN, ShipmentStatus.OUT_FOR_DELIVERY);

    /** How many entries the Recent Activity timeline shows, merged across kinds. */
    private static final int ACTIVITY_LIMIT = 8;

    /** Every stage of the pipeline the Company Overview card renders, in display order. */
    private static final List<ShipmentStatus> PIPELINE_STAGES = List.of(
            ShipmentStatus.BOOKED, ShipmentStatus.READY_FOR_MANIFEST, ShipmentStatus.MANIFEST_CREATED,
            ShipmentStatus.DISPATCHED, ShipmentStatus.IN_SCAN, ShipmentStatus.OUT_FOR_DELIVERY,
            ShipmentStatus.DELIVERED);

    /** "Ready for manifest" backlog — current, not month-bound: an action-required count,
     *  not a monthly stat. */
    private static final Set<ShipmentStatus> READY_FOR_MANIFEST = EnumSet.of(
            ShipmentStatus.BOOKED, ShipmentStatus.READY_FOR_MANIFEST);

    /** Terminal-or-off-the-clock statuses excluded from the "Delayed Shipments" backlog. */
    private static final Set<ShipmentStatus> DELAY_EXCLUDED = EnumSet.of(
            ShipmentStatus.DELIVERED, ShipmentStatus.CANCELLED, ShipmentStatus.RETURNED);

    /** The POD Dashboard pie's own candidate set — every shipment where a POD is still
     *  relevant, whether or not one was ever run against it. Current state, not month-bound
     *  (same "backlog, not a monthly stat" philosophy {@link #PENDING_DELIVERY} already
     *  uses): a DELIVERED shipment with no POD still blocks its own delivery commission,
     *  and a REJECTED one still needs a re-upload, regardless of when it was booked. */
    private static final Set<ShipmentStatus> POD_RELEVANT = EnumSet.of(
            ShipmentStatus.OUT_FOR_DELIVERY, ShipmentStatus.DELIVERED);

    /** How many rows the branch dashboard's "Rejected POD" backlog shows at most. */
    private static final int REJECTED_POD_LIMIT = 20;

    /** A shipment still open this many days after booking counts as delayed. A simple,
     *  company-wide backlog heuristic — not the per-stage SLA rules {@code
     *  ShipmentSlaSweepService} already enforces. */
    private static final int DELAYED_AFTER_DAYS = 2;

    /** Below this spendable balance, a branch wallet counts as "low" on the Company
     *  Overview card. No per-company "recommended balance" setting exists yet (confirmed
     *  against {@code CompanySettings}) — a fixed heuristic, not a fabricated config read. */
    private static final BigDecimal LOW_BALANCE_THRESHOLD = new BigDecimal("1000");

    private static final int TOP_N = 5;

    /** How many trailing days the Shipment/Delivery Performance/Revenue Trend charts
     *  cover, today inclusive. */
    private static final int TREND_DAYS = 14;

    private static final DateTimeFormatter CHART_LABEL = DateTimeFormatter.ofPattern("d MMM");

    private final ShipmentRepository shipmentRepository;
    private final ShipmentChargeRepository shipmentChargeRepository;
    private final ShipmentStatusHistoryRepository shipmentStatusHistoryRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final WalletService walletService;
    private final ManifestRepository manifestRepository;
    private final WalletRepository walletRepository;
    private final DashboardBranchDirectoryPort branchDirectory;
    private final PodVerificationService podVerificationService;
    private final PaymentModeRepository paymentModeRepository;

    /**
     * Deliberately not {@code @Transactional}: {@link #ownWallet} calls into
     * {@code WalletServiceImpl.getForBranch}, its own {@code @Transactional} proxy, which
     * marks a shared transaction rollback-only the moment it throws — before the catch
     * here ever runs. Wrapping this method would turn that caught, expected exception
     * into an {@code UnexpectedRollbackException} on commit. Each repository call below
     * already runs in its own implicit transaction (Spring Data's default) — which is
     * exactly why every one of them takes an explicit company id rather than trusting a
     * per-session Hibernate filter to have been enabled on whichever short-lived session
     * that particular call happens to run on (ISSUE-001).
     */
    @Override
    public DashboardSummaryResponse summary(UUID branchId) {
        AuthenticatedUser caller = SecurityUtils.requireCurrentUser();
        boolean crossTenant = caller.isSuperAdmin();
        // A SUPER_ADMIN's own JWT still carries a (sentinel) company id — CompanyContext
        // is never actually empty for that role — so scope is null (genuinely
        // cross-tenant) only via the explicit isSuperAdmin() check, never inferred from
        // "no company bound".
        UUID scope = crossTenant ? null : CompanyContext.requireCompanyId();

        // Computed up front (was previously computed after the KPI block below) so the
        // KPI/chart/recent-activity queries can branch-scope for a caller with an own
        // branch instead of always running company-wide — see the new branch below.
        // A named branch (13.4): the admin sees that branch's dashboard; getForBranch checks the branch is in the company, and refuses
        // a branch user naming any branch but their own. Never for the cross-tenant read.
        // A company admin's own view is company-wide even when the admin is also assigned to a branch: they reach a branch's view by
        // naming it. Everyone else defaults to their own branch.
        boolean companyAdmin = !crossTenant && caller.hasRole(com.courier.shared.security.Roles.COMPANY_ADMIN);
        Wallet ownWallet = branchId != null && !crossTenant ? walletService.getForBranch(branchId)
                : companyAdmin ? null : ownWallet();
        UUID ownBranchId = ownWallet == null ? null : ownWallet.getBranchId();

        LocalDate today = LocalDate.now();
        // "Today's Shipments" / Delivered / In Transit / Pending / Collection are all
        // month-to-date, not literally today — first-of-month through today, inclusive.
        LocalDate monthStart = today.withDayOfMonth(1);
        long todayShipments;
        long delivered;
        long inTransit;
        long pending;
        long totalShipments;
        BigDecimal totalRevenue;
        BigDecimal todayCollection;
        BigDecimal totalActualWeight;
        List<Shipment> recent;
        List<ShipmentStatusHistory> recentDeliveries;
        List<WalletTransaction> recentWalletTransactions;

        if (crossTenant) {
            // runAs(null, ...) is this platform's own sanctioned way to disable the
            // Hibernate companyFilter for a deliberately cross-company read — the same
            // pattern TicketServiceImpl.dashboard uses for the identical SUPER_ADMIN case.
            todayShipments = CompanyContext.runAs(null, () -> shipmentRepository
                    .countByStatusNotAndBookingDateBetween(ShipmentStatus.CANCELLED, monthStart, today));
            delivered = CompanyContext.runAs(null, () -> shipmentRepository.countByStatusAndBookingDateBetween(
                    ShipmentStatus.DELIVERED, monthStart, today));
            inTransit = CompanyContext.runAs(null,
                    () -> shipmentRepository.countByStatusInAndBookingDateBetween(IN_TRANSIT, monthStart, today));
            pending = CompanyContext.runAs(null,
                    () -> shipmentRepository.countByStatusInAndBookingDateBetween(PENDING, monthStart, today));
            totalShipments = CompanyContext.<Long>runAs(null,
                    () -> shipmentRepository.countByStatusNot(ShipmentStatus.CANCELLED));
            totalActualWeight = CompanyContext.<BigDecimal>runAs(null,
                    () -> shipmentRepository.sumActualWeightByBookingDateBetween(monthStart, today));
            todayCollection = CompanyContext.<BigDecimal>runAs(null,
                    () -> shipmentChargeRepository.sumNetAmountForBookingDateBetween(monthStart, today));
            recent = CompanyContext.<List<Shipment>>runAs(null,
                    () -> shipmentRepository.findTop5ByOrderByCreatedAtDesc());
            recentDeliveries = CompanyContext.<List<ShipmentStatusHistory>>runAs(null, () ->
                    shipmentStatusHistoryRepository.findTop5ByStatusOrderByChangedAtDesc(ShipmentStatus.DELIVERED));
            recentWalletTransactions = CompanyContext.<List<WalletTransaction>>runAs(null,
                    () -> walletTransactionRepository.findTop5ByOrderByCreatedAtDesc());
        } else if (ownBranchId != null) {
            // Branch-scoped caller (BRANCH_MANAGER/BRANCH_OPERATOR/hub staff with an own
            // branch wallet): every figure below is this branch's own bookings, not the
            // whole company's — the company-wide branch below was the real bug report
            // ("dashboard count wrong for branch, showing all branches data").
            todayShipments = shipmentRepository.countByCompanyIdAndBookingBranchIdAndStatusNotAndBookingDateBetween(
                    scope, ownBranchId, ShipmentStatus.CANCELLED, monthStart, today);
            delivered = shipmentRepository.countByCompanyIdAndBookingBranchIdAndStatusAndBookingDateBetween(
                    scope, ownBranchId, ShipmentStatus.DELIVERED, monthStart, today);
            inTransit = shipmentRepository.countByCompanyIdAndBookingBranchIdAndStatusInAndBookingDateBetween(
                    scope, ownBranchId, IN_TRANSIT, monthStart, today);
            pending = shipmentRepository.countByCompanyIdAndBookingBranchIdAndStatusInAndBookingDateBetween(
                    scope, ownBranchId, PENDING, monthStart, today);
            // Not shown on any branch-scoped profile's tile set (dashboard.roles.ts) —
            // left company-wide, same as the cross-tenant/company branches' own values.
            totalShipments = shipmentRepository.countByCompanyIdAndStatusNot(scope, ShipmentStatus.CANCELLED);
            totalActualWeight = shipmentRepository.sumActualWeightByCompanyIdAndBookingBranchIdAndBookingDateBetween(
                    scope, ownBranchId, monthStart, today);
            todayCollection = shipmentChargeRepository
                    .sumNetAmountByCompanyIdAndBookingBranchIdAndBookingDateBetween(
                            scope, ownBranchId, monthStart, today);
            recent = shipmentRepository.findTop5ByCompanyIdAndBookingBranchIdOrderByCreatedAtDesc(
                    scope, ownBranchId);
            recentDeliveries = shipmentStatusHistoryRepository
                    .findTop5ByCompanyIdAndBranchIdAndStatusOrderByChangedAtDesc(
                            scope, ownBranchId, ShipmentStatus.DELIVERED);
            recentWalletTransactions = walletTransactionRepository.findRecent(
                    ownWallet.getId(), scope, PageRequest.of(0, 5));
        } else {
            todayShipments = shipmentRepository.countByCompanyIdAndStatusNotAndBookingDateBetween(
                    scope, ShipmentStatus.CANCELLED, monthStart, today);
            delivered = shipmentRepository.countByCompanyIdAndStatusAndBookingDateBetween(
                    scope, ShipmentStatus.DELIVERED, monthStart, today);
            inTransit = shipmentRepository.countByCompanyIdAndStatusInAndBookingDateBetween(
                    scope, IN_TRANSIT, monthStart, today);
            pending = shipmentRepository.countByCompanyIdAndStatusInAndBookingDateBetween(
                    scope, PENDING, monthStart, today);
            totalShipments = shipmentRepository.countByCompanyIdAndStatusNot(scope, ShipmentStatus.CANCELLED);
            totalActualWeight = shipmentRepository.sumActualWeightByCompanyIdAndBookingDateBetween(
                    scope, monthStart, today);
            todayCollection = shipmentChargeRepository.sumNetAmountByCompanyIdAndBookingDateBetween(
                    scope, monthStart, today);
            recent = shipmentRepository.findTop5ByCompanyIdOrderByCreatedAtDesc(scope);
            recentDeliveries = shipmentStatusHistoryRepository
                    .findTop5ByCompanyIdAndStatusOrderByChangedAtDesc(scope, ShipmentStatus.DELIVERED);
            recentWalletTransactions = walletTransactionRepository.findTop5ByCompanyIdOrderByCreatedAtDesc(scope);
        }

        // Total Revenue is month-to-date, like every other tile here — the same figure (and
        // the same company/branch/cross-company scoping) as todayCollection above, no
        // longer an all-time sum.
        totalRevenue = todayCollection;

        BigDecimal walletBalance = ownWallet == null ? null : ownWallet.getAvailableBalance();
        // No own branch (company/platform admins) means no "Pending Delivery" tile is
        // shown for them either — 0 is a safe, unused default, not a fabricated figure.
        // A caller with an own branch is by construction never the cross-tenant
        // SUPER_ADMIN case (that role has no branch), so `scope` is always their real
        // company here.
        // Row-returning, not the plain count, so the branch-scoped card below can bucket
        // these same shipments by how long they've sat since being received — no second
        // query for an identical shipment set.
        List<Shipment> pendingDeliveryShipments = ownWallet == null ? List.of()
                : shipmentRepository.findByCompanyIdAndDeliveryBranchIdAndStatusIn(
                        scope, ownBranchId, PENDING_DELIVERY);
        long pendingDelivery = pendingDeliveryShipments.size();

        DashboardStatisticsResponse statistics = new DashboardStatisticsResponse(
                todayShipments, delivered, inTransit, pending, totalRevenue,
                todayShipments, todayCollection, pendingDelivery, totalShipments, walletBalance,
                totalActualWeight);

        // Company-wide, not branch-wide: shown only for a caller with no own branch (the
        // same "ownWallet == null" test the existing Pending Delivery tile already uses to
        // mean "company/platform admin"), and only for a real company (never the genuinely
        // cross-tenant SUPER_ADMIN read — "every company at once" has no single wallet
        // total or pipeline to show).
        CompanyOverviewResponse companyOverview = (!crossTenant && ownWallet == null)
                ? companyOverview(scope, monthStart, today) : null;

        // The exact opposite condition: a caller WITH an own branch gets the branch-scoped
        // sibling instead — never both, the two sections cover mutually exclusive callers.
        BranchOverviewResponse branchOverview = ownBranchId != null
                ? branchOverview(scope, ownBranchId, pendingDelivery, pendingDeliveryShipments, monthStart, today)
                : null;

        return new DashboardSummaryResponse(statistics, recentShipments(recent),
                recentActivity(recent, recentDeliveries, recentWalletTransactions, crossTenant, scope),
                companyOverview, branchOverview, charts(crossTenant, scope, ownBranchId, today));
    }

    /**
     * Trailing {@link #TREND_DAYS}-day daily aggregates for the three dashboard charts.
     * Each grouped query returns only the days that had activity — zero-filled here
     * against a full contiguous date range so the chart's x-axis never skips a day.
     */
    private DashboardChartsResponse charts(boolean crossTenant, UUID scope, UUID branchId, LocalDate today) {
        LocalDate trendStart = today.minusDays(TREND_DAYS - 1);
        List<LocalDate> days = trendStart.datesUntil(today.plusDays(1)).toList();

        List<ShipmentRepository.DailyCountRow> shipmentRows = crossTenant
                ? CompanyContext.<List<ShipmentRepository.DailyCountRow>>runAs(null,
                        () -> shipmentRepository.countDailyByBookingDateBetween(trendStart, today))
                : branchId != null
                        ? shipmentRepository.countDailyByCompanyIdAndBookingBranchIdAndBookingDateBetween(
                                scope, branchId, trendStart, today)
                        : shipmentRepository.countDailyByCompanyIdAndBookingDateBetween(scope, trendStart, today);
        Map<LocalDate, Long> shipmentByDay = shipmentRows.stream().collect(
                Collectors.toMap(ShipmentRepository.DailyCountRow::getDay, ShipmentRepository.DailyCountRow::getCount));
        List<ChartSeriesResponse> shipmentTrend = List.of(new ChartSeriesResponse("Bookings",
                days.stream().map(d -> new ChartPointResponse(CHART_LABEL.format(d),
                        BigDecimal.valueOf(shipmentByDay.getOrDefault(d, 0L)))).toList()));

        // No profile shows this chart cross-tenant (PLATFORM's own layout leaves it off) —
        // skip the query entirely rather than run a meaningless whole-platform breakdown.
        List<ChartSeriesResponse> deliveryPerformance = List.of();
        if (!crossTenant) {
            List<ShipmentRepository.DailyDeliveryPerformanceRow> perfRows = branchId != null
                    ? shipmentRepository.dailyDeliveryPerformanceByCompanyIdAndBookingBranchIdAndBookingDateBetween(
                            scope, branchId, ShipmentStatus.DELIVERED, IN_TRANSIT, PENDING, trendStart, today)
                    : shipmentRepository.dailyDeliveryPerformanceByCompanyIdAndBookingDateBetween(
                            scope, ShipmentStatus.DELIVERED, IN_TRANSIT, PENDING, trendStart, today);
            Map<LocalDate, ShipmentRepository.DailyDeliveryPerformanceRow> perfByDay = perfRows.stream()
                    .collect(Collectors.toMap(ShipmentRepository.DailyDeliveryPerformanceRow::getDay, r -> r));
            deliveryPerformance = List.of(
                    new ChartSeriesResponse("Delivered", days.stream().map(d -> new ChartPointResponse(
                            CHART_LABEL.format(d), BigDecimal.valueOf(perfDay(perfByDay, d,
                                    ShipmentRepository.DailyDeliveryPerformanceRow::getDelivered)))).toList()),
                    new ChartSeriesResponse("In Transit", days.stream().map(d -> new ChartPointResponse(
                            CHART_LABEL.format(d), BigDecimal.valueOf(perfDay(perfByDay, d,
                                    ShipmentRepository.DailyDeliveryPerformanceRow::getInTransit)))).toList()),
                    new ChartSeriesResponse("Pending", days.stream().map(d -> new ChartPointResponse(
                            CHART_LABEL.format(d), BigDecimal.valueOf(perfDay(perfByDay, d,
                                    ShipmentRepository.DailyDeliveryPerformanceRow::getPending)))).toList())
            );
        }

        List<ShipmentChargeRepository.DailyRevenueRow> revenueRows = crossTenant
                ? CompanyContext.<List<ShipmentChargeRepository.DailyRevenueRow>>runAs(null,
                        () -> shipmentChargeRepository.dailyRevenueByBookingDateBetween(trendStart, today))
                : branchId != null
                        ? shipmentChargeRepository.dailyRevenueByCompanyIdAndBookingBranchIdAndBookingDateBetween(
                                scope, branchId, trendStart, today)
                        : shipmentChargeRepository.dailyRevenueByCompanyIdAndBookingDateBetween(scope, trendStart, today);
        Map<LocalDate, BigDecimal> revenueByDay = revenueRows.stream().collect(Collectors.toMap(
                ShipmentChargeRepository.DailyRevenueRow::getDay, ShipmentChargeRepository.DailyRevenueRow::getRevenue));
        List<ChartSeriesResponse> revenueTrend = List.of(new ChartSeriesResponse("Revenue",
                days.stream().map(d -> new ChartPointResponse(CHART_LABEL.format(d),
                        revenueByDay.getOrDefault(d, BigDecimal.ZERO))).toList()));

        return new DashboardChartsResponse(shipmentTrend, deliveryPerformance, revenueTrend);
    }

    private static long perfDay(Map<LocalDate, ShipmentRepository.DailyDeliveryPerformanceRow> byDay,
            LocalDate day, java.util.function.ToLongFunction<ShipmentRepository.DailyDeliveryPerformanceRow> field) {
        ShipmentRepository.DailyDeliveryPerformanceRow row = byDay.get(day);
        return row == null ? 0L : field.applyAsLong(row);
    }

    private CompanyOverviewResponse companyOverview(UUID companyId, LocalDate monthStart, LocalDate today) {
        List<PipelineStageResponse> pipeline = PIPELINE_STAGES.stream()
                .map(status -> new PipelineStageResponse(status.name(),
                        shipmentRepository.countByCompanyIdAndStatusAndBookingDateBetween(
                                companyId, status, monthStart, today)))
                .toList();

        long readyForManifest = shipmentRepository.countByCompanyIdAndStatusIn(companyId, READY_FOR_MANIFEST);
        long manifestsAwaitingDispatch = manifestRepository.countByCompanyIdAndStatus(
                companyId, ManifestStatus.CREATED);
        long pendingDeliveryCompanyWide = shipmentRepository.countByCompanyIdAndStatusIn(
                companyId, PENDING_DELIVERY);
        long delayedShipments = shipmentRepository.countByCompanyIdAndStatusNotInAndBookingDateBefore(
                companyId, DELAY_EXCLUDED, today.minusDays(DELAYED_AFTER_DAYS));

        BigDecimal totalWalletBalance = walletRepository.sumAvailableBalanceByCompanyId(companyId);
        if (totalWalletBalance == null) {
            totalWalletBalance = BigDecimal.ZERO;
        }
        long lowBalanceBranches = walletRepository.countByCompanyIdAndAvailableBalanceLessThan(
                companyId, LOW_BALANCE_THRESHOLD);

        List<UUID> toPayModeIds = toPayModeIds(companyId);
        long toPayAwaitingDelivery = toPayModeIds.isEmpty() ? 0L
                : shipmentRepository.countByCompanyIdAndStatusInAndPaymentModeIdIn(
                        companyId, PENDING_DELIVERY, toPayModeIds);

        return new CompanyOverviewResponse(pipeline, readyForManifest, manifestsAwaitingDispatch,
                pendingDeliveryCompanyWide, delayedShipments, totalWalletBalance, lowBalanceBranches,
                topRoutes(companyId, monthStart, today), topCustomers(companyId, monthStart, today),
                podOverview(companyId, null), toPayAwaitingDelivery);
    }

    /**
     * Branch-scoped sibling of {@link #companyOverview}: same pipeline/action-required
     * shape, filtered to the caller's own branch via {@code currentLocationId} rather than
     * left company-wide. {@code pendingDelivery} is passed in rather than requeried — it's
     * the exact same figure {@link #summary()} already computed for the Pending Delivery
     * KPI tile (both mean "arrived/out for delivery at this branch"), so recomputing it
     * here would just be a duplicate query for an identical number.
     */
    private BranchOverviewResponse branchOverview(UUID companyId, UUID branchId, long pendingDelivery,
            List<Shipment> pendingDeliveryShipments, LocalDate monthStart, LocalDate today) {
        List<PipelineStageResponse> pipeline = PIPELINE_STAGES.stream()
                .map(status -> new PipelineStageResponse(status.name(),
                        shipmentRepository.countByCompanyIdAndCurrentLocationIdAndStatusAndBookingDateBetween(
                                companyId, branchId, status, monthStart, today)))
                .toList();

        long readyForManifest = shipmentRepository.countByCompanyIdAndCurrentLocationIdAndStatusIn(
                companyId, branchId, READY_FOR_MANIFEST);
        long manifestsAwaitingDispatch = manifestRepository.countByCompanyIdAndBookingBranchIdAndStatus(
                companyId, branchId, ManifestStatus.CREATED);
        long delayedShipments = shipmentRepository.countByCompanyIdAndCurrentLocationIdAndStatusNotInAndBookingDateBefore(
                companyId, branchId, DELAY_EXCLUDED, today.minusDays(DELAYED_AFTER_DAYS));

        // HashSet, not Set.of/copyOf: a shipment with no payment mode recorded yet would
        // otherwise NPE on contains(null) — Set.of's immutable sets reject a null probe
        // outright rather than just answering false.
        Set<UUID> toPayModeIds = new java.util.HashSet<>(toPayModeIds(companyId));
        long toPayAwaitingDelivery = pendingDeliveryShipments.stream()
                .filter(s -> toPayModeIds.contains(s.getPaymentModeId()))
                .count();

        long inScanPending = shipmentRepository.countInScanPendingByCompanyIdAndBranchId(
                companyId, branchId, ShipmentStatus.DISPATCHED);

        return new BranchOverviewResponse(pipeline, readyForManifest, manifestsAwaitingDispatch,
                pendingDelivery, delayedShipments, deliveryPendingAging(companyId, pendingDeliveryShipments),
                podOverview(companyId, branchId), rejectedPods(companyId, branchId), toPayAwaitingDelivery,
                inScanPending);
    }

    /** TO_PAY-shaped payment mode ids for a company — collects at delivery, but not
     *  cash-on-delivery (the consignee's amount). Backs the dashboard's "TO_PAY awaiting
     *  delivery" tile: shipments already debited at THC in-scan (see {@code
     *  ShipmentServiceImpl.scanOneIn}'s {@code ToPayReceivedAtDeliveryBranch}) but not yet
     *  actually delivered. */
    private List<UUID> toPayModeIds(UUID companyId) {
        return paymentModeRepository.findByCompanyIdAndCollectAtDeliveryTrueAndCashOnDeliveryFalse(companyId)
                .stream().map(PaymentMode::getId).toList();
    }

    /**
     * POD Dashboard pie for the given scope — every branch's own state (company-wide when
     * {@code branchId} is null). A candidate shipment missing from the latest-verification
     * map has never had a POD run at all ({@code pendingUpload}); otherwise its own latest
     * run's status places it in exactly one of the other three buckets.
     */
    private PodOverviewResponse podOverview(UUID companyId, UUID branchId) {
        List<UUID> candidateIds = branchId == null
                ? shipmentRepository.findIdsByCompanyIdAndStatusIn(companyId, POD_RELEVANT)
                : shipmentRepository.findIdsByCompanyIdAndDeliveryBranchIdAndStatusIn(companyId, branchId, POD_RELEVANT);
        if (candidateIds.isEmpty()) {
            return new PodOverviewResponse(0, 0, 0, 0);
        }
        Map<UUID, PodVerification> latest = podVerificationService.latestByShipmentIds(candidateIds);
        long pendingVerification = countByStatus(latest, PodVerificationStatus.PENDING);
        long approved = countByStatus(latest, PodVerificationStatus.PASS);
        long rejected = countByStatus(latest, PodVerificationStatus.FAIL);
        long pendingUpload = candidateIds.size() - latest.size();
        return new PodOverviewResponse(pendingUpload, pendingVerification, approved, rejected);
    }

    private static long countByStatus(Map<UUID, PodVerification> latest, PodVerificationStatus status) {
        return latest.values().stream().filter(v -> v.getVerificationStatus() == status).count();
    }

    /** The branch dashboard's own "Rejected POD — option to upload" backlog: every shipment
     *  in this branch whose latest POD verification is FAIL, capped at {@link
     *  #REJECTED_POD_LIMIT}. Separate query from {@link #podOverview} (the count there is
     *  enough for the pie; this one also needs each shipment's own number/receiver to
     *  render a real row, not just a total). */
    private List<RejectedPodResponse> rejectedPods(UUID companyId, UUID branchId) {
        List<UUID> candidateIds = shipmentRepository.findIdsByCompanyIdAndDeliveryBranchIdAndStatusIn(
                companyId, branchId, POD_RELEVANT);
        if (candidateIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, PodVerification> latest = podVerificationService.latestByShipmentIds(candidateIds);
        List<PodVerification> rejected = latest.values().stream()
                .filter(v -> v.getVerificationStatus() == PodVerificationStatus.FAIL)
                .limit(REJECTED_POD_LIMIT)
                .toList();
        if (rejected.isEmpty()) {
            return List.of();
        }
        List<UUID> rejectedShipmentIds = rejected.stream().map(PodVerification::getShipmentId).toList();
        Map<UUID, Shipment> shipmentsById = shipmentRepository
                .findAllByCompanyIdAndIdIn(companyId, rejectedShipmentIds).stream()
                .collect(Collectors.toMap(Shipment::getId, s -> s));
        return rejected.stream()
                .map(v -> {
                    Shipment s = shipmentsById.get(v.getShipmentId());
                    String reason = v.reasons().isEmpty() ? v.getReviewRemarks() : String.join("; ", v.reasons());
                    return new RejectedPodResponse(v.getShipmentId(),
                            s == null ? null : s.getShipmentNumber(),
                            s == null ? null : s.getReceiverName(), reason);
                })
                .toList();
    }

    /** Buckets the branch's own Pending Delivery shipments (IN_SCAN/OUT_FOR_DELIVERY) by how
     *  long since each was received — its last IN_SCAN scan-in, not its booking date, since
     *  a shipment can sit booked for a while before ever reaching this branch. Ranges are
     *  half-open on the low end so every shipment lands in exactly one bucket: [24,36),
     *  [36,48), [48,72), [72,&#8734;). Under 24h isn't a breach yet, so it's simply not
     *  counted anywhere — same "omit the not-yet-a-problem case" convention as the rest of
     *  this class. */
    private List<DeliveryAgingBucketResponse> deliveryPendingAging(UUID companyId, List<Shipment> shipments) {
        if (shipments.isEmpty()) {
            return List.of(
                    new DeliveryAgingBucketResponse("Since 24h", 0L),
                    new DeliveryAgingBucketResponse("Since 36h", 0L),
                    new DeliveryAgingBucketResponse("Since 48h", 0L),
                    new DeliveryAgingBucketResponse("72h+", 0L));
        }
        List<UUID> ids = shipments.stream().map(Shipment::getId).toList();
        Map<UUID, Instant> receivedAt = shipmentStatusHistoryRepository
                .findAllByCompanyIdAndShipmentIdInAndStatus(companyId, ids, ShipmentStatus.IN_SCAN)
                .stream()
                .collect(Collectors.toMap(ShipmentStatusHistory::getShipmentId, ShipmentStatusHistory::getChangedAt,
                        (a, b) -> a.isAfter(b) ? a : b));

        Instant now = Instant.now();
        long since24 = 0, since36 = 0, since48 = 0, since72 = 0;
        for (Shipment s : shipments) {
            Instant received = receivedAt.get(s.getId());
            // No IN_SCAN history shouldn't happen for IN_SCAN/OUT_FOR_DELIVERY — defensive,
            // not fabricated: an un-received shipment simply isn't aged into any bucket.
            if (received == null) continue;
            long hours = Duration.between(received, now).toHours();
            if (hours >= 72) since72++;
            else if (hours >= 48) since48++;
            else if (hours >= 36) since36++;
            else if (hours >= 24) since24++;
        }

        return List.of(
                new DeliveryAgingBucketResponse("Since 24h", since24),
                new DeliveryAgingBucketResponse("Since 36h", since36),
                new DeliveryAgingBucketResponse("Since 48h", since48),
                new DeliveryAgingBucketResponse("72h+", since72));
    }

    private List<TopRouteResponse> topRoutes(UUID companyId, LocalDate monthStart, LocalDate today) {
        List<ShipmentRepository.TopRouteRow> rows = shipmentRepository
                .findTopRoutesByCompanyIdAndBookingDateBetween(companyId, monthStart, today,
                        PageRequest.of(0, TOP_N));
        List<UUID> branchIds = rows.stream()
                .map(r -> TimeOrderedUuid.fromBytes(r.getBranchId()))
                .filter(java.util.Objects::nonNull)
                .toList();
        Map<UUID, DashboardBranchDirectoryPort.BranchRef> branches = branchDirectory.findBranches(
                branchIds, companyId);
        return rows.stream()
                .map(r -> {
                    UUID branchId = TimeOrderedUuid.fromBytes(r.getBranchId());
                    DashboardBranchDirectoryPort.BranchRef b = branchId == null ? null : branches.get(branchId);
                    return new TopRouteResponse(branchId,
                            b == null ? null : b.branchCode(), b == null ? "Unknown branch" : b.branchName(),
                            r.getShipmentCount(), r.getRevenue());
                })
                .toList();
    }

    private List<TopCustomerResponse> topCustomers(UUID companyId, LocalDate monthStart, LocalDate today) {
        return shipmentRepository
                .findTopCustomersByCompanyIdAndBookingDateBetween(companyId, monthStart, today,
                        PageRequest.of(0, TOP_N))
                .stream()
                .map(r -> new TopCustomerResponse(r.getName(), r.getContact(), r.getShipmentCount(), r.getRevenue()))
                .toList();
    }

    /**
     * The caller's own branch wallet, or null for a caller with no own branch
     * (company/platform admins) — {@code WalletService.getForBranch(null)} throws
     * {@link BusinessRuleException} for them, which the dashboard degrades rather than
     * surfaces, the same convention as every other omitted figure in this response.
     */
    private Wallet ownWallet() {
        try {
            return walletService.getForBranch(null);
        } catch (BusinessRuleException e) {
            return null;
        }
    }

    /**
     * Merges recent bookings, deliveries and wallet moves into one time-sorted feed for the
     * dashboard's Recent Activity timeline. No {@code SYSTEM}-kind source exists yet (no
     * readable event log backs it) — omitted rather than fabricated, same convention as
     * every other not-yet-built figure in this response.
     */
    private List<DashboardActivityResponse> recentActivity(List<Shipment> recentBookings,
            List<ShipmentStatusHistory> recentDeliveries, List<WalletTransaction> recentWalletTransactions,
            boolean crossTenant, UUID scope) {
        List<UUID> bookingIds = recentBookings.stream().map(Shipment::getId).toList();
        Map<UUID, BigDecimal> bookingAmounts = shipmentChargeRepository.findByShipmentIdIn(bookingIds).stream()
                .collect(Collectors.toMap(ShipmentCharge::getShipmentId, ShipmentCharge::getNetAmount));

        List<UUID> deliveredShipmentIds = recentDeliveries.stream()
                .map(ShipmentStatusHistory::getShipmentId).toList();
        List<Shipment> deliveredShipments = deliveredShipmentIds.isEmpty() ? List.of()
                : crossTenant
                        ? CompanyContext.<List<Shipment>>runAs(null,
                                () -> shipmentRepository.findAllById(deliveredShipmentIds))
                        : shipmentRepository.findAllByCompanyIdAndIdIn(scope, deliveredShipmentIds);
        Map<UUID, Shipment> deliveredShipmentsById = deliveredShipments.stream()
                .collect(Collectors.toMap(Shipment::getId, s -> s));

        Stream<DashboardActivityResponse> bookings = recentBookings.stream()
                .map((Shipment s) -> new DashboardActivityResponse(
                        "booking-" + s.getId(), "BOOKING", "New booking",
                        s.getTrackingNumber() + " to " + s.getReceiverName(),
                        s.getCreatedAt(), bookingAmounts.get(s.getId())));

        Stream<DashboardActivityResponse> deliveries = recentDeliveries.stream()
                .map((ShipmentStatusHistory h) -> {
                    Shipment s = deliveredShipmentsById.get(h.getShipmentId());
                    return new DashboardActivityResponse(
                            "delivery-" + h.getId(), "DELIVERY", "Shipment delivered",
                            s == null ? null : s.getTrackingNumber() + " to " + s.getReceiverName(),
                            h.getChangedAt(), null);
                });

        Stream<DashboardActivityResponse> wallet = recentWalletTransactions.stream()
                .map((WalletTransaction t) -> new DashboardActivityResponse(
                        "wallet-" + t.getId(), "WALLET", t.getSubTransactionType().getLabel(),
                        t.getRemarks(), t.getCreatedAt(), t.getSignedAmount()));

        return Stream.of(bookings, deliveries, wallet)
                .flatMap(s -> s)
                .sorted(Comparator.comparing(DashboardActivityResponse::at).reversed())
                .limit(ACTIVITY_LIMIT)
                .toList();
    }

    private List<RecentShipmentResponse> recentShipments(List<Shipment> recent) {
        List<UUID> ids = recent.stream().map(Shipment::getId).toList();
        Map<UUID, BigDecimal> amountByShipmentId = shipmentChargeRepository.findByShipmentIdIn(ids).stream()
                .collect(Collectors.toMap(ShipmentCharge::getShipmentId, ShipmentCharge::getNetAmount));

        return recent.stream()
                .map((Shipment s) -> new RecentShipmentResponse(
                        s.getId(),
                        s.getTrackingNumber(),
                        s.getReceiverName(),
                        s.getDeliveryPincode(),
                        s.getStatus().name(),
                        s.getCreatedAt(),
                        amountByShipmentId.get(s.getId())))
                .collect(Collectors.toList());
    }
}
