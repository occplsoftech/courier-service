package com.courier.modules.tariff.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** Weight slabs of rate cards, within a company. */
public interface TariffRateSlabRepository extends JpaRepository<TariffRateSlab, UUID> {

    @Query("select s from TariffRateSlab s where s.companyId = :companyId and s.rateCardId = :cardId"
            + " order by s.toZoneId, s.serviceTypeId, s.packageTypeId, s.weightFromKg")
    List<TariffRateSlab> findByCard(@Param("companyId") UUID companyId, @Param("cardId") UUID cardId);

    @Query("select s from TariffRateSlab s where s.companyId = :companyId and s.rateCardId = :cardId"
            + " and s.toZoneId = :toZoneId and s.serviceTypeId = :serviceTypeId and s.packageTypeId = :packageTypeId"
            + " order by s.weightFromKg")
    List<TariffRateSlab> findLane(@Param("companyId") UUID companyId, @Param("cardId") UUID cardId,
                                  @Param("toZoneId") UUID toZoneId, @Param("serviceTypeId") UUID serviceTypeId,
                                  @Param("packageTypeId") UUID packageTypeId);

    @Query("select count(s) > 0 from TariffRateSlab s where s.companyId = :companyId and s.toZoneId = :zoneId")
    boolean existsByToZone(@Param("companyId") UUID companyId, @Param("zoneId") UUID zoneId);
}
