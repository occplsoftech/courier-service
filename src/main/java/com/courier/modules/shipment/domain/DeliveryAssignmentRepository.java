package com.courier.modules.shipment.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeliveryAssignmentRepository extends JpaRepository<DeliveryAssignment, UUID> {

    /** Every row of one DRS (Scan → DRS → Edit). */
    @Query("select d from DeliveryAssignment d where d.companyId = :companyId and d.drsNumber = :drsNumber order by d.assignedAt")
    List<DeliveryAssignment> findByDrsNumberWithinCompany(@Param("companyId") UUID companyId, @Param("drsNumber") String drsNumber);

    /** A parcel taken off a DRS keeps its (soft-deleted) row, and there is one row per shipment: un-delete it before the
     *  shipment goes out on a run again. Native, as the entity's own queries never see deleted rows. */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "UPDATE delivery_assignment SET deleted = FALSE, deleted_at = NULL, deleted_by = NULL "
            + "WHERE company_id = :companyId AND shipment_id = :shipmentId AND deleted = TRUE", nativeQuery = true)
    int restoreRemoved(@Param("companyId") byte[] companyId, @Param("shipmentId") byte[] shipmentId);


    @Query("select d from DeliveryAssignment d where d.shipmentId = :shipmentId and d.companyId = :companyId")
    Optional<DeliveryAssignment> findByShipmentIdWithinCompany(@Param("shipmentId") UUID shipmentId,
                                                                @Param("companyId") UUID companyId);

    /** Batch load for a page of shipment rows — the Delivery Report's "delivered at" column. */
    List<DeliveryAssignment> findByShipmentIdIn(Collection<UUID> shipmentIds);

    /** Delivery Report's date-range filter — resolved to a shipment id set up front since
     *  {@code deliveredAt} lives on this entity, not on {@code Shipment}. */
    @Query("select d.shipmentId from DeliveryAssignment d where d.deliveredAt >= :from and d.deliveredAt < :to")
    List<UUID> findShipmentIdsDeliveredBetween(@Param("from") Instant from, @Param("to") Instant to);

    /** DRS Report's own grouping source — see {@code ShipmentService.listDrs}/{@code getDrsDetail}.
     *  Grouped by (deliveryUserId, deliveryBranchId, day) in the service layer rather than SQL,
     *  since there is no DRS batch id to group on and this keeps the query trivial. */
    @Query("select d from DeliveryAssignment d where d.companyId = :companyId "
            + "and d.assignedAt >= :from and d.assignedAt < :to order by d.assignedAt desc")
    List<DeliveryAssignment> findByCompanyAndAssignedAtBetween(@Param("companyId") UUID companyId,
                                                                @Param("from") Instant from,
                                                                @Param("to") Instant to);

    /**
     * Delivery Updation lists (V178): assignments in one status, optionally one delivery branch and one shipment status,
     * searched by AWB, shipment number, consignee or DRS number ({@code q} lower-case, wrapped in %). Sort comes with
     * {@code pageable}.
     */
    @Query(value = "select a from DeliveryAssignment a, com.courier.modules.shipment.domain.Shipment s"
            + " where s.id = a.shipmentId and a.companyId = :companyId and s.companyId = :companyId and a.status = :status"
            + " and (:branchId is null or a.deliveryBranchId = :branchId)"
            + " and (:shipmentStatus is null or s.status = :shipmentStatus)"
            + " and (:q is null or lower(s.trackingNumber) like :q or lower(s.shipmentNumber) like :q"
            + " or lower(s.receiverName) like :q or lower(a.drsNumber) like :q)",
            countQuery = "select count(a) from DeliveryAssignment a, com.courier.modules.shipment.domain.Shipment s"
                    + " where s.id = a.shipmentId and a.companyId = :companyId and s.companyId = :companyId and a.status = :status"
                    + " and (:branchId is null or a.deliveryBranchId = :branchId)"
                    + " and (:shipmentStatus is null or s.status = :shipmentStatus)"
                    + " and (:q is null or lower(s.trackingNumber) like :q or lower(s.shipmentNumber) like :q"
                    + " or lower(s.receiverName) like :q or lower(a.drsNumber) like :q)")
    org.springframework.data.domain.Page<DeliveryAssignment> searchForUpdation(
            @Param("companyId") UUID companyId, @Param("status") DeliveryAssignmentStatus status,
            @Param("branchId") UUID branchId, @Param("shipmentStatus") ShipmentStatus shipmentStatus,
            @Param("q") String q, org.springframework.data.domain.Pageable pageable);

    /** User Performance Report: a branch's DRS rows assigned or delivered in {@code [from, to)}. */
    @Query("select d from DeliveryAssignment d where d.companyId = :companyId and d.deliveryBranchId = :branchId "
            + "and ((d.assignedAt >= :from and d.assignedAt < :to) or (d.deliveredAt >= :from and d.deliveredAt < :to))")
    List<DeliveryAssignment> findForBranchAssignedOrDeliveredBetween(@Param("companyId") UUID companyId,
                                                                    @Param("branchId") UUID branchId,
                                                                    @Param("from") Instant from, @Param("to") Instant to);
}
