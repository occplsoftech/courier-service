package com.courier.modules.shipment.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Shipments, within a company.
 *
 * <p>Single-row loads go through {@link #findByIdWithinCompany} — a primary-key load
 * bypasses the Hibernate filter, the same reasoning every other module's repository
 * documents. Neither {@code shipmentNumber} nor {@code trackingNumber} needs an
 * existence check before insert any more — both are counters
 * ({@code BranchShipmentSequenceRepository} / {@code CompanyShipmentSequenceRepository})
 * that can't collide, unlike {@code finance.domain.WalletRepository}'s own generated,
 * dated-random numbers.
 */
public interface ShipmentRepository extends JpaRepository<Shipment, UUID>,
        JpaSpecificationExecutor<Shipment> {

    /** Cancelled shipments give their number back (see V100); the column is
     *  {@code updatable = false} on the entity, so the rename goes through SQL. */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "UPDATE shipments SET shipment_number = :newNumber WHERE id = :id", nativeQuery = true)
    void renameShipmentNumber(@Param("id") byte[] id, @Param("newNumber") String newNumber);


    /**
     * Open failed-delivery cases (Phase 10.2), oldest attempt first: only shipments actually back at the branch (IN_SCAN). A shipment
     * moved on some other way (a manual status override) keeps a stale flag but is not a case any more.
     */
    @Query("select s from Shipment s where s.companyId = :companyId and s.undeliveredOpen = true "
            + "and s.status = com.courier.modules.shipment.domain.ShipmentStatus.IN_SCAN order by s.undeliveredAt asc")
    List<Shipment> findOpenUndelivered(@Param("companyId") UUID companyId);

    /** Returns still on their way back to the sender (or waiting to be handed back), oldest first (Phase 12.1). */
    @Query("select s from Shipment s where s.companyId = :companyId and s.rto = true "
            + "and s.status <> com.courier.modules.shipment.domain.ShipmentStatus.RETURNED "
            + "and s.status <> com.courier.modules.shipment.domain.ShipmentStatus.CANCELLED order by s.rtoStartedAt asc")
    List<Shipment> findOpenReturns(@Param("companyId") UUID companyId);

    @Query("select s from Shipment s where s.id = :id and s.companyId = :companyId")
    Optional<Shipment> findByIdWithinCompany(@Param("id") UUID id, @Param("companyId") UUID companyId);

    Optional<Shipment> findByCompanyIdAndTrackingNumber(UUID companyId, String trackingNumber);

    /** Bulk Shipment Tracking report: one query for every number pasted in, matched against
     *  either column since the caller can't reliably tell an AWB from a shipment number. */
    @Query("select s from Shipment s where s.companyId = :companyId "
            + "and (s.trackingNumber in :numbers or s.shipmentNumber in :numbers)")
    List<Shipment> findAllByCompanyIdAndTrackingNumberInOrShipmentNumberIn(
            @Param("companyId") UUID companyId, @Param("numbers") Collection<String> numbers);

    boolean existsByCompanyIdAndShipmentNumber(UUID companyId, String shipmentNumber);

    List<Shipment> findAllByCompanyIdAndIdIn(UUID companyId, Collection<UUID> ids);

    List<Shipment> findAllByCompanyIdAndManifestIdIn(UUID companyId, Collection<UUID> manifestIds);

    /** Load Sheet's destination-city picker: every distinct {@code toCity} among a
     *  branch's own shipments still awaiting a delivery-branch assignment. */
    @Query("select distinct s.toCity from Shipment s where s.companyId = :companyId "
            + "and s.currentLocationId = :branchId and s.status in :statuses "
            + "and s.deliveryBranchId is null and s.toCity is not null order by s.toCity")
    List<String> findDistinctToCityByCompanyIdAndCurrentLocationIdAndStatusIn(
            @Param("companyId") UUID companyId, @Param("branchId") UUID branchId,
            @Param("statuses") Collection<ShipmentStatus> statuses);

    /** One booking branch's still-open shipments in a given set of statuses — the wallet
     *  dashboard's pending-commission figures walk this to find what hasn't crossed the
     *  dispatch/delivery trigger yet. */
    List<Shipment> findAllByCompanyIdAndBookingBranchIdAndStatusIn(
            UUID companyId, UUID bookingBranchId, Collection<ShipmentStatus> statuses);

    // -------------------------------------------------------------- cross-tenant
    // Unfiltered on purpose — only ever safe to call from inside a
    // CompanyContext.runAs(null, ...) block (a genuinely cross-tenant platform-level
    // read), never from a company-bound caller. See DashboardServiceImpl.summary().

    long countByBookingDateBetween(LocalDate start, LocalDate end);

    /** Dashboard's "This Month's Shipments"/"This Month's Bookings" tiles, cross-tenant —
     *  cancelled orders excluded, same rule as {@link #countByCompanyIdAndStatusNot}. */
    long countByStatusNotAndBookingDateBetween(ShipmentStatus status, LocalDate start, LocalDate end);

    long countByStatus(ShipmentStatus status);

    long countByStatusIn(Collection<ShipmentStatus> statuses);

    long countByStatusAndBookingDateBetween(ShipmentStatus status, LocalDate start, LocalDate end);

    long countByStatusInAndBookingDateBetween(Collection<ShipmentStatus> statuses, LocalDate start, LocalDate end);

    long countByDeliveryBranchIdAndStatusIn(UUID deliveryBranchId, Collection<ShipmentStatus> statuses);

    List<Shipment> findTop5ByOrderByCreatedAtDesc();

    /** Public Track Shipment (login page, no auth): looked up cross-company by either
     *  column, same ambiguity {@code findAllByCompanyIdAndTrackingNumberInOrShipmentNumberIn}
     *  already tolerates — {@code tracking_number} is only unique per company ({@code
     *  uk_shipments_company_tracking}), so two different companies can genuinely share one.
     *  Returns every match rather than {@code Optional} precisely so the caller can tell a
     *  real cross-company collision apart from "not found" instead of this throwing {@code
     *  NonUniqueResultException} on the second row. Only ever called from {@code
     *  PublicTrackingService}, which returns a redacted projection — never hand the raw
     *  {@link Shipment} entity back to an unauthenticated caller. */
    @Query("select s from Shipment s where s.trackingNumber = :number or s.shipmentNumber = :number")
    List<Shipment> findAllByTrackingNumberOrShipmentNumberForPublicTracking(@Param("number") String number);

    /** Backs the platform-wide "Shipment Trend" chart — one row per day that had at
     *  least one booking; days with none simply don't appear (DashboardServiceImpl
     *  fills the gaps with zero). */
    @Query("select s.bookingDate as day, count(s) as count from Shipment s "
            + "where s.bookingDate between :start and :end and s.status <> com.courier.modules.shipment.domain.ShipmentStatus.CANCELLED group by s.bookingDate")
    List<DailyCountRow> countDailyByBookingDateBetween(@Param("start") LocalDate start,
                                                        @Param("end") LocalDate end);

    @Query("select coalesce(sum(s.actualWeight), 0) from Shipment s "
            + "where s.bookingDate between :start and :end")
    BigDecimal sumActualWeightByBookingDateBetween(@Param("start") LocalDate start, @Param("end") LocalDate end);

    // -------------------------------------------------------------- company-scoped
    // Explicit companyId predicate, not left to the implicit Hibernate companyFilter —
    // DashboardServiceImpl.summary() is deliberately not @Transactional (see its own
    // javadoc), so each of these runs in its own short-lived session/transaction; an
    // explicit predicate is correct regardless of session boundaries, where relying on
    // CompanyFilterAspect having enabled the filter on that specific session is not
    // (see MEMORY: dashboard-cross-tenant-leak — this is ISSUE-001's actual fix).

    long countByCompanyIdAndBookingDateBetween(UUID companyId, LocalDate start, LocalDate end);

    /** Company-scoped sibling of {@link #countByStatusNotAndBookingDateBetween} — cancelled
     *  orders excluded from the "This Month's Shipments"/"This Month's Bookings" tiles. */
    long countByCompanyIdAndStatusNotAndBookingDateBetween(UUID companyId, ShipmentStatus status,
                                                             LocalDate start, LocalDate end);

    @Query("select coalesce(sum(s.actualWeight), 0) from Shipment s "
            + "where s.companyId = :companyId and s.bookingDate between :start and :end")
    BigDecimal sumActualWeightByCompanyIdAndBookingDateBetween(@Param("companyId") UUID companyId,
                                                                @Param("start") LocalDate start,
                                                                @Param("end") LocalDate end);

    long countByCompanyIdAndStatus(UUID companyId, ShipmentStatus status);

    long countByCompanyIdAndStatusIn(UUID companyId, Collection<ShipmentStatus> statuses);

    /** Backs the Company Overview "TO_PAY awaiting delivery" tile — a subset of {@code
     *  statuses} further filtered to TO_PAY-shaped payment modes, whose ids the caller
     *  looks up via {@code PaymentModeRepository} first (payment mode is company-defined
     *  rows, not a fixed enum, so there's no column to filter on directly). */
    long countByCompanyIdAndStatusInAndPaymentModeIdIn(UUID companyId, Collection<ShipmentStatus> statuses,
                                                        Collection<UUID> paymentModeIds);

    long countByCompanyIdAndStatusAndBookingDateBetween(UUID companyId, ShipmentStatus status,
                                                         LocalDate start, LocalDate end);

    long countByCompanyIdAndStatusInAndBookingDateBetween(UUID companyId, Collection<ShipmentStatus> statuses,
                                                           LocalDate start, LocalDate end);

    long countByCompanyId(UUID companyId);

    /** Dashboard's "Total Shipments" figure — cancelled orders don't count as real
     *  shipment volume, so excluded here rather than in the caller. */
    long countByCompanyIdAndStatusNot(UUID companyId, ShipmentStatus status);

    /** Cross-tenant ({@code SUPER_ADMIN}) sibling of {@link #countByCompanyIdAndStatusNot} —
     *  same "exclude CANCELLED from the total" rule, whole-platform. */
    long countByStatusNot(ShipmentStatus status);

    /** Row-returning, not a count — the dashboard's Pending Delivery tile needs the total
     *  ({@code .size()}) and the Delivery Pending aging breakdown needs each shipment's own
     *  id to look up its received (IN_SCAN) timestamp, from the exact same query. */
    List<Shipment> findByCompanyIdAndDeliveryBranchIdAndStatusIn(UUID companyId, UUID deliveryBranchId,
                                                                  Collection<ShipmentStatus> statuses);

    /** Id-only projections for the POD Dashboard pie (pending upload / pending verification /
     *  approved / rejected) — avoids materialising every OUT_FOR_DELIVERY/DELIVERED shipment
     *  as a full row just to cross-reference against {@code pod_verification} by id. */
    @Query("select s.id from Shipment s where s.companyId = :companyId and s.status in :statuses")
    List<UUID> findIdsByCompanyIdAndStatusIn(@Param("companyId") UUID companyId,
                                              @Param("statuses") Collection<ShipmentStatus> statuses);

    @Query("select s.id from Shipment s where s.companyId = :companyId "
            + "and s.deliveryBranchId = :deliveryBranchId and s.status in :statuses")
    List<UUID> findIdsByCompanyIdAndDeliveryBranchIdAndStatusIn(@Param("companyId") UUID companyId,
                                                                 @Param("deliveryBranchId") UUID deliveryBranchId,
                                                                 @Param("statuses") Collection<ShipmentStatus> statuses);

    List<Shipment> findTop5ByCompanyIdOrderByCreatedAtDesc(UUID companyId);

    /** Backs the Company Overview "Delayed Shipments" action-required tile: booked more
     *  than a few days ago and not yet in a terminal state. Not SLA-rule-aware (see
     *  {@code ShipmentSlaSweepService} for the real per-stage thresholds) — a simple,
     *  company-wide backlog heuristic. */
    long countByCompanyIdAndStatusNotInAndBookingDateBefore(UUID companyId,
                                                              Collection<ShipmentStatus> excludedStatuses,
                                                              LocalDate cutoff);

    /** Backs the "Shipment Trend" chart for a company-bound caller — see the unscoped
     *  sibling {@link #countDailyByBookingDateBetween} for the cross-tenant case. */
    @Query("select s.bookingDate as day, count(s) as count from Shipment s "
            + "where s.companyId = :companyId and s.bookingDate between :start and :end and s.status <> com.courier.modules.shipment.domain.ShipmentStatus.CANCELLED "
            + "group by s.bookingDate")
    List<DailyCountRow> countDailyByCompanyIdAndBookingDateBetween(@Param("companyId") UUID companyId,
                                                                    @Param("start") LocalDate start,
                                                                    @Param("end") LocalDate end);

    /** Backs the "Delivery Performance" chart: how many of each day's bookings have
     *  reached each status bucket as of now. Company-scoped only — no profile shows
     *  this chart cross-tenant (see dashboard.roles.ts PLATFORM: deliveryPerformance
     *  false), so no unscoped sibling exists. */
    @Query("select s.bookingDate as day, "
            + "sum(case when s.status = :delivered then 1L else 0L end) as delivered, "
            + "sum(case when s.status in :inTransit then 1L else 0L end) as inTransit, "
            + "sum(case when s.status in :pending then 1L else 0L end) as pending "
            + "from Shipment s where s.companyId = :companyId and s.bookingDate between :start and :end "
            + "group by s.bookingDate")
    List<DailyDeliveryPerformanceRow> dailyDeliveryPerformanceByCompanyIdAndBookingDateBetween(
            @Param("companyId") UUID companyId, @Param("delivered") ShipmentStatus delivered,
            @Param("inTransit") Collection<ShipmentStatus> inTransit,
            @Param("pending") Collection<ShipmentStatus> pending,
            @Param("start") LocalDate start, @Param("end") LocalDate end);

    /** Projection for both daily-count trend queries above. */
    interface DailyCountRow {
        LocalDate getDay();
        long getCount();
    }

    /** Projection for {@link #dailyDeliveryPerformanceByCompanyIdAndBookingDateBetween}. */
    interface DailyDeliveryPerformanceRow {
        LocalDate getDay();
        long getDelivered();
        long getInTransit();
        long getPending();
    }

    // -------------------------------------------------------------- branch-scoped
    // Backs the Branch Overview pipeline/action-required card (DashboardServiceImpl,
    // BRANCH_MANAGER/BRANCH_OPERATOR/hub callers — anyone with an own branch wallet).
    // Filtered on currentLocationId, not bookingBranchId/deliveryBranchId: that column is
    // "which branch physically holds this shipment right now" (set to the booking branch
    // at creation, advanced by CrossingService.arriveAt on every hub hop — see
    // MEMORY/AI_CONTEXT.md 0.26.0/0.27.0), the one field that's correct for every pipeline
    // stage including a shipment mid-crossing through a branch that is neither its
    // booking nor delivery branch.

    long countByCompanyIdAndCurrentLocationIdAndStatusAndBookingDateBetween(
            UUID companyId, UUID currentLocationId, ShipmentStatus status, LocalDate start, LocalDate end);

    long countByCompanyIdAndCurrentLocationIdAndStatusIn(
            UUID companyId, UUID currentLocationId, Collection<ShipmentStatus> statuses);

    long countByCompanyIdAndCurrentLocationIdAndStatusNotInAndBookingDateBefore(
            UUID companyId, UUID currentLocationId, Collection<ShipmentStatus> excludedStatuses, LocalDate cutoff);

    /** In Scan Pending backlog for the Branch Overview card: shipments already dispatched
     *  (out of some other branch/hub) whose next stop is this branch, still awaiting the
     *  receiving branch's own {@code scanOneIn} — stock physically incoming but not yet
     *  booked into this branch's own pipeline. {@code nextLocationId} is the shipment's
     *  real next stop (a crossing hub or the delivery branch); the {@code deliveryBranchId}
     *  fallback covers pre-V37 rows that never had it written, same fallback
     *  {@code ShipmentServiceImpl.scanOneIn} itself already applies. */
    @Query("select count(s) from Shipment s where s.companyId = :companyId and s.status = :status "
            + "and (s.nextLocationId = :branchId "
            + "or (s.nextLocationId is null and s.deliveryBranchId = :branchId))")
    long countInScanPendingByCompanyIdAndBranchId(@Param("companyId") UUID companyId,
            @Param("branchId") UUID branchId, @Param("status") ShipmentStatus status);

    // ---------------------------------------------------------- booking-branch-scoped
    // Backs the dashboard's own KPI tiles/charts/lists for a caller with an own branch
    // (BRANCH_MANAGER/BRANCH_OPERATOR) — the company-scoped siblings above leaked
    // whole-company figures to a branch-scoped caller (DashboardServiceImpl.summary()).
    // Filtered on bookingBranchId, not currentLocationId: these mirror the same
    // "this scope's own bookings" meaning the company-wide figures already have, just one
    // scope narrower — unlike Branch Overview's pipeline, which deliberately tracks a
    // shipment's current physical location instead (see that section's own comment).

    long countByCompanyIdAndBookingBranchIdAndBookingDateBetween(
            UUID companyId, UUID bookingBranchId, LocalDate start, LocalDate end);

    /** Booking-branch-scoped sibling of {@link #countByCompanyIdAndStatusNotAndBookingDateBetween}
     *  — cancelled orders excluded from the branch-scoped "This Month's Shipments" tile. */
    long countByCompanyIdAndBookingBranchIdAndStatusNotAndBookingDateBetween(
            UUID companyId, UUID bookingBranchId, ShipmentStatus status, LocalDate start, LocalDate end);

    @Query("select coalesce(sum(s.actualWeight), 0) from Shipment s "
            + "where s.companyId = :companyId and s.bookingBranchId = :bookingBranchId "
            + "and s.bookingDate between :start and :end")
    BigDecimal sumActualWeightByCompanyIdAndBookingBranchIdAndBookingDateBetween(
            @Param("companyId") UUID companyId, @Param("bookingBranchId") UUID bookingBranchId,
            @Param("start") LocalDate start, @Param("end") LocalDate end);

    long countByCompanyIdAndBookingBranchIdAndStatusAndBookingDateBetween(
            UUID companyId, UUID bookingBranchId, ShipmentStatus status, LocalDate start, LocalDate end);

    long countByCompanyIdAndBookingBranchIdAndStatusInAndBookingDateBetween(
            UUID companyId, UUID bookingBranchId, Collection<ShipmentStatus> statuses, LocalDate start, LocalDate end);

    List<Shipment> findTop5ByCompanyIdAndBookingBranchIdOrderByCreatedAtDesc(
            UUID companyId, UUID bookingBranchId);

    /** Backs the "Shipment Trend" chart for a branch-scoped caller. */
    @Query("select s.bookingDate as day, count(s) as count from Shipment s "
            + "where s.companyId = :companyId and s.bookingBranchId = :bookingBranchId "
            + "and s.bookingDate between :start and :end and s.status <> com.courier.modules.shipment.domain.ShipmentStatus.CANCELLED group by s.bookingDate")
    List<DailyCountRow> countDailyByCompanyIdAndBookingBranchIdAndBookingDateBetween(
            @Param("companyId") UUID companyId, @Param("bookingBranchId") UUID bookingBranchId,
            @Param("start") LocalDate start, @Param("end") LocalDate end);

    /** Backs the "Delivery Performance" chart for a branch-scoped caller. */
    @Query("select s.bookingDate as day, "
            + "sum(case when s.status = :delivered then 1L else 0L end) as delivered, "
            + "sum(case when s.status in :inTransit then 1L else 0L end) as inTransit, "
            + "sum(case when s.status in :pending then 1L else 0L end) as pending "
            + "from Shipment s where s.companyId = :companyId and s.bookingBranchId = :bookingBranchId "
            + "and s.bookingDate between :start and :end group by s.bookingDate")
    List<DailyDeliveryPerformanceRow> dailyDeliveryPerformanceByCompanyIdAndBookingBranchIdAndBookingDateBetween(
            @Param("companyId") UUID companyId, @Param("bookingBranchId") UUID bookingBranchId,
            @Param("delivered") ShipmentStatus delivered,
            @Param("inTransit") Collection<ShipmentStatus> inTransit,
            @Param("pending") Collection<ShipmentStatus> pending,
            @Param("start") LocalDate start, @Param("end") LocalDate end);

    /** Backs the Company Overview "Top Routes" card: one row per destination branch,
     *  ranked by month-to-date shipment count. Native — {@code Shipment}/{@code
     *  ShipmentCharge} have no ORM association (see {@code ShipmentChargeRepository}'s own
     *  class doc), so the revenue join has to be written as SQL. {@code Pageable} supplies
     *  the "top N" limit; the query itself carries no {@code LIMIT} clause. */
    @Query(value = """
            SELECT s.delivery_branch_id AS branchId, COUNT(*) AS shipmentCount,
                   COALESCE(SUM(c.net_amount), 0) AS revenue
            FROM shipments s
            LEFT JOIN shipment_charges c ON c.shipment_id = s.id AND c.company_id = :companyId
            WHERE s.company_id = :companyId AND s.deleted = FALSE
              AND s.booking_date BETWEEN :start AND :end AND s.status <> 'CANCELLED'
            GROUP BY s.delivery_branch_id
            ORDER BY shipmentCount DESC
            """, nativeQuery = true)
    List<TopRouteRow> findTopRoutesByCompanyIdAndBookingDateBetween(
            @Param("companyId") UUID companyId, @Param("start") LocalDate start,
            @Param("end") LocalDate end, Pageable pageable);

    /** Native-query projection for {@link #findTopRoutesByCompanyIdAndBookingDateBetween}.
     *  {@code branchId} comes back as the column's raw {@code BINARY(16)} bytes, not
     *  {@code UUID} — Spring Data has no byte[]-to-UUID converter for native-query
     *  projections (unlike entity mapping, which Hibernate itself handles), so a
     *  {@code UUID}-typed accessor here throws {@code UnsupportedOperationException} the
     *  moment it's called. Convert with {@code TimeOrderedUuid.fromBytes} at the call
     *  site instead. */
    interface TopRouteRow {
        byte[] getBranchId();
        long getShipmentCount();
        BigDecimal getRevenue();
    }

    /** Backs the Company Overview "Top Customers" card: grouped by {@code sender_contact}
     *  — see {@link com.courier.modules.dashboard.api.dto.TopCustomerResponse}'s own doc
     *  for why there is no {@code Customer} id to group by instead. */
    @Query(value = """
            SELECT s.sender_contact AS contact, MAX(s.sender_name) AS name,
                   COUNT(*) AS shipmentCount, COALESCE(SUM(c.net_amount), 0) AS revenue
            FROM shipments s
            LEFT JOIN shipment_charges c ON c.shipment_id = s.id AND c.company_id = :companyId
            WHERE s.company_id = :companyId AND s.deleted = FALSE
              AND s.booking_date BETWEEN :start AND :end AND s.status <> 'CANCELLED' AND s.sender_contact IS NOT NULL
            GROUP BY s.sender_contact
            ORDER BY shipmentCount DESC
            """, nativeQuery = true)
    List<TopCustomerRow> findTopCustomersByCompanyIdAndBookingDateBetween(
            @Param("companyId") UUID companyId, @Param("start") LocalDate start,
            @Param("end") LocalDate end, Pageable pageable);

    /** Native-query projection for {@link #findTopCustomersByCompanyIdAndBookingDateBetween}. */
    interface TopCustomerRow {
        String getContact();
        String getName();
        long getShipmentCount();
        BigDecimal getRevenue();
    }

    /** One row per shipment currently sitting past its stage's SLA threshold — backs
     *  {@code shipment.infrastructure.ShipmentSlaAdapter}. Native: the "how long has this
     *  shipment been in its current status" comparison is a per-row {@code TIMESTAMPDIFF}
     *  against the latest {@code shipment_status_history} row, awkward to express in JPQL
     *  and only meaningful as SQL. Filtering by {@code company_id} explicitly here is
     *  required, not optional — a native query bypasses the Hibernate {@code companyFilter}
     *  the same way every other native query in this codebase documents. */
    @Query(value = """
            SELECT s.id AS shipmentId, s.tracking_number AS trackingNumber,
                   s.current_location_id AS branchId, s.status AS status,
                   h.changed_at AS enteredAt
            FROM shipments s
            JOIN (
                SELECT shipment_id, MAX(changed_at) AS changed_at
                FROM shipment_status_history
                WHERE company_id = :companyId
                GROUP BY shipment_id
            ) h ON h.shipment_id = s.id
            WHERE s.company_id = :companyId
              AND s.deleted = FALSE
              AND (
                   (s.status = 'BOOKED' AND TIMESTAMPDIFF(HOUR, h.changed_at, :asOf) >= :bookingHours)
                OR (s.status = 'MANIFEST_CREATED' AND TIMESTAMPDIFF(HOUR, h.changed_at, :asOf) >= :loadingSheetHours)
                OR (s.status = 'DISPATCHED' AND TIMESTAMPDIFF(HOUR, h.changed_at, :asOf) >= :thcHours)
                OR (s.status = 'IN_SCAN' AND TIMESTAMPDIFF(HOUR, h.changed_at, :asOf) >= :inscanHours)
                OR (s.status = 'OUT_FOR_DELIVERY' AND TIMESTAMPDIFF(HOUR, h.changed_at, :asOf) >= :drsHours)
              )
            """, nativeQuery = true)
    List<ShipmentSlaCandidateRow> findSlaBreachCandidates(
            @Param("companyId") UUID companyId,
            @Param("asOf") java.time.Instant asOf,
            @Param("bookingHours") int bookingHours,
            @Param("loadingSheetHours") int loadingSheetHours,
            @Param("thcHours") int thcHours,
            @Param("inscanHours") int inscanHours,
            @Param("drsHours") int drsHours);

    /** Native-query projection for {@link #findSlaBreachCandidates}. */
    interface ShipmentSlaCandidateRow {
        byte[] getShipmentId();
        String getTrackingNumber();
        byte[] getBranchId();
        String getStatus();
        /** Raw driver value — {@code Timestamp}, {@code LocalDateTime} or {@code Instant}
         *  depending on the dialect path; callers normalise via {@code toInstant}. */
        Object getEnteredAt();
    }

    /** Customer portal dashboard: one business customer's shipments per status. */
    @Query("select s.status as status, count(s) as total from Shipment s "
            + "where s.companyId = :companyId and s.businessCustomerId = :customerId group by s.status")
    List<StatusCount> countByStatusForBusinessCustomer(@Param("companyId") UUID companyId,
                                                       @Param("customerId") UUID customerId);

    /** Customer portal: one business customer's shipments, newest first. */
    @Query("select s from Shipment s where s.companyId = :companyId and s.businessCustomerId = :customerId "
            + "order by s.createdAt desc")
    List<Shipment> findRecentForBusinessCustomer(@Param("companyId") UUID companyId,
                                                 @Param("customerId") UUID customerId,
                                                 Pageable page);

    interface StatusCount {
        ShipmentStatus getStatus();

        long getTotal();
    }
}
