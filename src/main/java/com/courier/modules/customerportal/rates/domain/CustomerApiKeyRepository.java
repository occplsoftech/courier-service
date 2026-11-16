package com.courier.modules.customerportal.rates.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerApiKeyRepository extends JpaRepository<CustomerApiKey, UUID> {

    /** Any company: the public API knows only the key. */
    @Query("select k from CustomerApiKey k where k.keyHash = :hash")
    Optional<CustomerApiKey> findByHash(@Param("hash") String hash);

    @Query("select k from CustomerApiKey k where k.companyId = :companyId and k.customerId = :customerId order by k.createdAt desc")
    List<CustomerApiKey> findForCustomer(@Param("companyId") UUID companyId, @Param("customerId") UUID customerId);
}
