package com.courier.modules.shipment.application;

import com.courier.modules.shipment.domain.CourierBookingTrackingPort;
import com.courier.modules.shipment.domain.CourierBookingTrackingPort.CourierTrack;
import com.courier.modules.shipment.domain.ShipmentRepository;
import com.courier.modules.shipment.domain.ShipmentStatus;
import com.courier.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicTrackingCourierFallbackTest {

    private final ShipmentRepository shipments = mock(ShipmentRepository.class);
    private final CourierBookingTrackingPort courier = mock(CourierBookingTrackingPort.class);

    private PublicTrackingServiceImpl service(Optional<CourierBookingTrackingPort> port) {
        return new PublicTrackingServiceImpl(shipments,
                mock(com.courier.modules.shipment.domain.ShipmentStatusHistoryRepository.class),
                mock(com.courier.modules.shipment.domain.ShipmentAssetRepository.class),
                mock(com.courier.modules.pod.domain.PodVerificationRepository.class),
                mock(com.courier.modules.support.domain.TicketRepository.class),
                mock(com.courier.modules.support.domain.TicketCategoryRepository.class),
                mock(com.courier.modules.support.domain.TicketStatusHistoryRepository.class),
                mock(com.courier.modules.support.domain.CompanyTicketSequenceRepository.class),
                mock(com.courier.modules.company.domain.BranchRepository.class),
                mock(com.courier.modules.shipment.application.storage.FileStoragePort.class),
                mock(PublicTrackVerificationLimiter.class),
                port);
    }

    @Test
    @DisplayName("an AWB that is not an OCCPL shipment is shown from the outside courier's booking, with the courier named")
    void showsAnOutsideCourierBooking() {
        Instant booked = Instant.parse("2026-11-05T05:00:00Z");
        when(shipments.findAllByTrackingNumberOrShipmentNumberForPublicTracking("SR123")).thenReturn(List.of());
        when(courier.findByAwb("SR123")).thenReturn(Optional.of(new CourierTrack("SR123", "ORD-9", "Shiprocket", "OUT_FOR_DELIVERY",
                "Out for delivery", "Mumbai", booked, booked.plusSeconds(86400), "Pune", "Mumbai")));

        var r = service(Optional.of(courier)).track("SR123");

        assertThat(r.status()).isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
        assertThat(r.carrier()).isEqualTo("Shiprocket");
        assertThat(r.latestLocation()).isEqualTo("Mumbai");
        assertThat(r.fromCity()).isEqualTo("Pune");
        assertThat(r.timeline()).extracting(e -> e.status()).containsExactly(ShipmentStatus.BOOKED, ShipmentStatus.OUT_FOR_DELIVERY);
    }

    @Test
    @DisplayName("an AWB known to neither is still 'not found'")
    void unknownIsNotFound() {
        when(shipments.findAllByTrackingNumberOrShipmentNumberForPublicTracking("X1")).thenReturn(List.of());
        when(courier.findByAwb("X1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(Optional.of(courier)).track("X1")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service(Optional.empty()).track("X1")).isInstanceOf(ResourceNotFoundException.class);
    }
}
