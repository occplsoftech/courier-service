package com.courier.modules.finance.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The User Performance Report's shapes. See {@link UserPerformanceService}. */
public final class UserPerformance {

    private UserPerformance() {
    }

    /**
     * Whose name a branch-wallet entry goes under.
     * <ul>
     *   <li>{@code WORK}: the user whose work caused it — a booking debit and its refund to the booker, a COD / To-Pay
     *       debit and DRS commission to the delivery boy on the DRS. Manual entries (manual debit, penalty, transfer,
     *       adjustment) belong to the branch, not to a user.</li>
     *   <li>{@code ENTERED_BY}: whoever was signed in when the entry was written.</li>
     * </ul>
     */
    public enum Attribution { WORK, ENTERED_BY }

    /** What a drill-down lists. */
    public enum Metric { BOOKINGS, COUNTER, DELIVERIES, WALLET, HANDOVERS }

    /**
     * One user's figures for the window. The same shape carries the branch total ({@code userId} null,
     * {@code system} false) and the row for work done without a signed-in user ({@code userId} null, {@code system}
     * true: customer portal / API bookings).
     *
     * @param counterCash        cash taken at the counter for Paid bookings (not cancelled)
     * @param counterOnline      UPI QR / Razorpay taken at the counter — goes to the bank, not into the user's hands
     * @param counterPending     Razorpay QR bookings not paid yet
     * @param deliveryCash       To-Pay freight and COD collected on delivery
     * @param cashCollected      counterCash + deliveryCash: what the user had in hand in the window
     * @param openingOutstanding cash not yet handed over at the start of the window
     * @param handedOver         approved handovers dated in the window
     * @param closingOutstanding cash not yet handed over (approved) at the end of it
     * @param awaitingApproval   handovers submitted with proof and not yet approved or rejected — still outstanding
     * @param successRate        delivered ÷ (delivered + still pending), percent; null with nothing assigned
     */
    public record Row(UUID userId, String userName, boolean system,
                      long booked, long cancelled, BigDecimal bookingValue, BigDecimal bookedWeight,
                      long paidCount, long toPayCount, long codCount, long creditCount,
                      BigDecimal counterCash, BigDecimal counterOnline, BigDecimal counterPending,
                      long delivered, long deliveryPending, long failedAttempts, BigDecimal deliveryCash,
                      BigDecimal successRate,
                      BigDecimal walletDebit, BigDecimal walletRefund, BigDecimal walletNet, BigDecimal commission,
                      BigDecimal cashCollected, BigDecimal openingOutstanding, BigDecimal handedOver,
                      BigDecimal closingOutstanding, BigDecimal awaitingApproval) {
    }

    /**
     * How the users' wallet figures add up to the branch wallet. Null for a caller who sees only their own row.
     *
     * @param walletDebits       every debit on the branch wallet in the window
     * @param walletCredits      every credit (recharges, refunds, commissions…)
     * @param attributedDebits   the part of {@code walletDebits} under a user's name
     * @param unattributedDebits the rest: manual debits, penalties, transfers, entries without a user
     */
    public record Reconciliation(BigDecimal walletDebits, BigDecimal walletCredits,
                                 BigDecimal attributedDebits, BigDecimal unattributedDebits) {
    }

    /**
     * @param trackingFrom the day outstanding cash starts counting (see V191); collections before it are never
     *                     outstanding
     * @param teamView     false when the caller sees only their own row
     * @param canApproveHandover whether the caller approves / rejects others' handovers (any company user may submit
     *                     their own)
     */
    public record Report(UUID branchId, LocalDate from, LocalDate to, Attribution attribution,
                         LocalDate trackingFrom, boolean teamView, boolean canApproveHandover,
                         List<Row> rows, Row total, Reconciliation reconciliation) {
    }

    /**
     * One line of a drill-down.
     *
     * @param reference shipment number, wallet transaction number or handover id
     * @param type      payment kind, counter method, DR/CR or handover mode
     * @param detail    free text: payment mode, DRS number, sub-transaction label…
     * @param status    shipment / payment / handover status
     */
    public record DetailRow(LocalDate day, Instant at, String reference, String type, String detail,
                            String status, BigDecimal amount) {
    }
}
