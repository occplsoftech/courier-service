package com.courier.modules.company.domain;

import com.courier.shared.domain.TimeOrderedUuid;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Users, from the company-administration context.
 *
 * <p>Company-owned: every derived query relies on {@code CompanyContext} being bound.
 * A {@code SUPER_ADMIN} request carries no company, so the filter is inactive and reads
 * span every company — intentional for the platform-wide user listing, and the reason
 * writes are pinned to the caller's company in the service.
 *
 * <p>This is the company context's own repository over the shared {@code users} table;
 * auth has its own. See {@link User} for why the table is a shared kernel.
 */
public interface CompanyUserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);

    Optional<User> findByUsername(String username);

    /**
     * Explicit company predicate rather than {@code findById}: a primary-key load bypasses
     * the Hibernate filter, so one company could otherwise fetch another's user by id.
     */
    @Query("select u from CompanyUser u where u.id = :id and u.companyId = :companyId")
    Optional<User> findByIdWithinCompany(@Param("id") UUID id, @Param("companyId") UUID companyId);

    /**
     * Users of one company by id. Used to bulk-assign users to a branch: every requested
     * id must resolve to a user of the caller's company, so a foreign id is simply absent
     * from the result rather than silently updated.
     */
    @Query("select u from CompanyUser u where u.companyId = :companyId and u.id in :ids")
    java.util.List<User> findAllByIdInWithinCompany(@Param("ids") java.util.Collection<UUID> ids,
                                                   @Param("companyId") UUID companyId);

    long countByCompanyId(UUID companyId);

    /** Headcount by lifecycle state, for the company statistics view. */
    long countByCompanyIdAndStatus(UUID companyId, UserStatus status);

    /** Whether any user is currently placed in this department — a delete guard. */
    boolean existsByDepartmentId(UUID departmentId);

    // ---------------------------------------------------------------- uniqueness
    // All count soft-deleted rows too: the unique keys do not know about `deleted`, and
    // @SQLRestriction cannot be switched off per query. Count, not boolean — MySQL has no
    // boolean type, so COUNT(*) > 0 comes back BIGINT and mapping it to boolean throws.

    default boolean isEmailTaken(UUID companyId, String email, UUID excludeId) {
        return countByCompanyEmailIncludingDeleted(TimeOrderedUuid.toBytes(companyId), email,
                TimeOrderedUuid.toBytes(excludeId)) > 0;
    }

    /**
     * A soft-deleted user with this email, if any. {@code @SQLRestriction} hides it from
     * every derived and JPQL query, so only a native query can see it — needed because
     * creating a user with a formerly-deleted colleague's email must resurrect that row
     * rather than insert a second one, which would collide with the unique key
     * {@code (company_id, email)} (it does not know about {@code deleted} either).
     */
    default Optional<User> findAnyByCompanyAndEmailIncludingDeleted(UUID companyId, String email) {
        return findAnyByCompanyAndEmailIncludingDeletedNative(TimeOrderedUuid.toBytes(companyId), email);
    }

    @Query(value = "SELECT * FROM users WHERE company_id = :companyId AND email = :email LIMIT 1",
            nativeQuery = true)
    Optional<User> findAnyByCompanyAndEmailIncludingDeletedNative(@Param("companyId") byte[] companyId,
                                                                  @Param("email") String email);

    default boolean isEmployeeCodeTaken(UUID companyId, String employeeCode, UUID excludeId) {
        return employeeCode != null && countByCompanyEmployeeCodeIncludingDeleted(
                TimeOrderedUuid.toBytes(companyId), employeeCode, TimeOrderedUuid.toBytes(excludeId)) > 0;
    }

    /**
     * Next {@code <branchCode>-N} sequence number for a branch employee's auto-generated
     * {@code employeeCode} — one past the highest N already used under that branch's
     * prefix, company-wide and including soft-deleted rows, so a deleted employee's number
     * is never reissued (the unique key does not know about {@code deleted} either).
     */
    default int nextEmployeeSequence(UUID companyId, String branchCode) {
        long max = maxEmployeeSequenceForPrefix(TimeOrderedUuid.toBytes(companyId), branchCode + "-");
        return (int) (max + 1);
    }

    @Query(value = """
            SELECT COALESCE(MAX(CAST(SUBSTRING(employee_code, LENGTH(:prefix) + 1) AS UNSIGNED)), 0)
            FROM users
            WHERE company_id = :companyId AND employee_code LIKE CONCAT(:prefix, '%')
            """, nativeQuery = true)
    long maxEmployeeSequenceForPrefix(@Param("companyId") byte[] companyId, @Param("prefix") String prefix);

    /** Username is globally unique, so this check is not company-scoped. */
    default boolean isUsernameTaken(String username, UUID excludeId) {
        return username != null && countByUsernameIncludingDeleted(
                username, TimeOrderedUuid.toBytes(excludeId)) > 0;
    }

    @Query(value = """
            SELECT COUNT(*) FROM users
            WHERE company_id = :companyId AND email = :email
              AND (:excludeId IS NULL OR id <> :excludeId)
            """, nativeQuery = true)
    long countByCompanyEmailIncludingDeleted(@Param("companyId") byte[] companyId,
                                            @Param("email") String email,
                                            @Param("excludeId") byte[] excludeId);

    @Query(value = """
            SELECT COUNT(*) FROM users
            WHERE company_id = :companyId AND employee_code = :employeeCode
              AND (:excludeId IS NULL OR id <> :excludeId)
            """, nativeQuery = true)
    long countByCompanyEmployeeCodeIncludingDeleted(@Param("companyId") byte[] companyId,
                                                   @Param("employeeCode") String employeeCode,
                                                   @Param("excludeId") byte[] excludeId);

    @Query(value = """
            SELECT COUNT(*) FROM users
            WHERE username = :username
              AND (:excludeId IS NULL OR id <> :excludeId)
            """, nativeQuery = true)
    long countByUsernameIncludingDeleted(@Param("username") String username,
                                         @Param("excludeId") byte[] excludeId);

    /**
     * Active users of a company holding any of the role codes, through either the company-role grants
     * ({@code user_company_roles.role_code}) or the legacy JWT role ({@code user_roles.role}). With a branch, only
     * users placed at it ({@code users.branch_id}) or managing it ({@code branches.manager_id}). Ids as BINARY(16).
     */
    @Query(value = """
            SELECT DISTINCT u.id FROM users u
            WHERE u.company_id = :companyId AND u.deleted = FALSE AND u.status = 'ACTIVE'
              AND (:branchId IS NULL OR u.branch_id = :branchId
                   OR u.id IN (SELECT b.manager_id FROM branches b WHERE b.id = :branchId AND b.deleted = FALSE))
              AND (EXISTS (SELECT 1 FROM user_company_roles r
                           WHERE r.user_id = u.id AND r.deleted = FALSE AND r.role_code IN (:roleCodes))
                   OR EXISTS (SELECT 1 FROM user_roles ur WHERE ur.user_id = u.id AND ur.role IN (:roleCodes)))
            """, nativeQuery = true)
    java.util.List<byte[]> findActiveIdsWithAnyRole(@Param("companyId") byte[] companyId,
                                                    @Param("branchId") byte[] branchId,
                                                    @Param("roleCodes") java.util.Collection<String> roleCodes);
}
