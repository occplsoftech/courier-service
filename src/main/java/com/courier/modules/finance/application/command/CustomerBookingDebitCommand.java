package com.courier.modules.finance.application.command;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The customer's wallet debit for a portal booking: reason ({@code SBK}) and reference
 * ({@code PORTAL_BOOKING}) are fixed by the wallet service, not chosen by the caller.
 *
 * @param customerId the signed-in customer paying for the booking
 * @param bookingId  the portal booking this debit answers to ({@code referenceId}); one debit per booking
 * @param amount     strictly positive, the frozen quote option's total
 * @param remarks    shown on the statement
 */
public record CustomerBookingDebitCommand(
        UUID customerId,
        UUID bookingId,
        BigDecimal amount,
        String remarks
) {
}
