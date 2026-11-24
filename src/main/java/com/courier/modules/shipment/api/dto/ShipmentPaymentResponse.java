package com.courier.modules.shipment.api.dto;

import com.courier.modules.shipment.domain.ShipmentPayment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** How the customer pays a Paid booking; for Razorpay, the QR to show while pending. */
public record ShipmentPaymentResponse(UUID id, UUID shipmentId, String shipmentNumber, UUID bookingBranchId,
                                      String method, String status, BigDecimal amount, String reference,
                                      String qrImageUrl, Instant qrCloseBy, boolean qrOpen, BigDecimal paidAmount,
                                      Instant paidAt, Instant createdAt) {

    public static ShipmentPaymentResponse of(ShipmentPayment p) {
        boolean open = !p.isPaid() && p.getQrImageUrl() != null && p.getQrCloseBy() != null
                && p.getQrCloseBy().isAfter(Instant.now());
        return new ShipmentPaymentResponse(p.getId(), p.getShipmentId(), p.getShipmentNumber(), p.getBookingBranchId(),
                p.getMethod().name(), p.getStatus().name(), p.getAmount(), p.getReference(),
                open ? p.getQrImageUrl() : null, p.getQrCloseBy(), open, p.getPaidAmount(), p.getPaidAt(),
                p.getCreatedAt());
    }
}
