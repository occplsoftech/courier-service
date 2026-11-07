package com.courier.modules.communication.application;

import com.courier.modules.communication.domain.CommunicationEventType;
import com.courier.modules.customerportal.rates.domain.PortalBookingNotice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PortalBookingNoticeListenerTest {

    private final CommunicationOrchestrator orchestrator = mock(CommunicationOrchestrator.class);
    private final ShipmentCommunicationListener listener = new ShipmentCommunicationListener(orchestrator);
    private final UUID company = UUID.randomUUID();
    private final UUID booking = UUID.randomUUID();

    @Test
    @DisplayName("an outside courier's booking and stages become the same events as an OCCPL shipment")
    void mapsStagesToEvents() {
        listener.on(new PortalBookingNotice(booking, company, "BOOKED"));
        listener.on(new PortalBookingNotice(booking, company, "PICKED_UP"));
        listener.on(new PortalBookingNotice(booking, company, "OUT_FOR_DELIVERY"));
        listener.on(new PortalBookingNotice(booking, company, "DELIVERED"));
        listener.on(new PortalBookingNotice(booking, company, "CANCELLED"));

        verify(orchestrator).handle(company, booking, CommunicationEventType.SHIPMENT_BOOKED);
        verify(orchestrator).handle(company, booking, CommunicationEventType.SHIPMENT_PICKED_UP);
        verify(orchestrator).handle(company, booking, CommunicationEventType.OUT_FOR_DELIVERY);
        verify(orchestrator).handle(company, booking, CommunicationEventType.SHIPMENT_DELIVERED);
        verify(orchestrator).handle(company, booking, CommunicationEventType.SHIPMENT_CANCELLED);
    }

    @Test
    @DisplayName("return to sender started sends the return-to-origin message; handed back sends the returned message")
    void returnedSendsRto() {
        listener.on(new com.courier.modules.shipment.application.event.ShipmentEvent.RtoStarted(booking, company, "SH-1", java.time.Instant.now()));
        verify(orchestrator).handle(company, booking, CommunicationEventType.RTO_INITIATED);
        listener.on(new com.courier.modules.shipment.application.event.ShipmentEvent.Returned(booking, company, java.time.Instant.now()));
        verify(orchestrator).handle(company, booking, CommunicationEventType.RTO_DELIVERED);
    }

    @Test
    @DisplayName("stages with no message (awaiting pickup, in transit, returned) send nothing")
    void quietStages() {
        listener.on(new PortalBookingNotice(booking, company, "AWAITING_PICKUP"));
        listener.on(new PortalBookingNotice(booking, company, "IN_TRANSIT"));
        listener.on(new PortalBookingNotice(booking, company, "RETURNED"));

        verify(orchestrator, never()).handle(any(), any(), any());
    }
}
