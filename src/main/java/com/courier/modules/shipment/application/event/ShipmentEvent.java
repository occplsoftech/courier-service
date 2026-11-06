package com.courier.modules.shipment.application.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} only — a shipment's own transaction has already
 * succeeded by the time any of these fire, the same discipline
 * {@code finance.application.event.WalletEvent} follows for money movements.
 */
public sealed interface ShipmentEvent {

    UUID companyId();

    Instant occurredAt();

    /**
     * A PREPAID shipment was booked and its booking branch's wallet still needs debiting.
     * Published only when the payment mode collects at booking — see
     * {@code ShipmentServiceImpl.create}. Handled by
     * {@code ShipmentBookingWalletListener}, which calls {@code WalletService
     * .debitForBooking} — the seam {@code MEMORY/modules/branch-wallet.md} documented as
     * deliberately not built ahead of this, its consumer.
     *
     * <p>Carries no commission any more — branch commission is credited on
     * {@link InScanCommissionEarned} instead, once the shipment is in-scanned at its own
     * final delivery branch, not at booking time.
     */
    record PrepaidBookingConfirmed(
            UUID shipmentId,
            UUID companyId,
            UUID bookingBranchId,
            String shipmentNumber,
            BigDecimal netAmount,
            Instant occurredAt
    ) implements ShipmentEvent {
    }

    /**
     * A shipment reached its own final delivery branch and was in-scanned there (not a
     * crossing hub's in-scan — see {@code ShipmentServiceImpl.scanOneIn}'s {@code
     * finalDestination} branch, the same one {@link ReceivedAtBranch} is gated on) and its
     * booking branch has commission still to collect. Published from {@code
     * ShipmentServiceImpl.scanOneIn}, one event per shipment, only when the shipment's
     * payment mode collects at booking and its booking branch has {@code instantCommission}
     * on. Handled by {@code ShipmentBookingWalletListener}, which calls {@code
     * WalletService.creditCommission} — moved here (was on Trip Challan/manifest dispatch
     * creation) on direct user request: "credit commission after shipment in-scan by
     * delivery branch, not after Trip Challan created". Only fires for collect-at-booking
     * payment modes — a collect-at-delivery (TO_PAY/COD) shipment's booking commission
     * credits later instead, via {@link DeliveryCommissionEarned}.
     *
     * @param branchCommission the branch's own two commission lines, summed —
     *                         {@code commissionOnBasicFreight + branchCommissionOnOtherAmount}
     *                         (V28), deliberately <b>not</b> the shipment charge's stored
     *                         {@code totalCommission}, which also folds in the company's own
     *                         {@code companyCommissionOnBasicFreight} — that is company
     *                         revenue and must never land in the branch's wallet.
     */
    record InScanCommissionEarned(
            UUID shipmentId,
            UUID companyId,
            UUID bookingBranchId,
            String shipmentNumber,
            BigDecimal branchCommission,
            Instant occurredAt
    ) implements ShipmentEvent {
    }

    /**
     * A {@code COD} shipment was delivered and its delivery branch's wallet still needs
     * debiting the consignee's collected amount — the delivery-side mirror of {@link
     * PrepaidBookingConfirmed}. Published only for cash-on-delivery payment modes — see
     * {@code ShipmentServiceImpl.deliver}. {@code TO_PAY} does <b>not</b> publish this: its
     * freight is already debited earlier, at {@link ToPayReceivedAtDeliveryBranch}, since
     * that liability is the branch's the moment the shipment arrives, not deferred to actual
     * delivery. Handled by {@code ShipmentDeliveryWalletListener}, which calls {@code
     * WalletService.debitForCodDelivery}.
     */
    record CodCollectedAtDelivery(
            UUID shipmentId,
            UUID companyId,
            UUID deliveryBranchId,
            String shipmentNumber,
            BigDecimal netAmount,
            Instant occurredAt
    ) implements ShipmentEvent {
    }

    /**
     * A {@code TO_PAY} shipment reached its own final delivery branch — in-scanned off its
     * incoming Trip Hire Challan, not a crossing hub's in-scan (see {@code
     * ShipmentServiceImpl.scanOneIn}'s {@code finalDestination} branch, the same one {@link
     * ReceivedAtBranch} is gated on) — and its delivery branch's wallet owes the freight
     * right away. The consignee only pays cash at the door once the shipment is actually
     * delivered, but the branch's liability to the company is booked the moment the
     * shipment is physically on its premises, not deferred to {@link Delivered}. Published
     * only for {@code TO_PAY} (collect-at-delivery, not cash-on-delivery) payment modes —
     * {@code COD}'s amount is the consignee's, not the freight, and still debits at {@link
     * CodCollectedAtDelivery} once actually collected. Handled by {@code
     * ShipmentDeliveryWalletListener}, which calls {@code WalletService
     * .debitForToPayReceivedAtBranch}.
     */
    record ToPayReceivedAtDeliveryBranch(
            UUID shipmentId,
            UUID companyId,
            UUID deliveryBranchId,
            String shipmentNumber,
            BigDecimal netAmount,
            Instant occurredAt
    ) implements ShipmentEvent {
    }

    /**
     * A collect-at-delivery ({@code TO_PAY}/{@code COD}) shipment was delivered — its
     * booking branch has commission still to collect, only now that payment has actually
     * been collected. The {@link InScanCommissionEarned} trigger never fires for these
     * (it's gated to collect-at-booking payment modes only), so without this a TO_PAY
     * order's booking branch never got its commission at all. Published from {@code
     * ShipmentServiceImpl.deliver}, same eligibility {@link InScanCommissionEarned} uses
     * otherwise (booking branch has {@code instantCommission} on, commission > 0). Handled
     * by {@code ShipmentBookingWalletListener}, which calls the same {@code WalletService
     * .creditCommission} — this is the booking branch's own commission, not a delivery-side
     * credit, even though it's triggered by delivery.
     *
     * @param branchCommission same two-line sum as {@link InScanCommissionEarned} —
     *                         {@code commissionOnBasicFreight + branchCommissionOnOtherAmount}
     */
    record DeliveryCommissionEarned(
            UUID shipmentId,
            UUID companyId,
            UUID bookingBranchId,
            String shipmentNumber,
            BigDecimal branchCommission,
            Instant occurredAt
    ) implements ShipmentEvent {
    }

    /**
     * A shipment was delivered and its delivery branch's wallet still needs crediting with
     * DRS commission ({@code drsCharge = delivery branch's own drsChargePerQty * item
     * quantity}) — published on every delivery, unlike {@link CodCollectedAtDelivery} which
     * only fires for collect-at-delivery payment modes. See {@code
     * ShipmentServiceImpl.deliver}. Handled by {@code ShipmentDeliveryWalletListener}, which
     * calls {@code WalletService.creditForDrsCharge}. Not published when {@code drsCharge} is
     * zero (a branch with {@code drsChargePerQty} set to 0) — nothing to credit.
     */
    record DrsChargeApplicable(
            UUID shipmentId,
            UUID companyId,
            UUID deliveryBranchId,
            String shipmentNumber,
            BigDecimal drsCharge,
            Instant occurredAt
    ) implements ShipmentEvent {
    }

    /**
     * A shipment was delivered and its delivery branch's wallet still needs crediting with
     * weight-based delivery commission ({@code amount = delivery branch's own
     * deliveryCommissionRatePerKg * max(shipment's chargeable weight, branch's
     * deliveryCommissionMinWeightKg)}) — published on every delivery, independent of and in
     * addition to {@link DrsChargeApplicable}. See {@code ShipmentServiceImpl.deliver}.
     * Handled by {@code ShipmentDeliveryWalletListener}, which calls {@code
     * WalletService.creditForDeliveryWeightCommission}. Not published when {@code amount} is
     * zero (a branch with {@code deliveryCommissionRatePerKg} set to 0) — nothing to credit.
     */
    record DeliveryWeightCommissionApplicable(
            UUID shipmentId,
            UUID companyId,
            UUID deliveryBranchId,
            String shipmentNumber,
            BigDecimal amount,
            Instant occurredAt
    ) implements ShipmentEvent {
    }

    // ---------------------------------------------------------------- Communication Center
    //
    // The six records below carry only ids/scalars, same discipline as every event above —
    // com.courier.modules.communication's own listener re-reads full shipment detail at
    // dispatch time rather than trusting a copy that could go stale by the time a retry
    // re-processes it minutes later. This module has no idea that listener exists; it just
    // publishes what happened. See ShipmentServiceImpl's six call sites (create/cancel/
    // transitionToDispatched/scanOneIn/assignOneOutForDelivery/deliver) and
    // com.courier.modules.communication.application.ShipmentCommunicationListener.
    //
    // No RTO_INITIATED/RTO_DELIVERED equivalents exist here — this codebase has no
    // return-to-origin flow yet (only a generic RETURNED terminal state nothing writes, per
    // ShipmentStatus's own doc). CommunicationEventType still declares both for architecture
    // readiness; nothing publishes into them until a real RTO module exists.

    record Booked(UUID shipmentId, UUID companyId, Instant occurredAt) implements ShipmentEvent {
    }

    /** The parcel of a portal-booked shipment was collected from the customer (its pickup was completed). */
    record PickedUp(UUID shipmentId, UUID companyId, Instant occurredAt) implements ShipmentEvent {
    }

    /** A delivery attempt failed; the parcel is back at the delivery branch (Phase 10.2). */
    record DeliveryFailed(UUID shipmentId, UUID companyId, Instant occurredAt) implements ShipmentEvent {
    }

    /** The shipment was returned to the sender after failed delivery attempts (Phase 10.2). */
    record Returned(UUID shipmentId, UUID companyId, Instant occurredAt) implements ShipmentEvent {
    }

    record Dispatched(UUID shipmentId, UUID companyId, Instant occurredAt) implements ShipmentEvent {
    }

    /** Published when a shipment reaches its own final destination branch — not on an
     *  intermediate crossing-hub in-scan (see {@code ShipmentServiceImpl.scanOneIn}'s
     *  {@code finalDestination} branch), since a crossing hop is not the customer-facing
     *  "your shipment arrived at the branch" moment the brief's {@code SHIPMENT_RECEIVED}
     *  describes. */
    record ReceivedAtBranch(UUID shipmentId, UUID companyId, Instant occurredAt) implements ShipmentEvent {
    }

    record OutForDelivery(UUID shipmentId, UUID companyId, Instant occurredAt) implements ShipmentEvent {
    }

    record Delivered(UUID shipmentId, UUID companyId, Instant occurredAt) implements ShipmentEvent {
    }

    /**
     * A shipment delivered by a Pickup-Delivery Partner is DELIVERED with its POD approved — the
     * moment the partner's delivery rate card applies. Published instead of the branch
     * delivery-commission events (a partner is paid from its rate card, not the branch commission
     * fields). Handled synchronously by the pdp module, which accrues an earning; nothing here
     * moves money. {@code weightKg} is the shipment's chargeable weight (actual if absent).
     */
    record PartnerDeliveryEarned(
            UUID shipmentId,
            UUID companyId,
            UUID partnerBranchId,
            String shipmentNumber,
            String deliveryPincode,
            java.math.BigDecimal weightKg,
            Instant occurredAt
    ) implements ShipmentEvent {
    }

    /**
     * Carries {@code shipmentNumber} — unlike its five siblings above — because {@code
     * ShipmentCancellationWalletListener} needs it to look up every wallet entry filed
     * against this shipment ({@code referenceId}) and reverse them.
     */
    record Cancelled(UUID shipmentId, UUID companyId, String shipmentNumber, Instant occurredAt)
            implements ShipmentEvent {
    }
}
