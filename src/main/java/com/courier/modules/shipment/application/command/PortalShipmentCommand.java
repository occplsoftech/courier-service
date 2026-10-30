package com.courier.modules.shipment.application.command;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A shipment issued at the moment a business customer books in the portal, priced exactly as the customer was quoted (nothing is
 * recalculated, the branch wallet is not touched).
 *
 * @param paymentKind    PAID (already taken from the customer's wallet), TOPAY, COD or CREDIT — mapped to the company's own payment mode
 * @param serviceCode    the quoted service ("OCCPL_SURFACE"...), used to pick the service type when one matches; may be null
 * @param total          what the customer was quoted and charged; the shipment's net amount
 * @param reference      the portal booking id, kept in the remarks
 */
public record PortalShipmentCommand(
        UUID bookingBranchId,
        UUID businessCustomerId,
        String reference,
        String pickupPincode,
        String deliveryPincode,
        String senderName, String senderAddress, String senderContact,
        String receiverName, String receiverAddress, String receiverContact,
        String fromCity, String toCity,
        String paymentKind,
        String serviceCode,
        String contents,
        BigDecimal declaredValue,
        int numberOfPackages,
        BigDecimal actualWeight,
        BigDecimal chargeableWeight,
        BigDecimal length, BigDecimal breadth, BigDecimal height,
        BigDecimal freight, BigDecimal fuel, BigDecimal handling, BigDecimal oda, BigDecimal insurance, BigDecimal gst,
        BigDecimal other,
        BigDecimal total) {
}
