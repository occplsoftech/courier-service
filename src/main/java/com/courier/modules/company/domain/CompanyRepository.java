package com.courier.modules.company.domain;

import com.courier.shared.domain.TimeOrderedUuid;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Companies — the company roots.
 *
 * <p>Not company-owned, so no filter applies and {@code findById} is safe here. Access
 * is restricted at the service layer to {@code SUPER_ADMIN}.
 *
 * <p>The uniqueness pre-checks are native and count soft-deleted rows, for the same
 * reasons as {@code SubscriptionPlanRepository}: {@code @SQLRestriction} cannot be
 * disabled per query, the database unique keys do not know about {@code deleted}, and
 * MySQL returns {@code BIGINT} for a comparison, not a boolean — so the count comes
 * back as a number and is compared in Java.
 */
public interface CompanyRepository extends JpaRepository<Company, UUID>,
        JpaSpecificationExecutor<Company> {

    Optional<Company> findByCompanyId(UUID companyId);

    /** Sets (or clears, with null) the uploaded logo; returns the rows changed. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Company c set c.logoDataUrl = :logo where c.companyId = :companyId")
    int setLogoDataUrl(@Param("companyId") UUID companyId, @Param("logo") String logo);

    Optional<Company> findByCompanyCode(String companyCode);

    Optional<Company> findByExternalCompanyId(UUID externalCompanyId);

    Optional<Company> findByEmail(String email);

    default boolean isCompanyCodeTaken(String companyCode, UUID excludeId) {
        return countByColumnIncludingDeleted("company_code", companyCode, excludeId) > 0;
    }

    default boolean isEmailTaken(String email, UUID excludeId) {
        return countByColumnIncludingDeleted("email", email, excludeId) > 0;
    }

    default boolean isGstNumberTaken(String gstNumber, UUID excludeId) {
        return gstNumber != null
                && countByColumnIncludingDeleted("gst_number", gstNumber, excludeId) > 0;
    }

    default boolean isPanNumberTaken(String panNumber, UUID excludeId) {
        return panNumber != null
                && countByColumnIncludingDeleted("pan_number", panNumber, excludeId) > 0;
    }

    default boolean isCompanyIdTaken(UUID companyId) {
        return countByCompanyIdIncludingDeleted(TimeOrderedUuid.toBytes(companyId)) > 0;
    }

    /**
     * One query for four unique columns, dispatched on a column name that only this
     * interface supplies — never a caller, and never a request value, so there is no
     * injection surface. The alternative was four near-identical native queries.
     */
    default long countByColumnIncludingDeleted(String column, String value, UUID excludeId) {
        byte[] exclude = TimeOrderedUuid.toBytes(excludeId);
        return switch (column) {
            case "company_code" -> countByCompanyCodeIncludingDeleted(value, exclude);
            case "email" -> countByEmailIncludingDeleted(value, exclude);
            case "gst_number" -> countByGstIncludingDeleted(value, exclude);
            case "pan_number" -> countByPanIncludingDeleted(value, exclude);
            default -> throw new IllegalArgumentException("Unsupported unique column: " + column);
        };
    }

    @Query(value = "SELECT COUNT(*) FROM companies WHERE company_code = :value "
            + "AND (:excludeId IS NULL OR id <> :excludeId)", nativeQuery = true)
    long countByCompanyCodeIncludingDeleted(@Param("value") String value,
                                            @Param("excludeId") byte[] excludeId);

    @Query(value = "SELECT COUNT(*) FROM companies WHERE email = :value "
            + "AND (:excludeId IS NULL OR id <> :excludeId)", nativeQuery = true)
    long countByEmailIncludingDeleted(@Param("value") String value,
                                      @Param("excludeId") byte[] excludeId);

    @Query(value = "SELECT COUNT(*) FROM companies WHERE gst_number = :value "
            + "AND (:excludeId IS NULL OR id <> :excludeId)", nativeQuery = true)
    long countByGstIncludingDeleted(@Param("value") String value,
                                    @Param("excludeId") byte[] excludeId);

    @Query(value = "SELECT COUNT(*) FROM companies WHERE pan_number = :value "
            + "AND (:excludeId IS NULL OR id <> :excludeId)", nativeQuery = true)
    long countByPanIncludingDeleted(@Param("value") String value,
                                    @Param("excludeId") byte[] excludeId);

    /** Guards the generated company id against the vanishingly unlikely collision. */
    @Query(value = "SELECT COUNT(*) FROM companies WHERE company_id = :companyId", nativeQuery = true)
    long countByCompanyIdIncludingDeleted(@Param("companyId") byte[] companyId);

    /** How many companies still point at a plan — used before a plan may be deleted. */
    long countBySubscriptionPlanId(UUID subscriptionPlanId);

    /** Every company still operating — {@code ShipmentSlaSweepJob} iterates this. */
    @Query("select c.companyId from Company c where c.active = true")
    java.util.List<UUID> findAllActiveCompanyIds();

    /**
     * Companies per lifecycle state, for the platform dashboard.
     *
     * <p>One grouped query rather than five counts: the dashboard would otherwise fire a
     * round trip per status and still not be consistent between them.
     *
     * @return rows of {@code [CompanyStatus, long]}
     */
    @Query("select c.status, count(c) from Company c group by c.status")
    java.util.List<Object[]> countGroupedByStatus();

    /**
     * Companies whose trial <em>or</em> subscription ends on or before the given date —
     * the renewals worklist, as a number.
     */
    @Query("""
            select count(c) from Company c
            where (c.trialEndDate is not null and c.trialEndDate <= :date)
               or (c.subscriptionEndDate is not null and c.subscriptionEndDate <= :date)
            """)
    long countExpiringOnOrBefore(@Param("date") java.time.LocalDate date);
}
