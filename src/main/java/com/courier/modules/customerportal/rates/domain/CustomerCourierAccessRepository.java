package com.courier.modules.customerportal.rates.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface CustomerCourierAccessRepository extends JpaRepository<CustomerCourierAccess, UUID> {

    @Query("select a.providerCode from CustomerCourierAccess a where a.companyId = :companyId and a.customerId = :customerId")
    List<String> findCodes(@Param("companyId") UUID companyId, @Param("customerId") UUID customerId);

    /** A hard delete: the whole set is replaced on save, and a soft-deleted row would still hold the unique key. */
    @Modifying
    @Query("delete from CustomerCourierAccess a where a.companyId = :companyId and a.customerId = :customerId")
    void deleteForCustomer(@Param("companyId") UUID companyId, @Param("customerId") UUID customerId);
}
