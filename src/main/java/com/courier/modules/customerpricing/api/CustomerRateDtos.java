package com.courier.modules.customerpricing.api;

import com.courier.modules.customerpricing.domain.ZoneStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Wire contract of {@code /api/v1/customer-rates}. */
public final class CustomerRateDtos {

    private CustomerRateDtos() {
    }

    public record SlabRequest(
            @NotNull @DecimalMin("0") BigDecimal minWeight,
            @DecimalMin(value = "0", inclusive = false) BigDecimal maxWeight,
            @NotNull @DecimalMin("0") BigDecimal ratePerKg) {
    }

    /** Full replacement of a zone's rate card. {@code version} is required only when one exists. */
    public record SaveRequest(
            @DecimalMin("0") BigDecimal minChargeableWeight,
            @DecimalMin("0") BigDecimal docketCharge,
            @DecimalMin("0") BigDecimal mathadiCharge,
            @DecimalMin("0") BigDecimal packCharge,
            @DecimalMin("0") BigDecimal odaCharge,
            @DecimalMin("0") @DecimalMax("100") BigDecimal fuelSurchargePercent,
            @DecimalMin("0") @DecimalMax("100") BigDecimal fovPercent,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @NotNull @Valid List<SlabRequest> slabs,
            @PositiveOrZero Long version) {
    }

    public record SlabResponse(UUID id, BigDecimal minWeight, BigDecimal maxWeight, BigDecimal ratePerKg) {
    }

    public record RateResponse(
            UUID id, UUID zoneId, String zoneName, UUID customerId, String customerName,
            BigDecimal minChargeableWeight, BigDecimal docketCharge, BigDecimal mathadiCharge,
            BigDecimal packCharge, BigDecimal odaCharge, BigDecimal fuelSurchargePercent, BigDecimal fovPercent,
            LocalDate effectiveFrom, LocalDate effectiveTo, ZoneStatus status,
            UUID serviceTypeId, String serviceTypeCode, String serviceTypeName,
            List<SlabResponse> slabs, Instant createdDate, Instant updatedDate, Long version) {
    }

    public record CalculateRequest(
            @NotNull UUID customerId,
            @NotNull UUID districtId,
            UUID cityId,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal actualWeight,
            @DecimalMin("0") BigDecimal declaredValue,
            boolean odaApplicable,
            LocalDate bookingDate,
            UUID serviceTypeId) {
    }

    public record CalculateResponse(
            UUID zoneId, String zoneName, BigDecimal actualWeight, BigDecimal chargeableWeight,
            BigDecimal ratePerKg, BigDecimal freight, BigDecimal docketCharge, BigDecimal mathadiCharge,
            BigDecimal packCharge, BigDecimal odaCharge, BigDecimal fuelSurcharge, BigDecimal fovCharge,
            BigDecimal total) {
    }
}
