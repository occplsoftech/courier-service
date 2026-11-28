package com.courier.modules.finance.domain;

/**
 * Where a cash handover stands. Only {@link #APPROVED} reduces the user's outstanding cash.
 * <pre>PENDING --approve--> APPROVED --void--> VOIDED
 *    \------reject---> REJECTED</pre>
 */
public enum CashHandoverStatus {
    /** Submitted with proof by the user handing over; waiting for the branch manager / accounts desk. */
    PENDING,
    /** The receiver saw the proof and confirmed the money arrived. */
    APPROVED,
    /** The receiver refused it (wrong amount, unreadable proof…); the user submits a new one. */
    REJECTED,
    /** Approved, then found to be wrong; counts as outstanding again. */
    VOIDED
}
