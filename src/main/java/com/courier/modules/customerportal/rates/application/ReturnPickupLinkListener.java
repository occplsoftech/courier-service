package com.courier.modules.customerportal.rates.application;

import com.courier.modules.customerportal.rates.domain.PortalBookingRepository;
import com.courier.modules.pickup.domain.PickupShipmentLinked;
import com.courier.modules.shipment.application.ShipmentService;
import com.courier.shared.company.CompanyContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * A branch booked by hand the shipment of a portal pickup (the buyer's pincode had no booking branch, so only a pickup was made). When
 * that pickup is a return pickup, the shipment is flagged as one, as it would have been had it been issued at booking (Phase 12.1 B).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReturnPickupLinkListener {

    private static final String OCCPL_NETWORK = "OCCPL_NETWORK";

    private final PortalBookingRepository bookings;
    private final ShipmentService shipments;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(PickupShipmentLinked event) {
        try {
            CompanyContext.runAs(event.companyId(), () -> bookings.findByProviderRef(event.companyId(), OCCPL_NETWORK, event.pickupNo())
                    .filter(b -> b.getReturnOfBookingId() != null)
                    .ifPresent(b -> shipments.markReturnPickup(event.companyId(), event.shipmentId())));
        } catch (RuntimeException e) {
            log.error("Could not flag shipment {} of return pickup {} as a return pickup", event.shipmentId(), event.pickupNo(), e);
        }
    }
}
