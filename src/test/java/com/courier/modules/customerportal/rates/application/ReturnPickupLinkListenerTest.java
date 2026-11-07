package com.courier.modules.customerportal.rates.application;

import com.courier.modules.customerportal.rates.domain.PortalBooking;
import com.courier.modules.customerportal.rates.domain.PortalBookingRepository;
import com.courier.modules.pickup.domain.PickupShipmentLinked;
import com.courier.modules.shipment.application.ShipmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReturnPickupLinkListenerTest {

    private static final UUID COMPANY = UUID.randomUUID();

    private final PortalBookingRepository bookings = mock(PortalBookingRepository.class);
    private final ShipmentService shipments = mock(ShipmentService.class);
    private final ReturnPickupLinkListener listener = new ReturnPickupLinkListener(bookings, shipments);

    @Test
    @DisplayName("a shipment booked by hand for a return pickup is flagged as a return pickup; a normal pickup's is not")
    void flagsOnlyReturnPickups() {
        UUID shipment = UUID.randomUUID();
        PortalBooking ret = PortalBooking.builder().returnOfBookingId(UUID.randomUUID()).build();
        when(bookings.findByProviderRef(COMPANY, "OCCPL_NETWORK", "PU-RET")).thenReturn(Optional.of(ret));
        listener.on(new PickupShipmentLinked(COMPANY, UUID.randomUUID(), "PU-RET", shipment));
        verify(shipments).markReturnPickup(COMPANY, shipment);

        UUID other = UUID.randomUUID();
        when(bookings.findByProviderRef(COMPANY, "OCCPL_NETWORK", "PU-NORMAL")).thenReturn(Optional.of(PortalBooking.builder().build()));
        listener.on(new PickupShipmentLinked(COMPANY, UUID.randomUUID(), "PU-NORMAL", other));
        when(bookings.findByProviderRef(COMPANY, "OCCPL_NETWORK", "PU-WALKIN")).thenReturn(Optional.empty());
        listener.on(new PickupShipmentLinked(COMPANY, UUID.randomUUID(), "PU-WALKIN", other));
        verify(shipments, never()).markReturnPickup(any(), org.mockito.ArgumentMatchers.eq(other));
    }
}
