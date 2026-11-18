package com.courier.modules.master.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UnserviceableRequestRepository extends JpaRepository<UnserviceableRequest, UUID> {

    @Query("select r from UnserviceableRequest r where r.companyId = :companyId and r.pincodeCode = :code and r.areaKey = :areaKey")
    Optional<UnserviceableRequest> findKey(@Param("companyId") UUID companyId, @Param("code") String code, @Param("areaKey") String areaKey);

    @Query("select r from UnserviceableRequest r where r.id = :id and r.companyId = :companyId")
    Optional<UnserviceableRequest> findOwned(@Param("id") UUID id, @Param("companyId") UUID companyId);

    /** {@code open}: null all, true still off, false turned on. Most asked first. */
    @Query(value = "select r from UnserviceableRequest r where r.companyId = :companyId "
            + "and (:open is null or (:open = true and r.turnedOnAt is null) or (:open = false and r.turnedOnAt is not null)) "
            + "and (:q is null or r.pincodeCode like :q or lower(r.areaName) like :q or lower(r.cityName) like :q) "
            + "and (:since is null or r.lastRequestedAt >= :since) "
            + "order by (r.staffCount + r.portalCount) desc, r.lastRequestedAt desc",
            countQuery = "select count(r) from UnserviceableRequest r where r.companyId = :companyId "
                    + "and (:open is null or (:open = true and r.turnedOnAt is null) or (:open = false and r.turnedOnAt is not null)) "
                    + "and (:q is null or r.pincodeCode like :q or lower(r.areaName) like :q or lower(r.cityName) like :q) "
                    + "and (:since is null or r.lastRequestedAt >= :since)")
    Page<UnserviceableRequest> search(@Param("companyId") UUID companyId, @Param("open") Boolean open, @Param("q") String q,
                                      @Param("since") Instant since, Pageable page);
}
