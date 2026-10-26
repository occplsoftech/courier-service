package com.courier.modules.finance.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Branch wallets, within a company.
 *
 * <p>Company-owned. Single-row loads carry an explicit company predicate rather than using
 * {@code findById}, which bypasses the Hibernate filter.
 *
 * <p>{@link #lockByBranchIdWithinCompany} is the one every money path must use. Optimistic
 * locking is the wrong tool for a balance: two concurrent bookings would both read the
 * same balance, one would lose, and the caller would have to retry a payment. A short
 * {@code SELECT ... FOR UPDATE} serialises them instead, and the three-second timeout stops
 * a stuck transaction from queueing the whole branch behind it.
 */
public interface WalletRepository extends JpaRepository<Wallet, UUID>,
        JpaSpecificationExecutor<Wallet> {

    @Query("select w from Wallet w where w.id = :id and w.companyId = :companyId")
    Optional<Wallet> findByIdWithinCompany(@Param("id") UUID id, @Param("companyId") UUID companyId);

    @Query("select w from Wallet w where w.branchId = :branchId and w.companyId = :companyId")
    Optional<Wallet> findByBranchIdWithinCompany(@Param("branchId") UUID branchId,
                                                @Param("companyId") UUID companyId);

    /**
     * Loads the wallet for update, blocking any concurrent balance change on it.
     * Every credit, debit and recharge goes through this — never through the plain read.
     *
     * <p>No JPA lock-timeout hint: MySQL's dialect does not render one, so a hint here would
     * be quietly ignored and read as protection that is not there. The bound is the server's
     * {@code innodb_lock_wait_timeout} (50 s by default — worth lowering for this workload).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.branchId = :branchId and w.companyId = :companyId")
    Optional<Wallet> lockByBranchIdWithinCompany(@Param("branchId") UUID branchId,
                                                @Param("companyId") UUID companyId);

    @Query("select w from Wallet w where w.customerId = :customerId and w.companyId = :companyId")
    Optional<Wallet> findByCustomerIdWithinCompany(@Param("customerId") UUID customerId,
                                                   @Param("companyId") UUID companyId);

    /** The customer-wallet twin of {@link #lockByBranchIdWithinCompany}: every money path on a
     *  customer wallet loads it through here. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.customerId = :customerId and w.companyId = :companyId")
    Optional<Wallet> lockByCustomerIdWithinCompany(@Param("customerId") UUID customerId,
                                                   @Param("companyId") UUID companyId);

    Optional<Wallet> findByWalletNumber(String walletNumber);

    boolean existsByWalletNumber(String walletNumber);

    @Query("select w from Wallet w where w.companyId = :companyId and w.branchId in :branchIds")
    List<Wallet> findAllByBranchIdInWithinCompany(@Param("branchIds") java.util.Collection<UUID> branchIds,
                                                 @Param("companyId") UUID companyId);

    long countByCompanyId(UUID companyId);

    /** Backs the Company Overview "Total Wallet Balance" tile — spendable balance summed
     *  across every <em>branch</em> wallet in the company. Customer wallets are excluded: the
     *  tile is about the branch network, and a client's prepaid money is not branch float. */
    @Query("select coalesce(sum(w.availableBalance), 0) from Wallet w "
            + "where w.companyId = :companyId and w.ownerType = com.courier.modules.finance.domain.WalletOwnerType.BRANCH")
    java.math.BigDecimal sumAvailableBalanceByCompanyId(@Param("companyId") UUID companyId);

    /** Backs the Company Overview "Low Wallet Balance" action-required tile (branch wallets only). */
    @Query("select count(w) from Wallet w where w.companyId = :companyId and w.availableBalance < :threshold "
            + "and w.ownerType = com.courier.modules.finance.domain.WalletOwnerType.BRANCH")
    long countByCompanyIdAndAvailableBalanceLessThan(@Param("companyId") UUID companyId,
                                                     @Param("threshold") java.math.BigDecimal threshold);
}
