package com.courier.modules.finance.application;

import com.courier.modules.finance.domain.BranchUserActivityPort.ShipmentOwners;
import com.courier.modules.finance.domain.ReferenceType;
import com.courier.modules.finance.domain.SubTransactionType;
import com.courier.modules.finance.domain.WalletTransaction;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Puts each branch-wallet entry under a user's name for the User Performance Report. Pure: no repository, no clock.
 *
 * <p><b>Work-based</b> (default): a booking debit ({@code SBK}) and its refund ({@code SRF}) go to whoever booked the
 * shipment; a COD / To-Pay debit ({@code COD}, {@code TPY}) and the DRS commissions ({@code DRS}, {@code DWC}) go to the
 * delivery boy on the shipment's DRS (whoever entered it, when the shipment never went on a DRS); booking-vs-delivery
 * commission ({@code COM}) goes to the booker when this is the booking branch's wallet and to the delivery boy
 * otherwise; an RTO charge to whoever entered it. Manual entries — manual debit/credit, penalty, transfer, adjustment,
 * settlement, recharge — are the branch's, not a user's.
 *
 * <p><b>Entered-by</b>: whoever was signed in when the entry was written ({@code created_by}).
 */
final class WalletAttribution {

    /** Which column an entry counts in. {@code NONE}: a credit that is no user's figure (recharge, manual credit…). */
    enum Bucket { DEBIT, REFUND, COMMISSION, NONE }

    /**
     * @param userId null = not under any user's name (an unattributed debit when {@code bucket} is DEBIT)
     * @param amount signed for COMMISSION (a reversed commission is negative); positive otherwise
     */
    record Share(UUID userId, Bucket bucket, BigDecimal amount) {
    }

    private WalletAttribution() {
    }

    static Share of(WalletTransaction entry, UUID walletBranchId, Map<String, ShipmentOwners> owners,
                    UserPerformance.Attribution attribution) {
        SubTransactionType sub = entry.getSubTransactionType();
        boolean credit = entry.getTransactionType().isCredit();
        BigDecimal amount = entry.getAmount();

        Bucket bucket;
        BigDecimal signed = amount;
        if (sub == SubTransactionType.COM || sub == SubTransactionType.DRS || sub == SubTransactionType.DWC) {
            bucket = Bucket.COMMISSION;
            signed = credit ? amount : amount.negate();
        } else if (!credit) {
            bucket = Bucket.DEBIT;
        } else if (sub == SubTransactionType.SRF) {
            bucket = Bucket.REFUND;
        } else {
            bucket = Bucket.NONE;
        }
        if (bucket == Bucket.NONE) {
            return new Share(null, bucket, signed);
        }
        if (attribution == UserPerformance.Attribution.ENTERED_BY) {
            return new Share(entry.getCreatedBy(), bucket, signed);
        }

        ShipmentOwners shipment = entry.getReferenceType() == ReferenceType.SHIPMENT && entry.getReferenceId() != null
                ? owners.get(entry.getReferenceId()) : null;
        if (shipment == null) {
            return new Share(null, bucket, signed);
        }
        UUID who = switch (sub) {
            case SBK, SRF -> shipment.bookedBy();
            case COD, TPY, DRS, DWC -> shipment.deliveryUserId() != null ? shipment.deliveryUserId() : entry.getCreatedBy();
            case COM -> walletBranchId.equals(shipment.bookingBranchId()) ? shipment.bookedBy() : shipment.deliveryUserId();
            case RTO -> entry.getCreatedBy();
            default -> null;
        };
        return new Share(who, bucket, signed);
    }
}
