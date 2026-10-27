package com.courier.modules.tariff.api;

import com.courier.modules.tariff.domain.ChargeAppliesWhen;
import com.courier.modules.tariff.domain.ChargeCalcMethod;
import com.courier.modules.tariff.domain.SlabRateType;
import com.courier.modules.tariff.domain.TariffStatus;
import com.courier.modules.tariff.domain.TotalRounding;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Wire contract of {@code /api/v1/tariff-rate-cards}. */
public final class TariffRateCardDtos {

    private TariffRateCardDtos() {
    }

    public record CardRequest(
            @NotBlank @Size(max = 30) String code,
            @NotBlank @Size(max = 100) String name,
            @NotNull UUID fromZoneId,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @DecimalMin("0") BigDecimal gstPercent,
            @Positive Integer volumetricDivisor,
            TotalRounding totalRounding,
            @Size(max = 255) String description,
            @PositiveOrZero Long version) {
    }

    public record SlabRequest(@NotNull BigDecimal weightFromKg, @NotNull BigDecimal weightToKg, @NotNull SlabRateType rateType,
                              @NotNull @DecimalMin("0") BigDecimal rate, BigDecimal stepKg) {
    }

    public record LaneRequest(@NotNull UUID toZoneId, @NotNull UUID serviceTypeId, @NotNull UUID packageTypeId,
                              @NotEmpty @Size(max = 50) @Valid List<SlabRequest> slabs) {
    }

    public record ChargeRequest(
            @NotBlank @Size(max = 30) String code,
            @NotBlank @Size(max = 100) String name,
            UUID toZoneId, UUID serviceTypeId, UUID packageTypeId,
            ChargeAppliesWhen appliesWhen,
            @NotNull ChargeCalcMethod calcMethod,
            @NotNull @DecimalMin("0") BigDecimal value,
            @DecimalMin("0") BigDecimal minAmount,
            @DecimalMin("0") BigDecimal maxAmount,
            Boolean taxable,
            @PositiveOrZero Integer displayOrder,
            TariffStatus status) {
    }

    public record SlabResponse(UUID id, BigDecimal weightFromKg, BigDecimal weightToKg, SlabRateType rateType,
                               BigDecimal rate, BigDecimal stepKg) {
    }

    /** A lane is every slab of one to zone × service type × package type. */
    public record LaneResponse(UUID toZoneId, String toZoneCode, UUID serviceTypeId, String serviceTypeCode,
                               UUID packageTypeId, String packageTypeCode, List<SlabResponse> slabs) {
    }

    public record ChargeResponse(UUID id, String code, String name, UUID toZoneId, String toZoneCode, UUID serviceTypeId,
                                 String serviceTypeCode, UUID packageTypeId, String packageTypeCode,
                                 ChargeAppliesWhen appliesWhen, ChargeCalcMethod calcMethod, BigDecimal value,
                                 BigDecimal minAmount, BigDecimal maxAmount, boolean taxable, int displayOrder,
                                 TariffStatus status) {
    }

    public record CardResponse(UUID id, String code, String name, UUID fromZoneId, String fromZoneCode,
                               LocalDate effectiveFrom, LocalDate effectiveTo, BigDecimal gstPercent,
                               Integer volumetricDivisor, TotalRounding totalRounding, String description,
                               TariffStatus status, int laneCount, int chargeCount, List<LaneResponse> lanes,
                               List<ChargeResponse> charges, Instant createdDate, Instant updatedDate, Long version) {
    }

    public record QuoteRequest(
            @NotBlank @Size(max = 6) String originPincode,
            @NotBlank @Size(max = 6) String destinationPincode,
            @NotNull UUID serviceTypeId,
            @NotNull UUID packageTypeId,
            @NotNull @Positive BigDecimal actualWeightKg,
            @Positive BigDecimal lengthCm, @Positive BigDecimal widthCm, @Positive BigDecimal heightCm,
            @Positive Integer pieces,
            @PositiveOrZero BigDecimal declaredValue,
            boolean cod,
            LocalDate bookingDate) {
    }

    public record ZoneRef(UUID id, String code, String name, String matchedBy) {
    }

    public record ChargeLineResponse(String code, String name, BigDecimal amount, boolean taxable, String basis) {
    }

    public record TaxLineResponse(String code, BigDecimal percent, BigDecimal amount) {
    }

    public record QuoteResponse(UUID rateCardId, String rateCardCode, ZoneRef fromZone, ZoneRef toZone,
                                String serviceTypeCode, String packageTypeCode, boolean oda,
                                BigDecimal actualWeightKg, BigDecimal volumetricWeightKg, BigDecimal chargeableWeightKg,
                                BigDecimal freight, String freightBasis, List<ChargeLineResponse> charges,
                                BigDecimal taxableAmount, List<TaxLineResponse> taxes, BigDecimal taxTotal,
                                BigDecimal nonTaxableAmount, BigDecimal roundOff, BigDecimal total) {
    }
}
