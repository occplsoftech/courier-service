package com.courier.modules.shipment.application;

import com.courier.modules.pod.domain.PodVerification;
import com.courier.modules.pod.domain.PodVerificationRepository;
import com.courier.modules.pod.domain.PodVerificationStatus;
import com.courier.modules.shipment.application.storage.FileStoragePort;
import com.courier.modules.shipment.domain.DeliveryAssignment;
import com.courier.modules.shipment.domain.DeliveryAssignmentRepository;
import com.courier.modules.shipment.domain.DeliveryAssignmentStatus;
import com.courier.modules.shipment.domain.Shipment;
import com.courier.modules.shipment.domain.ShipmentAsset;
import com.courier.modules.shipment.domain.ShipmentAssetRepository;
import com.courier.modules.shipment.domain.ShipmentAssetType;
import com.courier.modules.shipment.domain.ShipmentRepository;
import com.courier.modules.shipment.domain.ShipmentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The verified public tracking view's proof of delivery: POD Review first, else the Delivery Updation record. */
class PublicTrackingPodTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID SHIPMENT = UUID.randomUUID();
    private static final Instant DELIVERED = Instant.parse("2026-11-26T06:30:00Z");

    private final ShipmentRepository shipments = mock(ShipmentRepository.class);
    private final ShipmentAssetRepository assets = mock(ShipmentAssetRepository.class);
    private final PodVerificationRepository verifications = mock(PodVerificationRepository.class);
    private final DeliveryAssignmentRepository deliveries = mock(DeliveryAssignmentRepository.class);
    private final FileStoragePort storage = mock(FileStoragePort.class);
    private final Shipment shipment = mock(Shipment.class);
    private PublicTrackingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PublicTrackingServiceImpl(shipments,
                mock(com.courier.modules.shipment.domain.ShipmentStatusHistoryRepository.class), assets, verifications,
                mock(com.courier.modules.support.domain.TicketRepository.class),
                mock(com.courier.modules.support.domain.TicketCategoryRepository.class),
                mock(com.courier.modules.support.domain.TicketStatusHistoryRepository.class),
                mock(com.courier.modules.support.domain.CompanyTicketSequenceRepository.class),
                mock(com.courier.modules.company.domain.BranchRepository.class), storage,
                mock(PublicTrackVerificationLimiter.class), Optional.empty());
        service.setDeliveryAssignments(deliveries);

        when(shipment.getId()).thenReturn(SHIPMENT);
        when(shipment.getCompanyId()).thenReturn(COMPANY);
        when(shipment.getShipmentNumber()).thenReturn("SHP-1");
        when(shipment.getReceiverContact()).thenReturn("9876543210");
        when(shipment.getStatus()).thenReturn(ShipmentStatus.DELIVERED);
        when(shipments.findAllByTrackingNumberOrShipmentNumberForPublicTracking("AWB1")).thenReturn(List.of(shipment));
        when(storage.presignGet(anyString(), any())).thenAnswer(i -> i.getArgument(0) + "?signed");

        ShipmentAsset photo = mock(ShipmentAsset.class);
        when(photo.getAssetType()).thenReturn(ShipmentAssetType.POD);
        when(photo.getKind()).thenReturn("PHOTO");
        when(photo.getAssetUrl()).thenReturn("s3://pod/photo.jpg");
        when(assets.findAllByShipmentIdWithinCompany(SHIPMENT, COMPANY)).thenReturn(List.of(photo));
        when(verifications.findLatestByShipmentIdWithinCompany(SHIPMENT, COMPANY)).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("a delivery saved from Delivery Updation shows its photo, delivered time and receiver")
    void deliveryUpdationPhotoShown() {
        DeliveryAssignment a = delivered("Ramesh", "Security");
        when(deliveries.findByShipmentIdWithinCompany(SHIPMENT, COMPANY)).thenReturn(Optional.of(a));

        var pod = service.verifyAndGetDetail("AWB1", "3210").pod();

        assertThat(pod).isNotNull();
        assertThat(pod.photoUrl()).isEqualTo("s3://pod/photo.jpg?signed");
        assertThat(pod.deliveredAt()).isEqualTo(DELIVERED);
        assertThat(pod.receivedBy()).isEqualTo("Ramesh (Security)");
    }

    @Test
    @DisplayName("a capture POD Review approved is shown as before")
    void reviewedPassShown() {
        PodVerification v = mock(PodVerification.class);
        when(v.getVerificationStatus()).thenReturn(PodVerificationStatus.PASS);
        when(v.getDeliveredBy()).thenReturn("Suresh");
        when(v.getVerifiedAt()).thenReturn(DELIVERED);
        when(verifications.findLatestByShipmentIdWithinCompany(SHIPMENT, COMPANY)).thenReturn(Optional.of(v));

        var pod = service.verifyAndGetDetail("AWB1", "3210").pod();

        assertThat(pod.photoUrl()).isEqualTo("s3://pod/photo.jpg?signed");
        assertThat(pod.deliveredBy()).isEqualTo("Suresh");
    }

    @Test
    @DisplayName("a capture POD Review holds (awaiting or rejected) stays hidden, even with a delivery record")
    void reviewHoldWins() {
        PodVerification v = mock(PodVerification.class);
        when(v.getVerificationStatus()).thenReturn(PodVerificationStatus.PENDING);
        when(verifications.findLatestByShipmentIdWithinCompany(SHIPMENT, COMPANY)).thenReturn(Optional.of(v));
        DeliveryAssignment a = delivered("Ramesh", null);
        when(deliveries.findByShipmentIdWithinCompany(SHIPMENT, COMPANY)).thenReturn(Optional.of(a));

        assertThat(service.verifyAndGetDetail("AWB1", "3210").pod()).isNull();
    }

    @Test
    @DisplayName("nothing is shown before the shipment is delivered")
    void notDeliveredHidden() {
        when(shipment.getStatus()).thenReturn(ShipmentStatus.OUT_FOR_DELIVERY);
        DeliveryAssignment a = delivered("Ramesh", null);
        when(deliveries.findByShipmentIdWithinCompany(SHIPMENT, COMPANY)).thenReturn(Optional.of(a));

        assertThat(service.verifyAndGetDetail("AWB1", "3210").pod()).isNull();
    }

    private static DeliveryAssignment delivered(String receiver, String relation) {
        DeliveryAssignment a = mock(DeliveryAssignment.class);
        when(a.getStatus()).thenReturn(DeliveryAssignmentStatus.DELIVERED);
        when(a.getDeliveredAt()).thenReturn(DELIVERED);
        when(a.getReceiverName()).thenReturn(receiver);
        when(a.getReceiverRelation()).thenReturn(relation);
        return a;
    }
}
