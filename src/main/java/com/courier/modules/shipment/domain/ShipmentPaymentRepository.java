package com.courier.modules.shipment.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ShipmentPaymentRepository extends JpaRepository<ShipmentPayment, UUID> {

    @Query("select p from ShipmentPayment p where p.shipmentId = :shipmentId and p.companyId = :companyId")
    Optional<ShipmentPayment> findByShipmentWithinCompany(@Param("shipmentId") UUID shipmentId,
                                                          @Param("companyId") UUID companyId);

    /** Webhook lookup: no company context yet, the QR id is unique at Razorpay. */
    Optional<ShipmentPayment> findFirstByGatewayQrId(String gatewayQrId);

    @Query("""
            select p from ShipmentPayment p where p.companyId = :companyId
              and (:status is null or p.status = :status)
              and (:branchId is null or p.bookingBranchId = :branchId)
            order by p.createdAt desc""")
    Page<ShipmentPayment> search(@Param("companyId") UUID companyId,
                                 @Param("status") ShipmentPayment.Status status,
                                 @Param("branchId") UUID branchId, Pageable pageable);
}
