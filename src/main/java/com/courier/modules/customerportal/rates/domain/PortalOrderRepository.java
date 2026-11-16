package com.courier.modules.customerportal.rates.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PortalOrderRepository extends JpaRepository<PortalOrder, UUID> {

    @Query("select o from PortalOrder o where o.id = :id and o.companyId = :companyId and o.customerId = :customerId")
    Optional<PortalOrder> findOwned(@Param("id") UUID id, @Param("companyId") UUID companyId, @Param("customerId") UUID customerId);

    @Query("select o from PortalOrder o where o.companyId = :companyId and o.customerId = :customerId and o.source = :source "
            + "and o.externalRef = :ref")
    Optional<PortalOrder> findByRef(@Param("companyId") UUID companyId, @Param("customerId") UUID customerId,
                                    @Param("source") PortalOrder.Source source, @Param("ref") String ref);

    @Query(value = "select o from PortalOrder o where o.companyId = :companyId and o.customerId = :customerId and o.status in :statuses "
            + "order by o.createdAt desc",
            countQuery = "select count(o) from PortalOrder o where o.companyId = :companyId and o.customerId = :customerId "
                    + "and o.status in :statuses")
    Page<PortalOrder> findForCustomer(@Param("companyId") UUID companyId, @Param("customerId") UUID customerId,
                                      @Param("statuses") Collection<PortalOrder.Status> statuses, Pageable page);

    @Query("select o.status, count(o) from PortalOrder o where o.companyId = :companyId and o.customerId = :customerId group by o.status")
    List<Object[]> countByStatus(@Param("companyId") UUID companyId, @Param("customerId") UUID customerId);
}
