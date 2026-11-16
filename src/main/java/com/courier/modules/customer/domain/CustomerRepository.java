package com.courier.modules.customer.domain;

import com.courier.shared.domain.TimeOrderedUuid;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Customers, within a company.
 *
 * <p>Company-owned: every derived query relies on {@code CompanyContext} being bound.
 * Single-row loads go through {@link #findByIdWithinCompany} — a primary-key load
 * bypasses the Hibernate filter. Uniqueness checks are native and (for the code)
 * count soft-deleted rows, the same reasoning {@code BranchRepository} documents.
 */
public interface CustomerRepository extends JpaRepository<Customer, UUID>,
        JpaSpecificationExecutor<Customer> {

    @Query("select c from Customer c where c.id = :id and c.companyId = :companyId")
    Optional<Customer> findByIdWithinCompany(@Param("id") UUID id, @Param("companyId") UUID companyId);

    /**
     * Sets or clears the brand logo without touching the row's version, so saving a logo never makes an open edit form of
     * the same customer stale. Returns 0 when the customer is not in the company.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Customer c set c.logoDataUrl = :logo where c.id = :id and c.companyId = :companyId")
    int setLogo(@Param("id") UUID id, @Param("companyId") UUID companyId, @Param("logo") String logo);

    /** The customers a customer registered, newest first. */
    @Query("select c from Customer c where c.companyId = :companyId and c.parentCustomerId = :parentId order by c.createdAt desc")
    java.util.List<Customer> findChildren(@Param("companyId") UUID companyId, @Param("parentId") UUID parentId);

    /** Exact match, not the {@code search} LIKE — used to find-or-create a customer by the
     *  number typed at booking, so a repeat customer is reused rather than duplicated. */
    @Query("select c from Customer c where c.companyId = :companyId and c.mobile = :mobile")
    Optional<Customer> findByCompanyIdAndMobile(@Param("companyId") UUID companyId, @Param("mobile") String mobile);

    long countByCompanyId(UUID companyId);

    default boolean isCodeTaken(UUID companyId, String customerCode, UUID excludeId) {
        return countByCodeIncludingDeleted(TimeOrderedUuid.toBytes(companyId), customerCode,
                excludeId == null ? null : TimeOrderedUuid.toBytes(excludeId)) > 0;
    }

    /**
     * Mobile is unique within the company but, unlike the code, is <b>not</b> reserved
     * past a soft delete — see {@link Customer}'s class comment.
     */
    default boolean isMobileTaken(UUID companyId, String mobile, UUID excludeId) {
        return countByMobile(TimeOrderedUuid.toBytes(companyId), mobile,
                excludeId == null ? null : TimeOrderedUuid.toBytes(excludeId)) > 0;
    }

    @Query(value = """
            SELECT COUNT(*) FROM customers
            WHERE company_id = :companyId AND customer_code = :customerCode
              AND (:excludeId IS NULL OR id <> :excludeId)
            """, nativeQuery = true)
    long countByCodeIncludingDeleted(@Param("companyId") byte[] companyId,
                                     @Param("customerCode") String customerCode,
                                     @Param("excludeId") byte[] excludeId);

    @Query(value = """
            SELECT COUNT(*) FROM customers
            WHERE company_id = :companyId AND mobile = :mobile AND deleted = FALSE
              AND (:excludeId IS NULL OR id <> :excludeId)
            """, nativeQuery = true)
    long countByMobile(@Param("companyId") byte[] companyId,
                       @Param("mobile") String mobile,
                       @Param("excludeId") byte[] excludeId);

    /** Active business customers whose home branch this is (13.2). */
    @org.springframework.data.jpa.repository.Query("select count(c) from Customer c where c.companyId = :companyId and c.homeBranchId = :branchId "
            + "and c.customerType = com.courier.modules.customer.domain.CustomerType.BUSINESS "
            + "and c.status = com.courier.modules.customer.domain.CustomerStatus.ACTIVE")
    long countBusinessCustomersOfBranch(@org.springframework.data.repository.query.Param("companyId") UUID companyId,
                                        @org.springframework.data.repository.query.Param("branchId") UUID branchId);
}
