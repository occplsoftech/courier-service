package com.courier.modules.communication.application;

import com.courier.modules.communication.domain.CommunicationEventType;
import com.courier.modules.shipment.application.event.ShipmentEvent;
import com.courier.shared.company.CompanyContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * "Shipment/business modules must NOT directly send messages" — the brief's own instruction
 * enforced structurally: {@code ShipmentServiceImpl} publishes plain {@link ShipmentEvent}
 * records (ids and scalars only, see that class's own doc) and has no idea this module
 * exists. This listener is the one place a shipment lifecycle event turns into a
 * communication attempt.
 *
 * <p>Same discipline {@code ShipmentBookingWalletListener}/{@code ShipmentDeliveryWalletListener}
 * already established for cross-module {@code AFTER_COMMIT} side effects: {@code
 * REQUIRES_NEW} (the shipment's own transaction already committed by the time this runs),
 * wrapped in {@code CompanyContext.runAs} (company binding does not reliably survive from
 * the original request thread into an {@code AFTER_COMMIT} callback), and a broad
 * try/catch that only logs — a communication failure can never roll back or otherwise
 * affect the shipment operation that triggered it. The actual queueing
 * ({@code CommunicationOrchestrator.handle}) is a fast DB insert, not a network call — the
 * real provider send happens later, off {@code CommunicationLog}, in {@code
 * CommunicationDispatchJob}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShipmentCommunicationListener {

    private final CommunicationOrchestrator orchestrator;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(ShipmentEvent.Booked event) {
        dispatch(event.companyId(), event.shipmentId(), CommunicationEventType.SHIPMENT_BOOKED);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(ShipmentEvent.PickedUp event) {
        dispatch(event.companyId(), event.shipmentId(), CommunicationEventType.SHIPMENT_PICKED_UP);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(ShipmentEvent.Dispatched event) {
        dispatch(event.companyId(), event.shipmentId(), CommunicationEventType.SHIPMENT_DISPATCHED);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(ShipmentEvent.ReceivedAtBranch event) {
        dispatch(event.companyId(), event.shipmentId(), CommunicationEventType.SHIPMENT_RECEIVED);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(ShipmentEvent.OutForDelivery event) {
        dispatch(event.companyId(), event.shipmentId(), CommunicationEventType.OUT_FOR_DELIVERY);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(ShipmentEvent.Delivered event) {
        dispatch(event.companyId(), event.shipmentId(), CommunicationEventType.SHIPMENT_DELIVERED);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(ShipmentEvent.Cancelled event) {
        dispatch(event.companyId(), event.shipmentId(), CommunicationEventType.SHIPMENT_CANCELLED);
    }

    /** A portal booking with an outside courier was confirmed or reached a delivery stage (fallback: the sync publishes outside a transaction). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(com.courier.modules.customerportal.rates.domain.PortalBookingNotice notice) {
        CommunicationEventType type = switch (notice.event()) {
            case "BOOKED" -> CommunicationEventType.SHIPMENT_BOOKED;
            case "PICKED_UP" -> CommunicationEventType.SHIPMENT_PICKED_UP;
            case "OUT_FOR_DELIVERY" -> CommunicationEventType.OUT_FOR_DELIVERY;
            case "DELIVERED" -> CommunicationEventType.SHIPMENT_DELIVERED;
            case "CANCELLED" -> CommunicationEventType.SHIPMENT_CANCELLED;
            default -> null; // awaiting pickup, in transit, returned: no message
        };
        if (type != null) {
            dispatch(notice.companyId(), notice.bookingId(), type);
        }
    }

    private void dispatch(java.util.UUID companyId, java.util.UUID shipmentId, CommunicationEventType eventType) {
        try {
            CompanyContext.runAs(companyId, () -> orchestrator.handle(companyId, shipmentId, eventType));
        } catch (RuntimeException e) {
            log.error("Failed to queue {} communication for shipment {} in company {} — "
                    + "shipment operation already committed, not affected.", eventType, shipmentId, companyId, e);
        }
    }
}
