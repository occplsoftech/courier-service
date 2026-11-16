package com.courier.modules.customerportal.rates.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface BranchCourierAccessRepository extends JpaRepository<BranchCourierAccess, UUID> {

    @Query("select a.providerCode from BranchCourierAccess a where a.companyId = :companyId and a.branchId = :branchId")
    List<String> findCodes(@Param("companyId") UUID companyId, @Param("branchId") UUID branchId);

    /** A hard delete: the whole set is replaced on save, and a soft-deleted row would still hold the unique key. */
    @Modifying
    @Query("delete from BranchCourierAccess a where a.companyId = :companyId and a.branchId = :branchId")
    void deleteForBranch(@Param("companyId") UUID companyId, @Param("branchId") UUID branchId);
}
