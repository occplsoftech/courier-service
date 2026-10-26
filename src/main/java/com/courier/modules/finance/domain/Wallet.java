package com.courier.modules.finance.domain;

import com.courier.shared.domain.CompanyOwnedEntity;
import com.courier.shared.exception.BusinessRuleException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The prepaid wallet of one branch. Exactly one per branch, created with the branch.
 *
 * <p><b>The balance is not a settable property.</b> There is no {@code setAvailableBalance}
 * and there never should be: the only ways the number moves are {@link #applyCredit} and
 * {@link #applyDebit}, and the service calls those only while writing the matching
 * {@link WalletTransaction} in the same transaction. That is what makes the ledger and the
 * balance agree — a wallet whose balance can be assigned has no auditable history.
 *
 * <p>Two balances, not one. {@code availableBalance} is spendable now;
 * {@code holdBalance} is money reserved against in-flight work (a booked shipment that has
 * not yet been charged). A hold is neither spendable nor lost, so it cannot live in the
 * available figure, and netting the two into a single number loses the distinction the
 * branch actually cares about.
 *
 * <p>Concurrency is pessimistic, not optimistic: two simultaneous bookings against the same
 * wallet must serialise, and a retry-on-conflict loop over money is a worse answer than a
 * short row lock. See {@code WalletRepository.lockByBranchId}.
 */
@Entity
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(
        name = "wallets",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_wallets_company_branch",
                        columnNames = {"company_id", "branch_id"}),
                @UniqueConstraint(name = "uk_wallets_company_customer",
                        columnNames = {"company_id", "customer_id"}),
                @UniqueConstraint(name = "uk_wallets_number",
                        columnNames = {"wallet_number"})
        },
        indexes = {
                @Index(name = "idx_wallets_branch", columnList = "branch_id"),
                @Index(name = "idx_wallets_customer", columnList = "customer_id"),
                @Index(name = "idx_wallets_company_status", columnList = "company_id, status")
        })
// Repeated deliberately: Hibernate does not inherit @Filter from a @MappedSuperclass.
@Filter(name = CompanyOwnedEntity.COMPANY_FILTER, condition = "company_id = :companyId")
@SQLRestriction("deleted = false")
public class Wallet extends CompanyOwnedEntity {

    /** Money scale across the whole module. Matches {@code DECIMAL(19,4)}. */
    public static final int MONEY_SCALE = 4;

    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(MONEY_SCALE);

    public static final String DEFAULT_CURRENCY = "INR";

    /**
     * Human-facing identifier, printed on statements and quoted in support tickets.
     * Immutable, and globally unique rather than per-company for the same reason
     * {@code users.username} is.
     */
    @Column(name = "wallet_number", nullable = false, updatable = false, length = 30)
    private String walletNumber;

    /** Who owns the wallet. Fixed for its life; see {@link WalletOwnerType}. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "owner_type", nullable = false, updatable = false, length = 10)
    @Builder.Default
    private WalletOwnerType ownerType = WalletOwnerType.BRANCH;

    /** The owning branch. One wallet per branch; never reassigned. Null on a customer wallet. */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "branch_id", columnDefinition = "BINARY(16)", updatable = false)
    private UUID branchId;

    /** The owning customer. One wallet per customer; never reassigned. Null on a branch wallet. */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "customer_id", columnDefinition = "BINARY(16)", updatable = false)
    private UUID customerId;

    @Setter
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private WalletStatus status = WalletStatus.ACTIVE;

    /** Spendable now. Moved only by {@link #applyCredit} / {@link #applyDebit}. */
    @Column(name = "available_balance", nullable = false, precision = 19, scale = MONEY_SCALE)
    @Builder.Default
    private BigDecimal availableBalance = ZERO;

    /** Reserved against in-flight work. Not spendable, not lost. */
    @Column(name = "hold_balance", nullable = false, precision = 19, scale = MONEY_SCALE)
    @Builder.Default
    private BigDecimal holdBalance = ZERO;

    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = DEFAULT_CURRENCY;

    // ---------------------------------------------------------------- behaviour

    /** Available plus held — what the branch owns, not what it can spend. */
    public BigDecimal getTotalBalance() {
        return normalise(availableBalance).add(normalise(holdBalance));
    }

    public boolean isActive() {
        return status != null && status.isOperational();
    }

    public boolean isCustomerWallet() {
        return ownerType == WalletOwnerType.CUSTOMER;
    }

    /**
     * A new, empty, active wallet for a customer. The one way to build a customer wallet, so
     * the "customer set, branch null" shape the database CHECK requires cannot be got wrong.
     */
    public static Wallet forCustomer(UUID customerId, String walletNumber) {
        if (customerId == null) {
            throw new BusinessRuleException("A customer wallet needs a customer.");
        }
        return Wallet.builder()
                .ownerType(WalletOwnerType.CUSTOMER)
                .customerId(customerId)
                .walletNumber(walletNumber)
                .status(WalletStatus.ACTIVE)
                .availableBalance(ZERO)
                .holdBalance(ZERO)
                .currency(DEFAULT_CURRENCY)
                .build();
    }

    /**
     * Adds to the available balance.
     *
     * @return the balance after the credit
     * @throws BusinessRuleException if the amount is not strictly positive, or the wallet
     *         is not operational
     */
    public BigDecimal applyCredit(BigDecimal amount) {
        requireOperational();
        BigDecimal value = requirePositive(amount);
        this.availableBalance = normalise(availableBalance).add(value);
        return this.availableBalance;
    }

    /**
     * Subtracts from the available balance.
     *
     * <p>A wallet is prepaid: there is no overdraft, so a debit larger than the available
     * balance is refused rather than allowed to go negative. Held money does not count
     * towards it — that is the point of holding it.
     *
     * @return the balance after the debit
     * @throws BusinessRuleException if the amount is not strictly positive, the wallet is
     *         not operational, or the balance is insufficient
     */
    public BigDecimal applyDebit(BigDecimal amount) {
        requireOperational();
        BigDecimal value = requirePositive(amount);
        BigDecimal current = normalise(availableBalance);
        if (current.compareTo(value) < 0) {
            throw new BusinessRuleException(
                    "Insufficient wallet balance. Available %s %s, required %s %s."
                            .formatted(currency, current.toPlainString(),
                                    currency, value.toPlainString()));
        }
        this.availableBalance = current.subtract(value);
        return this.availableBalance;
    }

    /**
     * Subtracts from the available balance without the insufficient-balance check —
     * for debits that record a branch's liability the instant it arises (e.g. TO_PAY
     * freight received at the delivery branch, owed to the company before the branch
     * has collected it from the consignee), not a spend against existing float. The
     * branch is expected to carry this as a negative balance until settled.
     *
     * @return the balance after the debit
     * @throws BusinessRuleException if the amount is not strictly positive, or the wallet
     *         is not operational
     */
    public BigDecimal applyDebitAllowingOverdraft(BigDecimal amount) {
        requireOperational();
        BigDecimal value = requirePositive(amount);
        this.availableBalance = normalise(availableBalance).subtract(value);
        return this.availableBalance;
    }

    /** Scale-normalised amount, so 100 and 100.0000 are the same money. */
    public static BigDecimal normalise(BigDecimal amount) {
        return amount == null ? ZERO : amount.setScale(MONEY_SCALE, java.math.RoundingMode.HALF_UP);
    }

    private void requireOperational() {
        if (!isActive()) {
            throw new BusinessRuleException(
                    "Wallet %s is %s and cannot accept transactions."
                            .formatted(walletNumber, status == null ? "in an unknown state"
                                    : status.name().toLowerCase()));
        }
    }

    private static BigDecimal requirePositive(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessRuleException("Transaction amount must be greater than zero.");
        }
        return normalise(amount);
    }
}
