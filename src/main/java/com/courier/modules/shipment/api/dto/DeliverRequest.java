package com.courier.modules.shipment.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record DeliverRequest(
        @NotNull UUID shipmentId,
        @NotBlank @Size(max = 150) String receiverName,
        @Size(max = 500) String remarks,
        @Size(max = 10) String otp,
        @Size(max = 1000) String signatureUrl,
        @Size(max = 1000) String photoUrl,
        @Size(max = 50) String receiverRelation,
        com.courier.modules.shipment.domain.DeliveryProof proofType,
        java.time.LocalDate deliveredOn
) {
}
