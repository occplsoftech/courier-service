package com.courier.modules.tariff.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Rate cards, within a company. */
public interface TariffRateCardRepository extends JpaRepository<TariffRateCard, UUID> {

    @Query("select c from TariffRateCard c where c.id = :id and c.companyId = :companyId")
    Optional<TariffRateCard> findByIdWithinCompany(@Param("id") UUID id, @Param("companyId") UUID companyId);

    @Query("select c from TariffRateCard c where c.companyId = :companyId"
            + " and (:fromZoneId is null or c.fromZoneId = :fromZoneId)"
            + " and (:status is null or c.status = :status)"
            + " and (:search is null or lower(c.code) like :search or lower(c.name) like :search)"
            + " order by c.code, c.effectiveFrom desc")
    List<TariffRateCard> search(@Param("companyId") UUID companyId, @Param("fromZoneId") UUID fromZoneId,
                                @Param("status") TariffStatus status, @Param("search") String search);

    @Query("select c from TariffRateCard c where c.companyId = :companyId and c.fromZoneId = :fromZoneId and c.status = :status")
    List<TariffRateCard> findByFromZone(@Param("companyId") UUID companyId, @Param("fromZoneId") UUID fromZoneId,
                                        @Param("status") TariffStatus status);

    @Query("select count(c) > 0 from TariffRateCard c where c.companyId = :companyId"
            + " and c.code = :code and (:excludeId is null or c.id <> :excludeId)")
    boolean existsByCode(@Param("companyId") UUID companyId, @Param("code") String code, @Param("excludeId") UUID excludeId);

    @Query("select count(c) > 0 from TariffRateCard c where c.companyId = :companyId and c.fromZoneId = :zoneId")
    boolean existsByFromZone(@Param("companyId") UUID companyId, @Param("zoneId") UUID zoneId);
}
