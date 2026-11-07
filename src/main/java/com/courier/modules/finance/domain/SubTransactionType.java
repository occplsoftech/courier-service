package com.courier.modules.finance.domain;

import com.courier.shared.exception.BusinessRuleException;

import java.util.Arrays;
import java.util.List;

/**
 * Why a wallet entry happened.
 *
 * <p>Each constant carries the direction it is allowed to appear in, so a caller cannot
 * file a "shipment booking" as a credit or a "wallet recharge" as a debit. A handful are
 * genuinely {@link Direction#BOTH} — a settlement or an adjustment can legitimately move
 * money either way — and those are the only ones where the caller chooses.
 *
 * <p>The stored form is the three-letter code (the enum name). It is part of the wire
 * contract and of every exported statement, so a constant is never renamed or reused.
 */
public enum SubTransactionType {

    WRC(Direction.CREDIT, "Wallet Recharge"),
    SBK(Direction.DEBIT, "Shipment Booking"),
    SRF(Direction.CREDIT, "Shipment Refund"),
    COD(Direction.BOTH, "COD Settlement"),
    TPY(Direction.DEBIT, "TO_PAY Received At Branch"),
    COM(Direction.BOTH, "Commission"),
    BST(Direction.BOTH, "Branch Settlement"),
    MCR(Direction.CREDIT, "Manual Credit"),
    MDB(Direction.DEBIT, "Manual Debit"),
    TRI(Direction.CREDIT, "Transfer In"),
    TRO(Direction.DEBIT, "Transfer Out"),
    ADJ(Direction.BOTH, "Adjustment"),
    PNL(Direction.DEBIT, "Penalty"),
    DRS(Direction.CREDIT, "DRS Commission"),
    DWC(Direction.CREDIT, "Delivery Weight Commission"),
    /** A customer's return-to-sender charge (Phase 12.1); may take the wallet below zero, like TPY. */
    RTO(Direction.DEBIT, "RTO Charge");

    /** Which {@link TransactionType} a reason may be filed under. */
    public enum Direction {
        CREDIT, DEBIT, BOTH
    }

    private final Direction direction;
    private final String label;

    SubTransactionType(Direction direction, String label) {
        this.direction = direction;
        this.label = label;
    }

    public Direction getDirection() {
        return direction;
    }

    public String getLabel() {
        return label;
    }

    public boolean supports(TransactionType type) {
        return switch (direction) {
            case BOTH -> true;
            case CREDIT -> type == TransactionType.CR;
            case DEBIT -> type == TransactionType.DR;
        };
    }

    /** Reasons that may be used on a credit — what a manual-credit dropdown offers. */
    public static List<SubTransactionType> creditable() {
        return Arrays.stream(values()).filter(s -> s.supports(TransactionType.CR)).toList();
    }

    /** Reasons that may be used on a debit. */
    public static List<SubTransactionType> debitable() {
        return Arrays.stream(values()).filter(s -> s.supports(TransactionType.DR)).toList();
    }

    /**
     * @throws BusinessRuleException (422) if the reason cannot be filed under this
     *         direction — e.g. a shipment booking recorded as a credit
     */
    public void requireSupports(TransactionType type) {
        if (!supports(type)) {
            throw new BusinessRuleException(
                    "'%s' (%s) cannot be recorded as a %s."
                            .formatted(name(), label, type.getLabel().toLowerCase()));
        }
    }
}
