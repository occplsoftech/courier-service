package com.courier.modules.finance.api;

import com.courier.modules.finance.api.dto.CreditRequest;
import com.courier.modules.finance.api.dto.DebitRequest;
import com.courier.modules.finance.api.dto.RechargeOrderResponse;
import com.courier.modules.finance.api.dto.RechargeRequest;
import com.courier.modules.finance.api.dto.WalletResponse;
import com.courier.modules.finance.api.dto.WalletSummaryResponse;
import com.courier.modules.finance.api.dto.WalletTransactionResponse;
import com.courier.modules.finance.api.dto.WalletTransactionSearchRequest;
import com.courier.modules.finance.application.WalletService;
import com.courier.modules.finance.application.command.CreditCommand;
import com.courier.modules.finance.application.command.DebitCommand;
import com.courier.modules.finance.application.command.RechargeCommand;
import com.courier.modules.finance.application.payment.PaymentGatewayPort;
import com.courier.modules.finance.domain.BranchDirectoryPort;
import com.courier.modules.finance.domain.Wallet;
import com.courier.modules.finance.domain.WalletRepository;
import com.courier.modules.finance.domain.WalletTransaction;
import com.courier.modules.finance.domain.WalletTransactionCriteria;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/** Wire contract ↔ application/domain types for branch wallets. */
@Component
@RequiredArgsConstructor
public class WalletMapper {

    private final BranchDirectoryPort branchDirectory;
    private final WalletRepository walletRepository;

    // ------------------------------------------------------------------ inbound

    public RechargeCommand toCommand(RechargeRequest r) {
        return new RechargeCommand(r.branchId(), r.amount(), r.gatewayOrderId(),
                r.paymentReference(), r.signature(), r.remarks());
    }

    public CreditCommand toCommand(CreditRequest r) {
        return new CreditCommand(r.branchId(), r.amount(), r.subTransactionType(),
                r.referenceType(), r.referenceId(), r.remarks());
    }

    public DebitCommand toCommand(DebitRequest r) {
        return new DebitCommand(r.branchId(), r.amount(), r.subTransactionType(),
                r.referenceType(), r.referenceId(), r.remarks());
    }

    /**
     * Dates in, instants out. {@code toDate} becomes the start of the <em>next</em> day so
     * the specification's exclusive upper bound still includes the whole day the caller
     * asked for — the alternative silently drops the last day of every statement.
     */
    public WalletTransactionCriteria toCriteria(WalletTransactionSearchRequest r) {
        WalletTransactionSearchRequest safe =
                r == null ? WalletTransactionSearchRequest.empty() : r;
        return new WalletTransactionCriteria(
                null, null,
                safe.transactionType(), safe.subTransactionType(),
                safe.referenceType(),
                safe.referenceId(), safe.transactionNo(), safe.paymentReference(),
                startOfDay(safe.fromDate()), startOfNextDay(safe.toDate()),
                safe.minAmount(), safe.maxAmount(), safe.search());
    }

    // ----------------------------------------------------------------- outbound

    public com.courier.modules.finance.api.dto.RechargeAttemptResponse toAttemptResponse(
            com.courier.modules.finance.domain.RechargeOrder o) {
        BranchDirectoryPort.BranchRef branch = o.getBranchId() == null ? null
                : branchDirectory.findBranch(o.getBranchId(), o.getCompanyId()).orElse(null);
        return new com.courier.modules.finance.api.dto.RechargeAttemptResponse(
                o.getId(), o.getTransactionNo(), o.getBranchId(), o.getCustomerId(),
                branch == null ? null : branch.branchCode(),
                branch == null ? null : branch.branchName(),
                o.getAmount(), o.getCurrency(), o.getMode().name(), o.getGateway(), o.getGatewayOrderId(),
                o.getGatewayPaymentId(), o.getStatus().name(), o.getFailureReason(), o.getRemarks(),
                o.getCreatedBy() == null ? null
                        : branchDirectory.findUser(o.getCreatedBy(), o.getCompanyId())
                                .map(BranchDirectoryPort.UserRef::displayName).orElse(null),
                o.getLedgerTransactionId(), o.getLastCheckedAt(), o.getSettledAt(),
                bankSettlementStatus(o), o.getBankSettlementId(), o.getBankSettlementUtr(),
                o.getBankSettledAt(), o.getSettlementLastCheckedAt(), o.getCreatedAt());
    }

    /** Only a credited online recharge can be settled by the gateway; everything else has no payout to track. */
    private static String bankSettlementStatus(com.courier.modules.finance.domain.RechargeOrder o) {
        if (o.getMode() != com.courier.modules.finance.domain.RechargeMode.ONLINE
                || o.getStatus() != com.courier.modules.finance.domain.PaymentStatus.SUCCESS) {
            return null;
        }
        return o.isBankSettled() ? "SETTLED" : "AWAITING";
    }

    public WalletResponse toResponse(Wallet w) {
        BranchDirectoryPort.BranchRef branch = w.getBranchId() == null ? null
                : branchDirectory.findBranch(w.getBranchId(), w.getCompanyId()).orElse(null);
        return new WalletResponse(
                w.getId(), w.getCompanyId(), w.getWalletNumber(), w.getBranchId(),
                branch == null ? null : branch.branchCode(),
                branch == null ? null : branch.branchName(),
                w.getStatus(),
                w.getAvailableBalance(), w.getHoldBalance(), w.getTotalBalance(), w.getCurrency(),
                w.getCreatedBy(), w.getCreatedAt(), w.getUpdatedBy(), w.getUpdatedAt(), w.getVersion());
    }

    public WalletSummaryResponse toResponse(WalletService.WalletSummary s) {
        Wallet w = s.wallet();
        return new WalletSummaryResponse(
                w.getId(), w.getWalletNumber(), w.getBranchId(), s.branchCode(), s.branchName(),
                w.getStatus(), w.getCurrency(),
                w.getAvailableBalance(), w.getHoldBalance(), w.getTotalBalance(),
                s.todayCredit(), s.todayDebit(), s.monthCredit(), s.monthDebit(),
                s.totalCredit(), s.totalDebit(),
                s.todayCreditCommission(), s.bookingPendingCommission(), s.deliveryPendingCommission(),
                s.transactionCount(), s.lastTransactionAt(),
                s.lastRechargeAmount(), s.lastRechargeAt());
    }

    public WalletTransactionResponse toResponse(WalletTransaction t) {
        return toResponse(t, null, null, null);
    }

    /**
     * The recharge report's row: the same statement entry, enriched with the branch its
     * wallet belongs to — needed only here, since a single branch's own statement already
     * knows whose it is. One extra wallet lookup and one branch lookup per row; the report
     * is an admin read capped at 100 rows a page, not a hot path.
     */
    public WalletTransactionResponse toRechargeReportRow(WalletTransaction t) {
        Wallet wallet = walletRepository.findByIdWithinCompany(t.getWalletId(), t.getCompanyId())
                .orElse(null);
        BranchDirectoryPort.BranchRef branch = wallet == null || wallet.getBranchId() == null ? null
                : branchDirectory.findBranch(wallet.getBranchId(), t.getCompanyId()).orElse(null);
        return toResponse(t, wallet == null ? null : wallet.getBranchId(),
                branch == null ? null : branch.branchCode(),
                branch == null ? null : branch.branchName());
    }

    private WalletTransactionResponse toResponse(WalletTransaction t, UUID branchId,
                                                  String branchCode, String branchName) {
        String createdByName = branchDirectory.findUser(t.getCreatedBy(), t.getCompanyId())
                .map(BranchDirectoryPort.UserRef::displayName).orElse(null);
        return new WalletTransactionResponse(
                t.getId(), t.getCompanyId(), t.getWalletId(), branchId, branchCode, branchName,
                t.getTransactionNo(),
                t.getTransactionType(),
                t.getTransactionType() == null ? null : t.getTransactionType().getLabel(),
                t.getSubTransactionType(),
                t.getSubTransactionType() == null ? null : t.getSubTransactionType().getLabel(),
                t.getAmount(), t.getBalanceBefore(), t.getBalanceAfter(),
                t.getReferenceType(), t.getReferenceId(), t.getRemarks(),
                t.getPaymentGateway(), t.getPaymentReference(),
                t.getCreatedBy(), createdByName, t.getCreatedAt());
    }

    public RechargeOrderResponse toResponse(PaymentGatewayPort.GatewayOrder order,
                                            UUID walletId, String walletNumber, UUID branchId) {
        return new RechargeOrderResponse(walletId, walletNumber, branchId,
                order.gateway(), order.orderId(), order.amountMinor(), order.currency(),
                order.publicKey(), order.receipt());
    }

    // ------------------------------------------------------------------ helpers

    private static Instant startOfDay(LocalDate date) {
        return date == null ? null : date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Instant startOfNextDay(LocalDate date) {
        return date == null ? null : date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
