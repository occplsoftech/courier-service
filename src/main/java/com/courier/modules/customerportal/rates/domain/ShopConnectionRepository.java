package com.courier.modules.customerportal.rates.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShopConnectionRepository extends JpaRepository<ShopConnection, UUID> {

    @Query("select c from ShopConnection c where c.companyId = :companyId and c.customerId = :customerId and c.platform = :platform")
    Optional<ShopConnection> findForCustomer(@Param("companyId") UUID companyId, @Param("customerId") UUID customerId,
                                             @Param("platform") ShopConnection.Platform platform);

    /** Connections whose import is due, across companies (the import job runs with no signed-in user). */
    @Query("select c from ShopConnection c where c.paused = false and c.secretsEnc is not null "
            + "and (c.lastImportAt is null or c.lastImportAt < :before) order by c.lastImportAt asc")
    List<ShopConnection> findDueAcrossCompanies(@Param("before") Instant before, Pageable page);
}
