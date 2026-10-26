package com.courier.modules.finance.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface WalletTopupRequestRepository extends JpaRepository<WalletTopupRequest, UUID>,
        JpaSpecificationExecutor<WalletTopupRequest> {

    @Query("select r from WalletTopupRequest r where r.id = :id and r.companyId = :companyId")
    Optional<WalletTopupRequest> findByIdWithinCompany(@Param("id") UUID id,
                                                        @Param("companyId") UUID companyId);

    /** One customer's own requests. Explicit company predicate, like every single-owner read. */
    @Query("select r from WalletTopupRequest r where r.customerId = :customerId and r.companyId = :companyId")
    org.springframework.data.domain.Page<WalletTopupRequest> pageByCustomerWithinCompany(
            @Param("customerId") UUID customerId, @Param("companyId") UUID companyId,
            org.springframework.data.domain.Pageable pageable);
}
