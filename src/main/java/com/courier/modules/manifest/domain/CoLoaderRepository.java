package com.courier.modules.manifest.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CoLoaderRepository extends JpaRepository<CoLoader, UUID> {

    @Query("select c from CoLoader c where c.companyId = :companyId and (:all = true or c.active = true) order by lower(c.name)")
    List<CoLoader> list(@Param("companyId") UUID companyId, @Param("all") boolean all);

    @Query("select c from CoLoader c where c.id = :id and c.companyId = :companyId")
    Optional<CoLoader> findWithinCompany(@Param("id") UUID id, @Param("companyId") UUID companyId);

    boolean existsByCompanyIdAndNameIgnoreCase(UUID companyId, String name);
}
