package com.courier.modules.customerpricing.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** A business customer's rate structure rows (V177). */
public interface StructureRateRepository extends JpaRepository<StructureRate, UUID> {

    @Query("select r from StructureRate r where r.companyId = :companyId and r.customerId = :customerId")
    List<StructureRate> findByCustomer(@Param("companyId") UUID companyId, @Param("customerId") UUID customerId);

    @Query("select r from StructureRate r where r.companyId = :companyId and r.customerId = :customerId"
            + " and r.serviceTypeId = :serviceTypeId")
    List<StructureRate> findByCustomerAndService(@Param("companyId") UUID companyId, @Param("customerId") UUID customerId,
                                                 @Param("serviceTypeId") UUID serviceTypeId);
}
