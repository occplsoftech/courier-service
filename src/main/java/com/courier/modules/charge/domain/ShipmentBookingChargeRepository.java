package com.courier.modules.charge.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ShipmentBookingChargeRepository extends JpaRepository<ShipmentBookingCharge, UUID> {

    @Query("select c from ShipmentBookingCharge c where c.companyId = :companyId and c.shipmentId = :shipmentId order by c.code")
    List<ShipmentBookingCharge> findForShipment(@Param("companyId") UUID companyId, @Param("shipmentId") UUID shipmentId);
}
