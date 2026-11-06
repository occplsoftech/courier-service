package com.courier.modules.shipment.application.event;

import com.courier.modules.finance.application.WalletService;
import com.courier.modules.finance.application.command.CodDeliveryDebitCommand;
import com.courier.modules.finance.application.command.DeliveryWeightCommissionCreditCommand;
import com.courier.modules.finance.application.command.DrsChargeCreditCommand;
import com.courier.shared.company.CompanyContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Debits the delivery branch's wallet once a collect-at-delivery shipment's own delivery
 * transaction has committed — the delivery-side mirror of
 * {@link ShipmentBookingWalletListener}, closing the gap the wallet module's {@code COD}
 * sub-transaction type was seeded for but nothing ever triggered. Also credits the delivery
 * branch's wallet with its DRS commission on every delivery.
 *
 * <p>Same AFTER_COMMIT/{@code REQUIRES_NEW} shape: the shipment is already DELIVERED and
 * durable when this runs, so a failure (insufficient balance for the COD debit, a wallet
 * that went INACTIVE mid-flight) cannot roll the delivery back. The shipment stays
 * DELIVERED, un-posted; a real, logged gap in the same shape {@code
 * ShipmentBookingWalletListener} already accepts for the booking side.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShipmentDeliveryWalletListener {

    private final WalletService walletService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(ShipmentEvent.CodCollectedAtDelivery event) {
        try {
            CompanyContext.runAs(event.companyId(), () -> walletService.debitForCodDelivery(
                    new CodDeliveryDebitCommand(event.deliveryBranchId(), event.netAmount(),
                            event.shipmentNumber(), "COD collected for shipment " + event.shipmentNumber())));
        } catch (RuntimeException e) {
            log.error("Could not debit delivery branch {} for shipment {} ({}); the shipment stays "
                    + "delivered, undebited — reconcile manually", event.deliveryBranchId(),
                    event.shipmentNumber(), event.shipmentId(), e);
        }
    }

    /** Pays a portal shipment's collected COD amount into the customer's wallet (once; the wallet call is repeat-safe). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(ShipmentEvent.CodCollectedForCustomer event) {
        try {
            CompanyContext.runAs(event.companyId(), () -> walletService.creditCodForCustomer(
                    new com.courier.modules.finance.application.command.CustomerCodCreditCommand(event.customerId(), event.amount(),
                            event.shipmentId(), event.shipmentNumber())));
        } catch (RuntimeException e) {
            log.error("Could not pay the COD amount of shipment {} ({}) into customer {}'s wallet; the shipment stays delivered — "
                    + "credit it manually", event.shipmentNumber(), event.shipmentId(), event.customerId(), e);
        }
    }

    /**
     * The in-scan-time sibling of {@link #on(ShipmentEvent.CodCollectedAtDelivery)} — fires
     * once {@code scanOneIn}'s own transaction (not {@code deliver}'s) commits, since a
     * TO_PAY shipment's freight is owed the moment it lands at its final delivery branch.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(ShipmentEvent.ToPayReceivedAtDeliveryBranch event) {
        try {
            CompanyContext.runAs(event.companyId(), () -> walletService.debitForToPayReceivedAtBranch(
                    new CodDeliveryDebitCommand(event.deliveryBranchId(), event.netAmount(),
                            event.shipmentNumber(), "TO_PAY received at branch for shipment " + event.shipmentNumber())));
        } catch (RuntimeException e) {
            log.error("Could not debit delivery branch {} for TO_PAY receipt of shipment {} ({}); the "
                    + "shipment stays in-scanned, undebited — reconcile manually", event.deliveryBranchId(),
                    event.shipmentNumber(), event.shipmentId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(ShipmentEvent.DrsChargeApplicable event) {
        try {
            CompanyContext.runAs(event.companyId(), () -> walletService.creditForDrsCharge(
                    new DrsChargeCreditCommand(event.deliveryBranchId(), event.drsCharge(),
                            event.shipmentNumber(), "DRS commission for shipment " + event.shipmentNumber())));
        } catch (RuntimeException e) {
            log.error("Could not credit DRS commission to delivery branch {} for shipment {} ({}); the "
                    + "shipment stays delivered, uncredited — reconcile manually", event.deliveryBranchId(),
                    event.shipmentNumber(), event.shipmentId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(ShipmentEvent.DeliveryWeightCommissionApplicable event) {
        try {
            CompanyContext.runAs(event.companyId(), () -> walletService.creditForDeliveryWeightCommission(
                    new DeliveryWeightCommissionCreditCommand(event.deliveryBranchId(), event.amount(),
                            event.shipmentNumber(), "Delivery weight commission for shipment " + event.shipmentNumber())));
        } catch (RuntimeException e) {
            log.error("Could not credit delivery weight commission to delivery branch {} for shipment {} "
                    + "({}); the shipment stays delivered, uncredited — reconcile manually",
                    event.deliveryBranchId(), event.shipmentNumber(), event.shipmentId(), e);
        }
    }
}
