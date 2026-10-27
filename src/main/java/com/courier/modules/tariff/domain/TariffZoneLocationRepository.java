package com.courier.modules.tariff.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TariffZoneLocationRepository extends JpaRepository<TariffZoneLocation, UUID> {

    @Query("select l from TariffZoneLocation l where l.id = :id and l.companyId = :companyId")
    Optional<TariffZoneLocation> findByIdWithinCompany(@Param("id") UUID id, @Param("companyId") UUID companyId);

    @Query("select l from TariffZoneLocation l where l.companyId = :companyId and l.zoneId = :zoneId")
    List<TariffZoneLocation> findByZone(@Param("companyId") UUID companyId, @Param("zoneId") UUID zoneId);

    @Query("select l from TariffZoneLocation l where l.companyId = :companyId and l.zoneId in :zoneIds")
    List<TariffZoneLocation> findByZones(@Param("companyId") UUID companyId, @Param("zoneIds") Collection<UUID> zoneIds);

    /** Live locations naming exactly this place at its own scope, in any zone of the company. */
    @Query("select l from TariffZoneLocation l where l.companyId = :companyId and l.scope = :scope and l.stateId = :id")
    List<TariffZoneLocation> findSameState(@Param("companyId") UUID companyId, @Param("scope") LocationScope scope, @Param("id") UUID id);

    @Query("select l from TariffZoneLocation l where l.companyId = :companyId and l.scope = :scope and l.districtId = :id")
    List<TariffZoneLocation> findSameDistrict(@Param("companyId") UUID companyId, @Param("scope") LocationScope scope, @Param("id") UUID id);

    @Query("select l from TariffZoneLocation l where l.companyId = :companyId and l.scope = :scope and l.cityId = :id")
    List<TariffZoneLocation> findSameCity(@Param("companyId") UUID companyId, @Param("scope") LocationScope scope, @Param("id") UUID id);

    @Query("select l from TariffZoneLocation l where l.companyId = :companyId and l.scope = :scope and l.pincode = :pincode")
    List<TariffZoneLocation> findSamePincode(@Param("companyId") UUID companyId, @Param("scope") LocationScope scope, @Param("pincode") String pincode);

    /**
     * Every live location that could cover a destination: its pincode, its city, its district or its state. The caller
     * picks the most specific among the active zones of the right usage. A null argument simply matches nothing.
     */
    @Query("select l from TariffZoneLocation l where l.companyId = :companyId and ("
            + " (l.scope = com.courier.modules.tariff.domain.LocationScope.PINCODE and l.pincode = :pincode)"
            + " or (l.scope = com.courier.modules.tariff.domain.LocationScope.CITY and l.cityId = :cityId)"
            + " or (l.scope = com.courier.modules.tariff.domain.LocationScope.DISTRICT and l.districtId = :districtId)"
            + " or (l.scope = com.courier.modules.tariff.domain.LocationScope.STATE and l.stateId = :stateId))")
    List<TariffZoneLocation> findCandidates(@Param("companyId") UUID companyId, @Param("pincode") String pincode,
                                            @Param("cityId") UUID cityId, @Param("districtId") UUID districtId,
                                            @Param("stateId") UUID stateId);
}
