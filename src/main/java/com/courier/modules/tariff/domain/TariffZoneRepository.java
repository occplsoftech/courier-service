package com.courier.modules.tariff.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tariff zones, within a company. Single-row loads go through {@link #findByIdWithinCompany}. */
public interface TariffZoneRepository extends JpaRepository<TariffZone, UUID> {

    @Query("select z from TariffZone z where z.id = :id and z.companyId = :companyId")
    Optional<TariffZone> findByIdWithinCompany(@Param("id") UUID id, @Param("companyId") UUID companyId);

    @Query("select z from TariffZone z where z.companyId = :companyId and z.id in :ids")
    List<TariffZone> findAllWithinCompany(@Param("ids") Collection<UUID> ids, @Param("companyId") UUID companyId);

    @Query("select z from TariffZone z where z.companyId = :companyId"
            + " and (:usage is null or z.zoneUsage = :usage)"
            + " and (:status is null or z.status = :status)"
            + " and (:search is null or lower(z.code) like :search or lower(z.name) like :search)"
            + " order by z.displayOrder, z.code")
    List<TariffZone> search(@Param("companyId") UUID companyId, @Param("usage") ZoneUsage usage,
                            @Param("status") TariffZoneStatus status, @Param("search") String search);

    @Query("select count(z) > 0 from TariffZone z where z.companyId = :companyId"
            + " and z.code = :code and (:excludeId is null or z.id <> :excludeId)")
    boolean existsByCode(@Param("companyId") UUID companyId, @Param("code") String code,
                         @Param("excludeId") UUID excludeId);
}
