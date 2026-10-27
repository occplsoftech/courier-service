package com.courier.modules.tariff.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Charge rules of rate cards, within a company. */
public interface TariffChargeRuleRepository extends JpaRepository<TariffChargeRule, UUID> {

    @Query("select r from TariffChargeRule r where r.id = :id and r.companyId = :companyId")
    Optional<TariffChargeRule> findByIdWithinCompany(@Param("id") UUID id, @Param("companyId") UUID companyId);

    @Query("select r from TariffChargeRule r where r.companyId = :companyId and r.rateCardId = :cardId"
            + " order by r.displayOrder, r.chargeCode")
    List<TariffChargeRule> findByCard(@Param("companyId") UUID companyId, @Param("cardId") UUID cardId);

    @Query("select count(r) > 0 from TariffChargeRule r where r.companyId = :companyId and r.toZoneId = :zoneId")
    boolean existsByToZone(@Param("companyId") UUID companyId, @Param("zoneId") UUID zoneId);
}
