package com.courier.modules.customerportal.rates.application;

import com.courier.modules.shipment.application.event.ShipmentEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** An OCCPL Network shipment started its return to the sender: charge the portal booking it came from (Phase 12.1). */
@Component
@RequiredArgsConstructor
public class PortalRtoListener {

    private final PortalRtoChargeService charges;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ShipmentEvent.RtoStarted event) {
        charges.chargeForShipment(event.companyId(), event.shipmentId());
    }
}
