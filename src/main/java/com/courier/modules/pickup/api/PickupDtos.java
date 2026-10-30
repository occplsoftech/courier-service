package com.courier.modules.pickup.api;

import com.courier.modules.pickup.domain.PickupStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/** Wire contract of {@code /api/v1/pickups}. */
public final class PickupDtos {

    private PickupDtos() {
    }

    public record SaveRequest(
            UUID customerId,
            @NotBlank @Size(max = 150) String contactName,
            @NotBlank @Pattern(regexp = "^[+]?[0-9 \\-]{7,20}$", message = "must be a valid phone number") String contactMobile,
            @NotBlank @Size(max = 255) String addressLine,
            @Size(max = 100) String city,
            UUID districtId,
            @NotBlank @Pattern(regexp = "^[0-9]{6}$", message = "must be 6 digits") String pincode,
            @Min(1) Integer expectedParcels,
            @DecimalMin("0") BigDecimal expectedWeightKg,
            LocalDate preferredDate,
            @Size(max = 40) String preferredSlot,
            @Size(max = 500) String remarks,
            /** Optional on create: assigns straight away. Ignored on update (use /assign). */
            UUID pdpBranchId,
            @PositiveOrZero Long version) {
    }

    public record AssignRequest(@NotNull UUID pdpBranchId) {
    }

    public record ReasonRequest(@NotBlank @Size(max = 255) String reason) {
    }

    public record CompleteRequest(@Min(1) int actualParcels, @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal actualWeightKg) {
    }

    public record LinkShipmentRequest(@NotNull UUID shipmentId, @Size(max = 50) String shipmentNo) {
    }

    public record PickupResponse(
            UUID id, String pickupNo, PickupStatus status, UUID customerId, String contactName,
            String contactMobile, String addressLine, String city, UUID districtId, String pincode,
            int expectedParcels, BigDecimal expectedWeightKg, LocalDate preferredDate, String preferredSlot,
            String remarks, UUID pdpBranchId, String pdpBranchCode, String pdpBranchName, Instant assignedAt,
            Instant acceptedAt, Instant startedAt, Instant pickedUpAt, Integer actualParcels,
            BigDecimal actualWeightKg, String rejectReason, String failReason, String cancelReason,
            UUID shipmentId, String shipmentNo, UUID bookingBranchId, String bookingBranchCode, String bookingBranchName,
            Instant createdDate, Instant updatedDate, Long version) {
    }

    public record StatsResponse(Map<PickupStatus, Long> byStatus, long open) {
    }
}
