package com.courier.modules.finance.application.command;

import java.util.UUID;

/**
 * Credits back a portal booking's debit — the booking was refused by the courier, or the courier
 * cancelled the shipment. The wallet is found from the ledger entry itself, so the caller names
 * only the booking; one that was never debited, or was already refunded, refunds nothing.
 *
 * @param bookingId the portal booking ({@code referenceId} to reverse)
 * @param remarks   shown on the refund entry's statement line
 */
public record CustomerBookingRefundCommand(
        UUID bookingId,
        String remarks
) {
}
