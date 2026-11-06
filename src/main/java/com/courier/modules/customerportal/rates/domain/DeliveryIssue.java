package com.courier.modules.customerportal.rates.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A failed delivery attempt on a booking, as the customer sees it: why, how many, and what they chose. {@code open} while it waits
 * for them (or for the branch); {@code choice} is REATTEMPT, CHANGE_ADDRESS or RETURN, null until they choose.
 */
public record DeliveryIssue(int attempts, String reason, String note, Instant at, boolean open, boolean returnFlagged,
                            String choice, String choiceLabel, LocalDate choiceDate, String receiverAddress, String receiverPhone) {
}
