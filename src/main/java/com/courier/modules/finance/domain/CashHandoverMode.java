package com.courier.modules.finance.domain;

/** How a user handed their collected cash over to the branch. */
public enum CashHandoverMode {
    /** Notes counted into the manager's / accounts desk's hands. */
    CASH,
    /** Deposited into the company's bank account; the deposit slip number goes in the reference. */
    BANK_DEPOSIT,
    /** Sent by UPI to the company's account; the UTR goes in the reference. */
    UPI
}
