package com.courier.modules.finance.application;

import com.courier.modules.finance.application.command.BookingDebitCommand;
import com.courier.modules.finance.application.command.CodDeliveryDebitCommand;
import com.courier.modules.finance.application.command.CommissionCreditCommand;
import com.courier.modules.finance.application.command.CreditCommand;
import com.courier.modules.finance.application.command.CustomerBookingDebitCommand;
import com.courier.modules.finance.application.command.CustomerBookingRefundCommand;
import com.courier.modules.finance.application.command.CustomerCreditCommand;
import com.courier.modules.finance.application.command.CustomerDebitCommand;
import com.courier.modules.finance.application.command.CustomerRechargeOrderCommand;
import com.courier.modules.finance.application.command.DebitCommand;
import com.courier.modules.finance.application.command.DeliveryWeightCommissionCreditCommand;
import com.courier.modules.finance.application.command.DrsChargeCreditCommand;
import com.courier.modules.finance.application.command.RechargeCommand;
import com.courier.modules.finance.application.command.ShipmentReversalCommand;
import com.courier.modules.finance.application.payment.PaymentGatewayPort;
import com.courier.modules.finance.domain.Wallet;
import com.courier.modules.finance.domain.WalletTransaction;
import com.courier.modules.finance.domain.WalletTransactionCriteria;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Branch wallet use cases.
 *
 * <p>Every method that moves money takes the branch, not the wallet: a branch has exactly
 * one wallet, so an API that took a wallet id would only invite a caller to guess one.
 * A null {@code branchId} means "the caller's own branch", which is the only thing a branch
 * user can mean; a company admin must name one.
 *
 * <p><b>There is no method that sets a balance.</b> That is the module's central rule and
 * it is expressed by this interface's shape, not only by documentation.
 */
public interface WalletService {

    /**
     * Creates the wallet for a branch if it does not have one yet, and returns it either
     * way. Idempotent.
     *
     * <p>Called from two places: the {@code BranchCreated} listener, so a new branch has a
     * wallet immediately, and every read path, so branches that predate this module acquire
     * one on first access instead of 404ing forever.
     */
    Wallet getOrCreateForBranch(UUID branchId);

    /** The wallet the caller may see. Read-only; does not create. */
    Wallet getForBranch(UUID branchId);

    /** Balances plus the derived figures a dashboard shows. */
    WalletSummary summarise(UUID branchId);

    /** One summary per branch of the caller's company — the Finance Report's company-wide
     *  table. `COMPANY_ADMIN`/`FINANCE_USER` only, since every other wallet read is scoped
     *  to a single branch. */
    List<WalletSummary> companySummary();

    /** The statement, paged and filtered. Criteria are pinned to the resolved wallet. */
    Page<WalletTransaction> searchTransactions(UUID branchId, WalletTransactionCriteria criteria,
                                               Pageable pageable);

    /**
     * Step one of a recharge: fixes the amount at the gateway and returns an order for the
     * browser. Credits nothing.
     */
    PaymentGatewayPort.GatewayOrder openRecharge(RechargeCommand command);

    /**
     * Step two: verifies the gateway's confirmation, asks the gateway what was actually
     * paid, and credits that. Idempotent on the gateway payment id — a client that retries,
     * or a webhook that arrives twice, credits the wallet once.
     */
    WalletTransaction completeRecharge(RechargeCommand command);

    /**
     * Webhook path: the gateway says a payment on this order failed. Marks the recharge
     * attempt {@code FAILED} (never downgrades a {@code SUCCESS}). No authenticated user —
     * same caller contract as {@link #settleFromWebhook}.
     */
    /**
     * Report of online recharge attempts — {@code PENDING}, {@code SUCCESS} and {@code FAILED}
     * (the recharge report itself lists only the credited ones). Company admins and finance
     * users see every branch (optionally one via {@code branchId}); anyone else sees only
     * their own branch.
     */
    org.springframework.data.domain.Page<com.courier.modules.finance.domain.RechargeOrder> searchRechargeAttempts(
            UUID branchId, com.courier.modules.finance.domain.PaymentStatus status,
            com.courier.modules.finance.domain.RechargeMode mode,
            java.time.LocalDate fromDate, java.time.LocalDate toDate, String search,
            org.springframework.data.domain.Pageable pageable);

    /**
     * The "Check status" button on a {@code PENDING} attempt: runs the same gateway check the
     * scheduler would, right now, and returns the attempt as it stands afterwards. Company
     * admins/finance users may check any branch's; anyone else only their own.
     */
    com.courier.modules.finance.domain.RechargeOrder checkRechargeAttempt(UUID rechargeOrderId);

    void markRechargeFailed(UUID companyId, String gatewayOrderId, String reason);

    /**
     * Scheduler path: asks the gateway what happened to a still-{@code PENDING} recharge and
     * settles it (credit + {@code SUCCESS}), fails it, or leaves it for the next run. Called
     * only by {@code RechargeReconciliationJob}, never from a request.
     */
    void reconcileRecharge(UUID rechargeOrderId);


    /**
     * Same settlement as {@link #completeRecharge}, reached from a Razorpay webhook instead
     * of the browser — covers a tab closed after the gateway captured the payment but before
     * the browser confirmed it. No {@code @PreAuthorize}: a webhook carries no authenticated
     * user, so the caller ({@code RazorpayWebhookController}) must already have verified the
     * webhook signature and resolved {@code companyId}/{@code branchId} from the order's own
     * notes before calling this — never from anything the webhook body claims about itself.
     * Idempotent on the gateway payment id, same as {@link #completeRecharge}.
     */
    WalletTransaction settleFromWebhook(UUID companyId, UUID branchId, String gatewayOrderId,
                                        String paymentId);

    // ------------------------------------------------------- customer wallets (money in)

    /**
     * Step one of an online recharge of the signed-in customer's own wallet. The customer comes
     * from the portal's authenticated account, never from the request. Same rules as
     * {@link #openRecharge}: the amount is fixed at the gateway, and nothing is credited here.
     * {@code CUSTOMER} accounts only.
     */
    PaymentGatewayPort.GatewayOrder openRechargeForCustomer(CustomerRechargeOrderCommand command);

    /**
     * Step two: settles the payment checkout produced, for a customer. The signature is verified
     * and the gateway is asked what was actually paid; that figure is credited. Idempotent on
     * the payment reference. {@code CUSTOMER} accounts only.
     */
    WalletTransaction completeRechargeForCustomer(UUID customerId, String gatewayOrderId,
                                                  String paymentReference, String signature);

    /** {@link #settleFromWebhook} for a customer's recharge; same caller contract (no user, the
     *  webhook controller has already verified the payload signature). */
    WalletTransaction settleFromWebhookForCustomer(UUID companyId, UUID customerId, String gatewayOrderId,
                                                   String paymentId);

    /**
     * A company admin credits a customer's wallet by hand (a bank transfer received, a goodwill
     * credit). Writes the ledger entry and raises the balance in one transaction. The reason
     * defaults to {@code MCR} and must be creditable. {@code COMPANY_ADMIN} only; a customer of
     * another company is 404.
     */
    WalletTransaction creditForCustomer(CustomerCreditCommand command);

    // -------------------------------------------------------- customer wallets (money out)

    /**
     * Pays for a portal booking from the signed-in customer's wallet ({@code SBK}, referenced to the
     * booking). Refused if the balance is short. Idempotent: a booking already debited returns its
     * entry instead of debiting twice. {@code CUSTOMER} accounts only.
     */
    WalletTransaction debitForCustomerBooking(CustomerBookingDebitCommand command);

    /**
     * Credits back ({@code SRF}) a portal booking's debit that has not been refunded yet. Empty when the
     * booking was never debited or already refunded, so it is safe to call twice. No user is required:
     * the tracking-sync job calls it for a courier-cancelled shipment. It reverses only an entry the
     * booking itself wrote, never a caller-chosen amount.
     */
    List<WalletTransaction> refundCustomerBooking(CustomerBookingRefundCommand command);

    /**
     * A company admin debits a customer's wallet by hand. Writes the ledger entry and lowers the balance
     * in one transaction; refused if the balance is short. The reason defaults to {@code MDB} and must be
     * debitable. {@code COMPANY_ADMIN} only; a customer of another company is 404.
     */
    WalletTransaction debitForCustomer(CustomerDebitCommand command);

    /** Manual credit by a company admin. */
    WalletTransaction credit(CreditCommand command);

    /** Manual debit by a company admin. Refused if the balance is insufficient. */
    WalletTransaction debit(DebitCommand command);

    /**
     * Debits the booking branch's wallet for a PREPAID shipment ({@code SBK}, referencing
     * the shipment number). Unlike {@link #debit}, this is <b>not</b> restricted to
     * {@code COMPANY_ADMIN} — Shipment Booking has already decided who may book through
     * that branch, and this seam only moves the money that decision earns; it still
     * refuses an out-of-scope branch and an insufficient balance exactly as {@link #debit}
     * does. Deliberately not built ahead of its consumer — see
     * {@code MEMORY/modules/branch-wallet.md}'s "Booking debit seam".
     *
     * @param command branch, amount, and the shipment the debit answers to
     * @throws com.courier.shared.exception.BusinessRuleException insufficient balance, a
     *         non-ACTIVE wallet, or a non-positive amount
     * @throws com.courier.shared.exception.ForbiddenException the branch is not the
     *         caller's own and the caller is not a company admin
     */
    WalletTransaction debitForBooking(BookingDebitCommand command);

    /**
     * Debits the delivery branch's wallet for a shipment collected at delivery
     * ({@code COD}, referencing the shipment number) — the delivery-side mirror of
     * {@link #debitForBooking}. Same non-{@code COMPANY_ADMIN}-only shape: Shipment
     * Movement has already decided who may deliver through that branch, and this seam
     * only moves the money that decision earns.
     *
     * @param command branch, amount, and the shipment the debit answers to
     * @throws com.courier.shared.exception.BusinessRuleException insufficient balance, a
     *         non-ACTIVE wallet, or a non-positive amount
     * @throws com.courier.shared.exception.ForbiddenException the branch is not the
     *         caller's own and the caller is not a company admin
     */
    WalletTransaction debitForCodDelivery(CodDeliveryDebitCommand command);

    /**
     * Debits the delivery branch's wallet for a {@code TO_PAY} shipment the moment it is
     * in-scanned off its incoming Trip Hire Challan at its own final delivery branch
     * ({@code TPY}, referencing the shipment number) — not a crossing hub's in-scan, and not
     * {@link #debitForCodDelivery}'s later, actual-delivery trigger. The branch is liable
     * for the freight the instant the shipment is physically on its premises, ahead of the
     * consignee actually paying cash at the door; {@code COD} still waits for
     * {@link #debitForCodDelivery} since that amount is the consignee's, not the freight, and
     * is only real once collected. Same non-{@code COMPANY_ADMIN}-only shape as
     * {@link #debitForCodDelivery}.
     *
     * @param command branch, amount, and the shipment the debit answers to
     * @throws com.courier.shared.exception.BusinessRuleException insufficient balance, a
     *         non-ACTIVE wallet, or a non-positive amount
     * @throws com.courier.shared.exception.ForbiddenException the branch is not the
     *         caller's own and the caller is not a company admin
     */
    WalletTransaction debitForToPayReceivedAtBranch(CodDeliveryDebitCommand command);

    /**
     * Credits the delivery branch's wallet with DRS commission on a delivered shipment
     * ({@code DRS}, referencing the shipment number) — {@code drsCharge = branch's own
     * drsChargePerQty * item quantity}, published from {@code ShipmentServiceImpl.deliver}
     * for every delivery, not only collect-at-delivery ones. Same non-{@code
     * COMPANY_ADMIN}-only shape as {@link #debitForCodDelivery}.
     *
     * @param command branch, amount, and the shipment the credit answers to
     * @throws com.courier.shared.exception.BusinessRuleException a non-ACTIVE wallet or a
     *         non-positive amount
     * @throws com.courier.shared.exception.ForbiddenException the branch is not the
     *         caller's own and the caller is not a company admin
     */
    WalletTransaction creditForDrsCharge(DrsChargeCreditCommand command);

    /**
     * Credits the delivery branch's wallet with weight-based delivery commission on a
     * delivered shipment ({@code DWC}, referencing the shipment number) — {@code amount =
     * branch's own deliveryCommissionRatePerKg * max(shipment's chargeable weight,
     * branch's deliveryCommissionMinWeightKg)}, published from {@code
     * ShipmentServiceImpl.deliver} for every delivery, independent of and in addition to
     * {@link #creditForDrsCharge}. Same non-{@code COMPANY_ADMIN}-only shape as
     * {@link #debitForCodDelivery}.
     *
     * @param command branch, amount, and the shipment the credit answers to
     * @throws com.courier.shared.exception.BusinessRuleException a non-ACTIVE wallet or a
     *         non-positive amount
     * @throws com.courier.shared.exception.ForbiddenException the branch is not the
     *         caller's own and the caller is not a company admin
     */
    WalletTransaction creditForDeliveryWeightCommission(DeliveryWeightCommissionCreditCommand command);

    /**
     * Credits the booking branch's wallet with its commission share of a PREPAID shipment
     * ({@code COM}, referencing the shipment number) — posted right after
     * {@link #debitForBooking} settles, only when the branch has {@code instantCommission}
     * on. Same non-{@code COMPANY_ADMIN}-only shape as {@link #debitForBooking}.
     *
     * @param command branch, amount, and the shipment the credit answers to
     * @throws com.courier.shared.exception.BusinessRuleException a non-ACTIVE wallet or a
     *         non-positive amount
     * @throws com.courier.shared.exception.ForbiddenException the branch is not the
     *         caller's own and the caller is not a company admin
     */
    WalletTransaction creditCommission(CommissionCreditCommand command);

    /**
     * Undoes every settled wallet entry filed against a cancelled shipment — the cancel
     * seam. Walks the ledger by {@code (SHIPMENT, shipmentNumber)}, and for each entry not
     * itself already a reversal, posts the opposite move on that <em>same</em> entry's own
     * wallet: a debit ({@code SBK}, freight) is credited back ({@code SRF}), a credit
     * ({@code COM}/{@code DRS}/{@code DWC}, commission) is debited back ({@code ADJ}). Each
     * entry's wallet is resolved from the ledger row itself, not a caller-supplied branch,
     * because a shipment's freight debit and its commission credit can sit on different
     * branches.
     *
     * <p>One entry's failure (an INACTIVE wallet, a commission already spent past what the
     * branch can now give back) does not block the rest — logged for manual reconciliation,
     * the same accepted-gap shape {@link #debitForBooking} documents, rather than leaving a
     * cancelled shipment's other, reversible entries un-reversed too.
     *
     * @param command the shipment being cancelled, and the remarks each reversal carries
     * @return the reversal entries actually posted, in the order their originals were found
     */
    List<WalletTransaction> reverseForShipment(ShipmentReversalCommand command);

    /**
     * Credits back ({@code SRF}) every booking debit ({@code SBK}) on this shipment that has
     * not already been refunded — the PAID -> TO_PAY switch seam. Commission entries are
     * left alone. Returns the credits posted; empty when the shipment was never debited.
     */
    List<WalletTransaction> refundBookingDebit(ShipmentReversalCommand command);

    /**
     * Derived view of a wallet, assembled for the summary endpoint.
     *
     * @param branchCode      / {@code branchName} — the labels a statement needs, resolved
     *                        through the branch directory
     * @param todayCredit     settled credits since midnight UTC
     * @param todayDebit      settled debits since midnight UTC
     * @param monthCredit     settled credits this calendar month (UTC)
     * @param monthDebit      settled debits this calendar month (UTC)
     * @param totalCredit     settled credits over the wallet's life
     * @param totalDebit      settled debits over the wallet's life
     * @param todayCreditCommission settled {@code COM}/{@code DRS} credits since midnight
     *                        UTC — the commission slice of {@code todayCredit}, not a
     *                        separate pool
     * @param bookingPendingCommission commission still short of its dispatch trigger —
     *                        collect-at-booking shipments not yet {@code DISPATCHED}; see
     *                        {@code PendingCommissionPort}
     * @param deliveryPendingCommission commission still short of its delivery trigger —
     *                        collect-at-delivery (TO_PAY/COD) shipments not yet {@code
     *                        DELIVERED}
     * @param transactionCount entries in the ledger
     * @param lastTransactionAt when the ledger last moved, or null for an untouched wallet
     * @param lastRechargeAmount / {@code lastRechargeAt} — the most recent settled recharge
     */
    record WalletSummary(
            Wallet wallet,
            String branchCode,
            String branchName,
            BigDecimal todayCredit,
            BigDecimal todayDebit,
            BigDecimal monthCredit,
            BigDecimal monthDebit,
            BigDecimal totalCredit,
            BigDecimal totalDebit,
            BigDecimal todayCreditCommission,
            BigDecimal bookingPendingCommission,
            BigDecimal deliveryPendingCommission,
            long transactionCount,
            Instant lastTransactionAt,
            BigDecimal lastRechargeAmount,
            Instant lastRechargeAt
    ) {
    }
}
