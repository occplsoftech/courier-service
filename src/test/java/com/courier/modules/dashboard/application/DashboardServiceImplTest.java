package com.courier.modules.dashboard.application;

import com.courier.modules.dashboard.api.dto.DashboardSummaryResponse;
import com.courier.modules.dashboard.domain.DashboardBranchDirectoryPort;
import com.courier.modules.finance.application.WalletService;
import com.courier.modules.finance.domain.Wallet;
import com.courier.modules.finance.domain.WalletRepository;
import com.courier.modules.finance.domain.WalletTransactionRepository;
import com.courier.modules.manifest.domain.ManifestRepository;
import com.courier.modules.manifest.domain.ManifestStatus;
import com.courier.modules.master.domain.PaymentModeRepository;
import com.courier.modules.pod.application.PodVerificationService;
import com.courier.modules.pod.domain.PodVerification;
import com.courier.modules.pod.domain.PodVerificationStatus;
import com.courier.modules.shipment.domain.Shipment;
import com.courier.modules.shipment.domain.ShipmentChargeRepository;
import com.courier.modules.shipment.domain.ShipmentRepository;
import com.courier.modules.shipment.domain.ShipmentStatus;
import com.courier.modules.shipment.domain.ShipmentStatusHistory;
import com.courier.modules.shipment.domain.ShipmentStatusHistoryRepository;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.security.AuthenticatedUser;
import com.courier.shared.security.Roles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ISSUE-001 regression coverage (see {@code perf-tests/ISSUES.md} in the repo root):
 * a {@code COMPANY_ADMIN} dashboard call must never touch the unscoped/cross-tenant
 * repository methods, and a {@code SUPER_ADMIN} call must never be scoped to a real
 * company id (which would silently filter against the sentinel company id their own
 * JWT carries, per {@link CompanyContext}'s own javadoc).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DashboardServiceImplTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID CALLER = UUID.randomUUID();

    @Mock private ShipmentRepository shipmentRepository;
    @Mock private ShipmentChargeRepository shipmentChargeRepository;
    @Mock private ShipmentStatusHistoryRepository shipmentStatusHistoryRepository;
    @Mock private WalletTransactionRepository walletTransactionRepository;
    @Mock private WalletService walletService;
    @Mock private ManifestRepository manifestRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private DashboardBranchDirectoryPort branchDirectory;
    @Mock private PodVerificationService podVerificationService;
    @Mock private PaymentModeRepository paymentModeRepository;

    private DashboardServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DashboardServiceImpl(shipmentRepository, shipmentChargeRepository,
                shipmentStatusHistoryRepository, walletTransactionRepository, walletService,
                manifestRepository, walletRepository, branchDirectory, podVerificationService,
                paymentModeRepository);
        // Every caller in these tests has no own branch (company/platform admins) —
        // the same degrade-rather-than-throw path DashboardServiceImpl.ownWallet()
        // already documents.
        when(walletService.getForBranch(null)).thenThrow(new BusinessRuleException("no own branch"));
        // No TO_PAY-shaped payment modes stubbed by default — the "TO_PAY awaiting
        // delivery" tile degrades to 0 rather than NPEing on an un-stubbed mock.
        when(paymentModeRepository.findByCompanyIdAndCollectAtDeliveryTrueAndCashOnDeliveryFalse(any()))
                .thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        CompanyContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("COMPANY_ADMIN: every figure is scoped to the caller's own company, never the unscoped cross-tenant methods")
    void companyAdminIsScopedToOwnCompany() {
        CompanyContext.setCompanyId(COMPANY);
        signedIn(Roles.COMPANY_ADMIN);

        when(shipmentRepository.countByCompanyIdAndStatusNotAndBookingDateBetween(
                eq(COMPANY), eq(ShipmentStatus.CANCELLED), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(1L);
        when(shipmentRepository.countByCompanyIdAndStatusAndBookingDateBetween(
                eq(COMPANY), eq(ShipmentStatus.DELIVERED), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(2L);
        when(shipmentRepository.countByCompanyIdAndStatusInAndBookingDateBetween(
                eq(COMPANY), any(Collection.class), any(LocalDate.class), any(LocalDate.class))).thenReturn(3L);
        when(shipmentRepository.countByCompanyIdAndStatusNot(COMPANY, ShipmentStatus.CANCELLED)).thenReturn(4L);
        when(shipmentChargeRepository.sumNetAmountByCompanyIdAndBookingDateBetween(
                eq(COMPANY), any(LocalDate.class), any(LocalDate.class))).thenReturn(BigDecimal.ONE);
        when(shipmentRepository.findTop5ByCompanyIdOrderByCreatedAtDesc(COMPANY)).thenReturn(List.of());
        when(shipmentStatusHistoryRepository.findTop5ByCompanyIdAndStatusOrderByChangedAtDesc(
                COMPANY, ShipmentStatus.DELIVERED)).thenReturn(List.of());
        when(walletTransactionRepository.findTop5ByCompanyIdOrderByCreatedAtDesc(COMPANY)).thenReturn(List.of());

        // Company Overview — computed because this caller (COMPANY_ADMIN) has no own
        // branch, see DashboardServiceImpl.ownWallet().
        when(shipmentRepository.countByCompanyIdAndStatusIn(eq(COMPANY), any(Collection.class))).thenReturn(5L);
        when(manifestRepository.countByCompanyIdAndStatus(COMPANY, ManifestStatus.CREATED)).thenReturn(6L);
        when(shipmentRepository.countByCompanyIdAndStatusNotInAndBookingDateBefore(
                eq(COMPANY), any(Collection.class), any(LocalDate.class))).thenReturn(7L);
        when(walletRepository.sumAvailableBalanceByCompanyId(COMPANY)).thenReturn(new BigDecimal("500"));
        when(walletRepository.countByCompanyIdAndAvailableBalanceLessThan(COMPANY, new BigDecimal("1000")))
                .thenReturn(2L);
        com.courier.modules.master.domain.PaymentMode toPayMode = new com.courier.modules.master.domain.PaymentMode();
        toPayMode.setId(UUID.randomUUID());
        when(paymentModeRepository.findByCompanyIdAndCollectAtDeliveryTrueAndCashOnDeliveryFalse(COMPANY))
                .thenReturn(List.of(toPayMode));
        when(shipmentRepository.countByCompanyIdAndStatusInAndPaymentModeIdIn(
                eq(COMPANY), any(Collection.class), eq(List.of(toPayMode.getId())))).thenReturn(9L);
        when(shipmentRepository.findTopRoutesByCompanyIdAndBookingDateBetween(
                eq(COMPANY), any(LocalDate.class), any(LocalDate.class), any(Pageable.class))).thenReturn(List.of());
        when(shipmentRepository.findTopCustomersByCompanyIdAndBookingDateBetween(
                eq(COMPANY), any(LocalDate.class), any(LocalDate.class), any(Pageable.class))).thenReturn(List.of());
        when(branchDirectory.findBranches(any(Collection.class), eq(COMPANY))).thenReturn(Map.of());

        // Charts — one real day of activity, the rest of the 14-day window zero-filled.
        LocalDate today = LocalDate.now();
        when(shipmentRepository.countDailyByCompanyIdAndBookingDateBetween(
                eq(COMPANY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(dailyCount(today, 9L)));
        when(shipmentRepository.dailyDeliveryPerformanceByCompanyIdAndBookingDateBetween(
                eq(COMPANY), eq(ShipmentStatus.DELIVERED), any(Collection.class), any(Collection.class),
                any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(dailyPerf(today, 3L, 2L, 1L)));
        when(shipmentChargeRepository.dailyRevenueByCompanyIdAndBookingDateBetween(
                eq(COMPANY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(dailyRevenue(today, new BigDecimal("42"))));

        // POD Dashboard pie, company-wide: 2 candidates, both missing from the
        // verification map — both pendingUpload, nothing else.
        UUID podShipmentA = UUID.randomUUID();
        UUID podShipmentB = UUID.randomUUID();
        when(shipmentRepository.findIdsByCompanyIdAndStatusIn(eq(COMPANY), any()))
                .thenReturn(List.of(podShipmentA, podShipmentB));
        when(podVerificationService.latestByShipmentIds(any())).thenReturn(Map.of());

        DashboardSummaryResponse response = service.summary();

        assertThat(response.companyOverview()).isNotNull();
        assertThat(response.companyOverview().pipeline()).hasSize(7);
        assertThat(response.companyOverview().manifestsAwaitingDispatch()).isEqualTo(6L);
        assertThat(response.companyOverview().totalWalletBalance()).isEqualByComparingTo("500");
        assertThat(response.companyOverview().lowBalanceBranches()).isEqualTo(2L);
        assertThat(response.companyOverview().toPayAwaitingDelivery()).isEqualTo(9L);
        assertThat(response.companyOverview().podOverview().pendingUpload()).isEqualTo(2L);
        assertThat(response.companyOverview().podOverview().approved()).isEqualTo(0L);

        assertThat(response.charts()).isNotNull();
        assertThat(response.charts().shipmentTrend()).hasSize(1);
        assertThat(response.charts().shipmentTrend().get(0).points()).hasSize(14);
        assertThat(response.charts().shipmentTrend().get(0).points().get(13).value()).isEqualByComparingTo("9");
        assertThat(response.charts().shipmentTrend().get(0).points().get(0).value()).isEqualByComparingTo("0");
        assertThat(response.charts().deliveryPerformance()).hasSize(3);
        assertThat(response.charts().revenueTrend().get(0).points().get(13).value()).isEqualByComparingTo("42");

        verify(shipmentRepository).countByCompanyIdAndStatusNot(COMPANY, ShipmentStatus.CANCELLED);
        // Called twice: once for the "Delivered This Month" stat tile, once more as the
        // pipeline's own DELIVERED stage (companyOverview()).
        verify(shipmentRepository, org.mockito.Mockito.times(2)).countByCompanyIdAndStatusAndBookingDateBetween(
                eq(COMPANY), eq(ShipmentStatus.DELIVERED), any(LocalDate.class), any(LocalDate.class));
        verify(shipmentRepository).findTop5ByCompanyIdOrderByCreatedAtDesc(COMPANY);
        // Total Revenue is month-to-date now — the all-time sum is never queried.
        verify(shipmentChargeRepository, never()).sumNetAmountByCompanyId(any());
        verify(shipmentStatusHistoryRepository).findTop5ByCompanyIdAndStatusOrderByChangedAtDesc(
                COMPANY, ShipmentStatus.DELIVERED);
        verify(walletTransactionRepository).findTop5ByCompanyIdOrderByCreatedAtDesc(COMPANY);

        // The whole point of ISSUE-001: none of the unscoped/cross-tenant methods may
        // ever be reached for a company-bound caller.
        verify(shipmentRepository, never()).count();
        verify(shipmentRepository, never()).countByStatus(any());
        verify(shipmentRepository, never()).countByStatusIn(any());
        verify(shipmentRepository, never()).countByStatusAndBookingDateBetween(any(), any(), any());
        verify(shipmentRepository, never()).countByStatusInAndBookingDateBetween(any(), any(), any());
        verify(shipmentRepository, never()).countByBookingDateBetween(any(), any());
        verify(shipmentRepository, never()).findTop5ByOrderByCreatedAtDesc();
        verify(shipmentChargeRepository, never()).sumNetAmount();
        verify(shipmentChargeRepository, never()).sumNetAmountForBookingDateBetween(any(), any());
        verify(shipmentStatusHistoryRepository, never()).findTop5ByStatusOrderByChangedAtDesc(any());
        verify(walletTransactionRepository, never()).findTop5ByOrderByCreatedAtDesc();
    }

    @Test
    @DisplayName("SUPER_ADMIN: genuinely cross-tenant, never filtered against the sentinel company id")
    void superAdminIsGenuinelyCrossTenant() {
        // Deliberately NOT binding CompanyContext to a real company here — mirrors the
        // sentinel-cid gotcha this whole bug traces back to: a SUPER_ADMIN's JWT binds
        // a non-null sentinel, so the fix must branch on caller.isSuperAdmin(), never on
        // "is CompanyContext empty".
        signedIn(Roles.SUPER_ADMIN);

        when(shipmentRepository.countByStatusNotAndBookingDateBetween(
                eq(ShipmentStatus.CANCELLED), any(LocalDate.class), any(LocalDate.class))).thenReturn(10L);
        when(shipmentRepository.countByStatusAndBookingDateBetween(
                eq(ShipmentStatus.DELIVERED), any(LocalDate.class), any(LocalDate.class))).thenReturn(20L);
        when(shipmentRepository.countByStatusInAndBookingDateBetween(
                any(Collection.class), any(LocalDate.class), any(LocalDate.class))).thenReturn(30L);
        when(shipmentRepository.countByStatusNot(ShipmentStatus.CANCELLED)).thenReturn(40L);
        when(shipmentChargeRepository.sumNetAmountForBookingDateBetween(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(BigDecimal.ONE);
        when(shipmentRepository.findTop5ByOrderByCreatedAtDesc()).thenReturn(List.of());
        when(shipmentStatusHistoryRepository.findTop5ByStatusOrderByChangedAtDesc(ShipmentStatus.DELIVERED))
                .thenReturn(List.of());
        when(walletTransactionRepository.findTop5ByOrderByCreatedAtDesc()).thenReturn(List.of());

        LocalDate today = LocalDate.now();
        when(shipmentRepository.countDailyByBookingDateBetween(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(dailyCount(today, 15L)));
        when(shipmentChargeRepository.dailyRevenueByBookingDateBetween(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(dailyRevenue(today, new BigDecimal("99"))));

        DashboardSummaryResponse response = service.summary();

        // Genuinely cross-tenant (no single company) — no companyOverview, and none of
        // its company-scoped repository calls are ever reached.
        assertThat(response.companyOverview()).isNull();
        assertThat(response.charts().shipmentTrend().get(0).points().get(13).value()).isEqualByComparingTo("15");
        // No profile shows Delivery Performance cross-tenant — the query never runs.
        assertThat(response.charts().deliveryPerformance()).isEmpty();
        verify(shipmentRepository, never()).dailyDeliveryPerformanceByCompanyIdAndBookingDateBetween(
                any(), any(), any(), any(), any(), any());
        verify(manifestRepository, never()).countByCompanyIdAndStatus(any(), any());
        verify(walletRepository, never()).sumAvailableBalanceByCompanyId(any());
        verify(walletRepository, never()).countByCompanyIdAndAvailableBalanceLessThan(any(), any());
        verify(shipmentRepository, never()).countByCompanyIdAndStatusIn(any(), any());
        verify(shipmentRepository, never())
                .countByCompanyIdAndStatusNotInAndBookingDateBefore(any(), any(), any());

        verify(shipmentRepository).countByStatusNot(ShipmentStatus.CANCELLED);
        verify(shipmentRepository).countByStatusAndBookingDateBetween(
                eq(ShipmentStatus.DELIVERED), any(LocalDate.class), any(LocalDate.class));
        verify(shipmentRepository).findTop5ByOrderByCreatedAtDesc();
        verify(shipmentChargeRepository, never()).sumNetAmount();
        verify(shipmentStatusHistoryRepository).findTop5ByStatusOrderByChangedAtDesc(ShipmentStatus.DELIVERED);
        verify(walletTransactionRepository).findTop5ByOrderByCreatedAtDesc();

        verify(shipmentRepository, never()).countByStatus(any());
        verify(shipmentRepository, never()).countByStatusIn(any());
        verify(shipmentRepository, never()).countByCompanyId(any());
        verify(shipmentRepository, never()).countByCompanyIdAndStatus(any(), any());
        verify(shipmentRepository, never()).countByCompanyIdAndStatusIn(any(), any());
        verify(shipmentRepository, never()).countByCompanyIdAndStatusAndBookingDateBetween(any(), any(), any(), any());
        verify(shipmentRepository, never()).countByCompanyIdAndStatusInAndBookingDateBetween(any(), any(), any(), any());
        verify(shipmentRepository, never()).countByCompanyIdAndBookingDateBetween(any(), any(), any());
        verify(shipmentRepository, never()).findTop5ByCompanyIdOrderByCreatedAtDesc(any());
        verify(shipmentChargeRepository, never()).sumNetAmountByCompanyId(any());
        verify(shipmentChargeRepository, never()).sumNetAmountByCompanyIdAndBookingDateBetween(any(), any(), any());
        verify(shipmentStatusHistoryRepository, never()).findTop5ByCompanyIdAndStatusOrderByChangedAtDesc(any(), any());
        verify(walletTransactionRepository, never()).findTop5ByCompanyIdOrderByCreatedAtDesc(any());
    }

    @Test
    @DisplayName("BRANCH_MANAGER: gets branchOverview scoped to their own branch, never companyOverview")
    void branchManagerGetsBranchOverviewScopedToOwnBranch() {
        UUID branch = UUID.randomUUID();
        CompanyContext.setCompanyId(COMPANY);
        signedIn(Roles.BRANCH_MANAGER);

        Wallet ownWallet = Wallet.builder().branchId(branch).availableBalance(new BigDecimal("250")).build();
        org.mockito.Mockito.reset(walletService);
        when(walletService.getForBranch(null)).thenReturn(ownWallet);

        when(shipmentRepository.countByCompanyIdAndBookingBranchIdAndStatusNotAndBookingDateBetween(
                eq(COMPANY), eq(branch), eq(ShipmentStatus.CANCELLED), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(1L);
        when(shipmentRepository.countByCompanyIdAndBookingBranchIdAndStatusAndBookingDateBetween(
                eq(COMPANY), eq(branch), any(ShipmentStatus.class), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(2L);
        when(shipmentRepository.countByCompanyIdAndBookingBranchIdAndStatusInAndBookingDateBetween(
                eq(COMPANY), eq(branch), any(Collection.class), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(3L);
        when(shipmentRepository.countByCompanyIdAndStatusNot(COMPANY, ShipmentStatus.CANCELLED)).thenReturn(4L);
        when(shipmentChargeRepository.sumNetAmountByCompanyIdAndBookingBranchIdAndBookingDateBetween(
                eq(COMPANY), eq(branch), any(LocalDate.class), any(LocalDate.class))).thenReturn(new BigDecimal("77"));
        when(shipmentRepository.findTop5ByCompanyIdAndBookingBranchIdOrderByCreatedAtDesc(
                eq(COMPANY), eq(branch))).thenReturn(List.of());
        when(shipmentStatusHistoryRepository.findTop5ByCompanyIdAndBranchIdAndStatusOrderByChangedAtDesc(
                eq(COMPANY), eq(branch), eq(ShipmentStatus.DELIVERED))).thenReturn(List.of());
        when(walletTransactionRepository.findRecent(any(UUID.class), eq(COMPANY), any())).thenReturn(List.of());

        // Pending Delivery: 2 shipments, received (last IN_SCAN) 40h and 80h ago — one
        // lands in the "Since 36h" aging bucket, the other in "72h+". Only pendingA is
        // TO_PAY — pendingB has no payment mode recorded (null), which must not NPE the
        // toPayAwaitingDelivery filter (Set.of/copyOf's contains(null) would).
        UUID toPayModeId = UUID.randomUUID();
        Shipment pendingA = Shipment.builder().paymentModeId(toPayModeId).build();
        Shipment pendingB = Shipment.builder().build();
        com.courier.modules.master.domain.PaymentMode toPayMode = new com.courier.modules.master.domain.PaymentMode();
        toPayMode.setId(toPayModeId);
        when(paymentModeRepository.findByCompanyIdAndCollectAtDeliveryTrueAndCashOnDeliveryFalse(COMPANY))
                .thenReturn(List.of(toPayMode));
        when(shipmentRepository.findByCompanyIdAndDeliveryBranchIdAndStatusIn(
                eq(COMPANY), eq(branch), any(Collection.class))).thenReturn(List.of(pendingA, pendingB));
        java.time.Instant now = java.time.Instant.now();
        when(shipmentStatusHistoryRepository.findAllByCompanyIdAndShipmentIdInAndStatus(
                eq(COMPANY), any(Collection.class), eq(ShipmentStatus.IN_SCAN))).thenReturn(List.of(
                        ShipmentStatusHistory.builder().shipmentId(pendingA.getId())
                                .status(ShipmentStatus.IN_SCAN).changedAt(now.minusSeconds(40 * 3600L)).build(),
                        ShipmentStatusHistory.builder().shipmentId(pendingB.getId())
                                .status(ShipmentStatus.IN_SCAN).changedAt(now.minusSeconds(80 * 3600L)).build()));

        // Charts — branch-scoped siblings, not the company-wide ones.
        LocalDate today = LocalDate.now();
        when(shipmentRepository.countDailyByCompanyIdAndBookingBranchIdAndBookingDateBetween(
                eq(COMPANY), eq(branch), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(dailyCount(today, 11L)));
        when(shipmentRepository.dailyDeliveryPerformanceByCompanyIdAndBookingBranchIdAndBookingDateBetween(
                eq(COMPANY), eq(branch), eq(ShipmentStatus.DELIVERED), any(Collection.class), any(Collection.class),
                any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(dailyPerf(today, 3L, 2L, 1L)));

        // Branch Overview — computed because this caller has an own branch wallet.
        when(shipmentRepository.countByCompanyIdAndCurrentLocationIdAndStatusAndBookingDateBetween(
                eq(COMPANY), eq(branch), any(ShipmentStatus.class), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(5L);
        when(shipmentRepository.countByCompanyIdAndCurrentLocationIdAndStatusIn(
                eq(COMPANY), eq(branch), any(Collection.class))).thenReturn(6L);
        when(manifestRepository.countByCompanyIdAndBookingBranchIdAndStatus(
                COMPANY, branch, ManifestStatus.CREATED)).thenReturn(7L);
        when(shipmentRepository.countByCompanyIdAndCurrentLocationIdAndStatusNotInAndBookingDateBefore(
                eq(COMPANY), eq(branch), any(Collection.class), any(LocalDate.class))).thenReturn(8L);
        when(shipmentRepository.countInScanPendingByCompanyIdAndBranchId(
                eq(COMPANY), eq(branch), eq(ShipmentStatus.DISPATCHED))).thenReturn(9L);

        // POD Dashboard pie + Rejected POD backlog: 4 candidate shipments at this branch —
        // one never had a POD run (pendingUpload, by omission from the map below), one
        // PENDING, one PASS, one FAIL (the rejected one, needing a re-upload).
        UUID noPodShipment = UUID.randomUUID();
        UUID pendingShipment = UUID.randomUUID();
        UUID passShipment = UUID.randomUUID();
        UUID failShipment = UUID.randomUUID();
        when(shipmentRepository.findIdsByCompanyIdAndDeliveryBranchIdAndStatusIn(eq(COMPANY), eq(branch), any()))
                .thenReturn(List.of(noPodShipment, pendingShipment, passShipment, failShipment));
        Shipment rejectedShipment = Shipment.builder().shipmentNumber("SHP-REJECTED").receiverName("Ramesh").build();
        rejectedShipment.setId(failShipment);
        when(shipmentRepository.findAllByCompanyIdAndIdIn(eq(COMPANY), any()))
                .thenReturn(List.of(rejectedShipment));
        when(podVerificationService.latestByShipmentIds(any())).thenReturn(Map.of(
                pendingShipment, podVerification(pendingShipment, PodVerificationStatus.PENDING),
                passShipment, podVerification(passShipment, PodVerificationStatus.PASS),
                failShipment, podVerification(failShipment, PodVerificationStatus.FAIL)));

        DashboardSummaryResponse response = service.summary();

        assertThat(response.companyOverview()).isNull();
        assertThat(response.branchOverview()).isNotNull();
        assertThat(response.branchOverview().pipeline()).hasSize(7);
        assertThat(response.branchOverview().readyForManifest()).isEqualTo(6L);
        assertThat(response.branchOverview().manifestsAwaitingDispatch()).isEqualTo(7L);
        assertThat(response.branchOverview().pendingDelivery()).isEqualTo(2L);
        assertThat(response.branchOverview().delayedShipments()).isEqualTo(8L);
        assertThat(response.branchOverview().deliveryPendingAging()).hasSize(4);
        assertThat(response.branchOverview().toPayAwaitingDelivery()).isEqualTo(1L);
        assertThat(response.branchOverview().inScanPending()).isEqualTo(9L);
        assertThat(response.branchOverview().deliveryPendingAging())
                .extracting("label", "count")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Since 24h", 0L),
                        org.assertj.core.groups.Tuple.tuple("Since 36h", 1L),
                        org.assertj.core.groups.Tuple.tuple("Since 48h", 0L),
                        org.assertj.core.groups.Tuple.tuple("72h+", 1L));

        // POD Dashboard pie: 1 of the 4 candidates is missing from the verification map
        // (pendingUpload), the other 3 land in their own latest-status bucket.
        assertThat(response.branchOverview().podOverview().pendingUpload()).isEqualTo(1L);
        assertThat(response.branchOverview().podOverview().pendingVerification()).isEqualTo(1L);
        assertThat(response.branchOverview().podOverview().approved()).isEqualTo(1L);
        assertThat(response.branchOverview().podOverview().rejected()).isEqualTo(1L);
        assertThat(response.branchOverview().rejectedPods()).hasSize(1);
        assertThat(response.branchOverview().rejectedPods().get(0).shipmentNumber()).isEqualTo("SHP-REJECTED");
        assertThat(response.branchOverview().rejectedPods().get(0).receiverName()).isEqualTo("Ramesh");

        // KPI tiles ("This Month's Bookings"/"Collection") come from the branch-scoped
        // queries, not the whole-company ones — the actual bug this test now guards.
        assertThat(response.statistics().todayShipments()).isEqualTo(1L);
        assertThat(response.statistics().todayCollection()).isEqualByComparingTo("77");
        assertThat(response.charts().shipmentTrend().get(0).points().get(13).value())
                .isEqualByComparingTo("11");
        assertThat(response.charts().deliveryPerformance()).hasSize(3);

        // Never the company-wide Company Overview repository calls for a branch-scoped caller.
        verify(manifestRepository, never()).countByCompanyIdAndStatus(any(), any());
        verify(walletRepository, never()).sumAvailableBalanceByCompanyId(any());
        verify(shipmentRepository, never()).findTopRoutesByCompanyIdAndBookingDateBetween(
                any(), any(), any(), any());

        // The whole point of this fix: a branch-scoped caller must never reach the
        // whole-company KPI/chart/recent-activity methods either.
        verify(shipmentRepository, never()).countByCompanyIdAndBookingDateBetween(any(), any(), any());
        verify(shipmentRepository, never()).countByCompanyIdAndStatusAndBookingDateBetween(any(), any(), any(), any());
        verify(shipmentRepository, never()).countByCompanyIdAndStatusInAndBookingDateBetween(any(), any(), any(), any());
        verify(shipmentChargeRepository, never())
                .sumNetAmountByCompanyIdAndBookingDateBetween(any(), any(), any());
        verify(shipmentRepository, never()).findTop5ByCompanyIdOrderByCreatedAtDesc(any());
        verify(shipmentStatusHistoryRepository, never())
                .findTop5ByCompanyIdAndStatusOrderByChangedAtDesc(any(), any());
        verify(walletTransactionRepository, never()).findTop5ByCompanyIdOrderByCreatedAtDesc(any());
        verify(shipmentRepository, never()).countDailyByCompanyIdAndBookingDateBetween(any(), any(), any());
        verify(shipmentRepository, never()).dailyDeliveryPerformanceByCompanyIdAndBookingDateBetween(
                any(), any(), any(), any(), any(), any());
    }

    private static ShipmentRepository.DailyCountRow dailyCount(LocalDate day, long count) {
        return new ShipmentRepository.DailyCountRow() {
            @Override public LocalDate getDay() { return day; }
            @Override public long getCount() { return count; }
        };
    }

    private static ShipmentRepository.DailyDeliveryPerformanceRow dailyPerf(
            LocalDate day, long delivered, long inTransit, long pending) {
        return new ShipmentRepository.DailyDeliveryPerformanceRow() {
            @Override public LocalDate getDay() { return day; }
            @Override public long getDelivered() { return delivered; }
            @Override public long getInTransit() { return inTransit; }
            @Override public long getPending() { return pending; }
        };
    }

    private static ShipmentChargeRepository.DailyRevenueRow dailyRevenue(LocalDate day, BigDecimal revenue) {
        return new ShipmentChargeRepository.DailyRevenueRow() {
            @Override public LocalDate getDay() { return day; }
            @Override public BigDecimal getRevenue() { return revenue; }
        };
    }

    private static PodVerification podVerification(UUID shipmentId, PodVerificationStatus status) {
        PodVerification v = PodVerification.builder().shipmentId(shipmentId).verificationStatus(status)
                .verificationScore(80).aiProvider("heuristic-local").aiModel("structural-v1").build();
        v.setId(UUID.randomUUID());
        return v;
    }

    @Test
    @DisplayName("COMPANY_ADMIN naming a branch (13.4): that branch's dashboard, not the company's")
    void companyAdminSeesANamedBranch() {
        UUID branch = UUID.randomUUID();
        CompanyContext.setCompanyId(COMPANY);
        signedIn(Roles.COMPANY_ADMIN);
        when(walletService.getForBranch(branch)).thenReturn(Wallet.builder().branchId(branch).availableBalance(new BigDecimal("75")).build());
        when(shipmentRepository.countByCompanyIdAndBookingBranchIdAndStatusNotAndBookingDateBetween(
                eq(COMPANY), eq(branch), eq(ShipmentStatus.CANCELLED), any(LocalDate.class), any(LocalDate.class))).thenReturn(4L);

        DashboardSummaryResponse response = service.summary(branch);

        assertThat(response.companyOverview()).isNull();
        assertThat(response.branchOverview()).isNotNull();
        assertThat(response.statistics().todayShipments()).isEqualTo(4L);
        assertThat(response.statistics().walletBalance()).isEqualByComparingTo("75");
    }

    @Test
    @DisplayName("A branch user naming another branch is refused (the wallet lookup's own check)")
    void branchUserCannotNameAnotherBranch() {
        UUID other = UUID.randomUUID();
        CompanyContext.setCompanyId(COMPANY);
        signedIn(Roles.BRANCH_MANAGER);
        when(walletService.getForBranch(other)).thenThrow(new com.courier.shared.exception.ResourceNotFoundException("Wallet", other));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.summary(other))
                .isInstanceOf(com.courier.shared.exception.ResourceNotFoundException.class);
    }

    private void signedIn(String role) {
        AuthenticatedUser principal = new AuthenticatedUser(CALLER, COMPANY, "user@test.local", Set.of(role), "jti");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.authorities()));
    }
}
