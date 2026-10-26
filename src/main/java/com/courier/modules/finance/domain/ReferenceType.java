package com.courier.modules.finance.domain;

/**
 * What the {@code referenceId} on a ledger entry points at.
 *
 * <p>Deliberately a loose pairing rather than a real foreign key: the entry may reference
 * a gateway payment, a shipment, a settlement batch or nothing at all, and the ledger must
 * outlive whatever it references. The pair (type, id) is indexed so "what did shipment X
 * cost this branch?" is one lookup.
 */
public enum ReferenceType {

    /** A payment-gateway transaction — the recharge path. */
    PAYMENT,

    /** A shipment: booking charge, refund, COD. */
    SHIPMENT,

    /** A customer-portal booking (a courier booked from the e-commerce portal): its debit and refund. */
    PORTAL_BOOKING,

    /** A settlement or payout batch. */
    SETTLEMENT,

    /** Raised by the platform itself, with no user-facing document. */
    SYSTEM,

    /** A company admin's manual credit or debit. */
    MANUAL
}
