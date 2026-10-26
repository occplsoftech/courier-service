package com.courier.modules.finance.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The wallet ledger.
 *
 * <p>Append-only: there is no update or delete path here, and the service never calls one.
 * Reads are company-scoped twice over — the Hibernate filter, plus an explicit predicate on
 * every query, because a statement crossing companies is a Sev-1.
 *
 * <p>The aggregate queries all exclude unsettled payments. A {@code PENDING} recharge is an
 * intent, not money: counting it in "today's credit" would show a branch a balance it does
 * not have. {@code paymentStatus is null} is the ordinary case (a booking debit, a manual
 * credit) and does count.
 */
public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, UUID>,
        JpaSpecificationExecutor<WalletTransaction> {

    @Query("select t from WalletTransaction t where t.id = :id and t.companyId = :companyId")
    Optional<WalletTransaction> findByIdWithinCompany(@Param("id") UUID id,
                                                     @Param("companyId") UUID companyId);

    /** One wallet's statement, newest first by the caller's {@code Pageable}. The company
     *  predicate is explicit, like every single-wallet read here. */
    @Query("select t from WalletTransaction t where t.walletId = :walletId and t.companyId = :companyId")
    org.springframework.data.domain.Page<WalletTransaction> pageByWalletWithinCompany(
            @Param("walletId") UUID walletId, @Param("companyId") UUID companyId,
            org.springframework.data.domain.Pageable pageable);

    Optional<WalletTransaction> findByTransactionNo(String transactionNo);

    boolean existsByTransactionNo(String transactionNo);

    /**
     * The idempotency lookup: a gateway payment id already recorded for this company must
     * never credit the wallet a second time, however many times the client retries.
     */
    @Query("""
            select t from WalletTransaction t
            where t.companyId = :companyId and t.paymentReference = :paymentReference
            """)
    Optional<WalletTransaction> findByPaymentReferenceWithinCompany(
            @Param("paymentReference") String paymentReference, @Param("companyId") UUID companyId);

    /**
     * Has this gateway payment been recorded by <em>anyone</em>?
     *
     * <p><b>Deliberately not company-scoped</b>, and native for that reason: the Hibernate
     * filter would confine it to the caller's own company, which is precisely the check
     * that must not happen here. One merchant account serves the whole platform, so a
     * payment id belongs to exactly one wallet globally; without this, company B could
     * present company A's payment id and be credited for it. The caller turns a hit into a
     * flat refusal — it never reveals whose it was.
     *
     * <p>Native, like the uniqueness counts elsewhere in the project, and counted rather
     * than returned as a boolean because MySQL answers {@code COUNT(*) > 0} with a BIGINT.
     */
    @Query(value = "SELECT COUNT(*) FROM wallet_transactions WHERE payment_reference = :ref",
            nativeQuery = true)
    long countByPaymentReferenceAcrossCompanies(@Param("ref") String ref);

    default boolean isPaymentReferenceRecorded(String paymentReference) {
        return paymentReference != null
                && countByPaymentReferenceAcrossCompanies(paymentReference) > 0;
    }

    /**
     * Sums settled entries of one direction since a point in time.
     *
     * <p>{@code from} is non-null by contract — "all time" is {@link java.time.Instant#EPOCH},
     * not null. A nullable bound would need {@code (:from is null or ...)} in the JPQL, and
     * a null timestamp parameter is exactly the shape Hibernate cannot always infer a type
     * for; a sum that fails at runtime is worse than a caller passing a floor.
     *
     * <p>Returns null when nothing matches ({@code sum} over no rows), which the caller
     * normalises to zero rather than a {@code coalesce} whose literal type Hibernate has to
     * reconcile against a {@code DECIMAL}.
     *
     * @param from inclusive lower bound on {@code createdAt}
     */
    @Query("""
            select sum(t.amount) from WalletTransaction t
            where t.walletId = :walletId and t.companyId = :companyId
              and t.transactionType = :type
              and t.createdAt >= :from
            """)
    BigDecimal sumSettledSince(@Param("walletId") UUID walletId,
                               @Param("companyId") UUID companyId,
                               @Param("type") TransactionType type,
                               @Param("from") Instant from);

    @Query("""
            select count(t) from WalletTransaction t
            where t.walletId = :walletId and t.companyId = :companyId
            """)
    long countByWallet(@Param("walletId") UUID walletId, @Param("companyId") UUID companyId);

    /**
     * Same shape as {@link #sumSettledSince}, narrowed to a set of reasons — the wallet
     * dashboard's "today's credit commission" wants only {@code COM}/{@code DRS}, not every
     * credit reason {@code sumSettledSince} sums.
     */
    @Query("""
            select sum(t.amount) from WalletTransaction t
            where t.walletId = :walletId and t.companyId = :companyId
              and t.transactionType = :type
              and t.subTransactionType in :subTypes
              and t.createdAt >= :from
            """)
    BigDecimal sumSettledSinceForSubTypes(@Param("walletId") UUID walletId,
                               @Param("companyId") UUID companyId,
                               @Param("type") TransactionType type,
                               @Param("subTypes") java.util.Collection<SubTransactionType> subTypes,
                               @Param("from") Instant from);

    /** Newest entries first — the statement head and the dashboard's "recent activity". */
    @Query("""
            select t from WalletTransaction t
            where t.walletId = :walletId and t.companyId = :companyId
            order by t.createdAt desc, t.transactionNo desc
            """)
    List<WalletTransaction> findRecent(@Param("walletId") UUID walletId,
                                       @Param("companyId") UUID companyId,
                                       org.springframework.data.domain.Pageable pageable);

    // -------------------------------------------------------------- dashboard: recent activity
    // Same explicit-companyId discipline documented on the class above, and on
    // ShipmentRepository / DashboardServiceImpl.summary() (ISSUE-001).

    /** Unfiltered on purpose — only safe inside a CompanyContext.runAs(null, ...) block. */
    List<WalletTransaction> findTop5ByOrderByCreatedAtDesc();

    List<WalletTransaction> findTop5ByCompanyIdOrderByCreatedAtDesc(UUID companyId);

    /**
     * Every settled entry filed against one reference — the reversal lookup a shipment
     * cancellation walks to know what to undo. Settled only: a still-{@code PENDING} gateway
     * intent never moved money, so there is nothing to reverse.
     */
    @Query("""
            select t from WalletTransaction t
            where t.companyId = :companyId and t.referenceType = :referenceType
              and t.referenceId = :referenceId
            order by t.createdAt asc
            """)
    List<WalletTransaction> findSettledByReferenceWithinCompany(
            @Param("referenceType") ReferenceType referenceType,
            @Param("referenceId") String referenceId,
            @Param("companyId") UUID companyId);

    /** Most recent settled entry of one reason — used for "last recharge". */
    @Query("""
            select t from WalletTransaction t
            where t.walletId = :walletId and t.companyId = :companyId
              and t.subTransactionType = :subType
            order by t.createdAt desc
            """)
    List<WalletTransaction> findLatestSettledOfType(@Param("walletId") UUID walletId,
                                                    @Param("companyId") UUID companyId,
                                                    @Param("subType") SubTransactionType subType,
                                                    org.springframework.data.domain.Pageable pageable);

    // ------------------------------------------------------- daily business summary
    // Company-wide (every wallet of the company, branch wallets included), window is
    // [from, to). Explicit companyId like the dashboard queries above — called from a
    // scheduler thread. sum returns null over no rows; callers normalise to zero.

    @Query("""
            select sum(t.amount) from WalletTransaction t
            where t.companyId = :companyId and t.transactionType = :type
              and t.subTransactionType in :subTypes
              and t.createdAt >= :from and t.createdAt < :to
            """)
    BigDecimal sumForCompanyBetween(@Param("companyId") UUID companyId, @Param("type") TransactionType type,
                                    @Param("subTypes") java.util.Collection<SubTransactionType> subTypes,
                                    @Param("from") Instant from, @Param("to") Instant to);

    @Query("""
            select count(t) from WalletTransaction t
            where t.companyId = :companyId and t.transactionType = :type
              and t.subTransactionType in :subTypes
              and t.createdAt >= :from and t.createdAt < :to
            """)
    long countForCompanyBetween(@Param("companyId") UUID companyId, @Param("type") TransactionType type,
                                @Param("subTypes") java.util.Collection<SubTransactionType> subTypes,
                                @Param("from") Instant from, @Param("to") Instant to);

    /** Either direction — COD/TPY entries can legitimately be a credit or a debit. */
    @Query("""
            select sum(t.amount) from WalletTransaction t
            where t.companyId = :companyId and t.subTransactionType in :subTypes
              and t.createdAt >= :from and t.createdAt < :to
            """)
    BigDecimal sumAnyDirectionForCompanyBetween(@Param("companyId") UUID companyId,
                                                @Param("subTypes") java.util.Collection<SubTransactionType> subTypes,
                                                @Param("from") Instant from, @Param("to") Instant to);
}
