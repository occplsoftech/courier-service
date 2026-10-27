package com.courier.modules.tariff.application;

import com.courier.modules.tariff.domain.ChargeAppliesWhen;
import com.courier.modules.tariff.domain.ChargeCalcMethod;
import com.courier.modules.tariff.domain.SlabRateType;
import com.courier.modules.tariff.domain.TariffChargeRule;
import com.courier.modules.tariff.domain.TariffRateCard;
import com.courier.modules.tariff.domain.TariffRateSlab;
import com.courier.modules.tariff.domain.TariffStatus;
import com.courier.modules.tariff.domain.TotalRounding;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rate cards of the zone tariff: the card (from zone, dates, GST, rounding), its lanes of weight slabs (to zone × service
 * type × package type) and its charge rules. A lane's slabs are always replaced together so they can be checked as a
 * whole. Writes are {@code COMPANY_ADMIN}; reads are any signed-in user.
 */
public interface TariffRateCardService {

    RateCardDetail create(CardInput input);

    RateCardDetail update(UUID cardId, CardInput input, Long expectedVersion);

    RateCardDetail getById(UUID cardId);

    List<RateCardSummary> search(UUID fromZoneId, TariffStatus status, String search);

    /** Refused while another active card of the same from zone covers any of the same dates, or the card has no rates. */
    RateCardDetail activate(UUID cardId);

    RateCardDetail deactivate(UUID cardId);

    /** Soft-deletes the card with its slabs and charges. */
    void delete(UUID cardId);

    /** Replaces every slab of one lane. */
    RateCardDetail saveLane(UUID cardId, LaneInput lane);

    RateCardDetail deleteLane(UUID cardId, UUID toZoneId, UUID serviceTypeId, UUID packageTypeId);

    RateCardDetail addCharge(UUID cardId, ChargeInput input);

    RateCardDetail updateCharge(UUID cardId, UUID chargeId, ChargeInput input);

    RateCardDetail deleteCharge(UUID cardId, UUID chargeId);

    record CardInput(String code, String name, UUID fromZoneId, LocalDate effectiveFrom, LocalDate effectiveTo,
                     BigDecimal gstPercent, Integer volumetricDivisor, TotalRounding totalRounding, String description) {
    }

    record SlabInput(BigDecimal weightFromKg, BigDecimal weightToKg, SlabRateType rateType, BigDecimal rate, BigDecimal stepKg) {
    }

    record LaneInput(UUID toZoneId, UUID serviceTypeId, UUID packageTypeId, List<SlabInput> slabs) {
    }

    record ChargeInput(String code, String name, UUID toZoneId, UUID serviceTypeId, UUID packageTypeId,
                       ChargeAppliesWhen appliesWhen, ChargeCalcMethod calcMethod, BigDecimal value, BigDecimal minAmount,
                       BigDecimal maxAmount, Boolean taxable, Integer displayOrder, TariffStatus status) {
    }

    /** {@code labels}: code of every zone, service type and package type the card mentions, by id. */
    record RateCardDetail(TariffRateCard card, List<TariffRateSlab> slabs, List<TariffChargeRule> charges,
                          Map<UUID, String> labels) {
    }

    record RateCardSummary(TariffRateCard card, String fromZoneCode, int laneCount, int chargeCount) {
    }
}
