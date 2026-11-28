package com.courier.modules.charge.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface BookingChargeRateRepository extends JpaRepository<BookingChargeRate, UUID> {

    /** The company's own rows (no customer). */
    @Query("select r from BookingChargeRate r where r.companyId = :companyId and r.customerId is null")
    List<BookingChargeRate> findCompanyRates(@Param("companyId") UUID companyId);

    /** One business customer's overrides. */
    @Query("select r from BookingChargeRate r where r.companyId = :companyId and r.customerId = :customerId")
    List<BookingChargeRate> findCustomerRates(@Param("companyId") UUID companyId, @Param("customerId") UUID customerId);
}
