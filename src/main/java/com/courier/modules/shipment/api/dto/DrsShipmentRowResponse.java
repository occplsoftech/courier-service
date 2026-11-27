package com.courier.modules.shipment.api.dto;

import com.courier.modules.shipment.domain.ShipmentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record DrsShipmentRowResponse(
        UUID shipmentId, String shipmentNumber, String trackingNumber,
        String receiverName, String receiverAddress, String receiverContact, UUID paymentModeId,
        BigDecimal netAmount, ShipmentStatus status, Instant deliveredAt,
        /** The shipment's E-Way Bill number, null where none exists or Part-A hasn't
         *  succeeded yet. */
        String ewayBillNumber,
        /** The booking branch's own city. */
        String fromCity,
        /** The destination pincode/area's resolved city. */
        String toCity,
        /** COD to collect from the receiver; null when none. */
        BigDecimal codAmount,
        String deliveryPincode,
        Integer numberOfPackages,
        BigDecimal actualWeight
) {
}
