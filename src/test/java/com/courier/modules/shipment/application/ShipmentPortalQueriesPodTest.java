package com.courier.modules.shipment.application;

import com.courier.modules.pod.domain.PodVerification;
import com.courier.modules.pod.domain.PodVerificationRepository;
import com.courier.modules.pod.domain.PodVerificationStatus;
import com.courier.modules.shipment.application.storage.FileStoragePort;
import com.courier.modules.shipment.domain.BranchLookupPort;
import com.courier.modules.shipment.domain.DeliveryAssignment;
import com.courier.modules.shipment.domain.DeliveryAssignmentRepository;
import com.courier.modules.shipment.domain.Shipment;
import com.courier.modules.shipment.domain.ShipmentAsset;
import com.courier.modules.shipment.domain.ShipmentAssetRepository;
import com.courier.modules.shipment.domain.ShipmentAssetType;
import com.courier.modules.shipment.domain.ShipmentRepository;
import com.courier.modules.shipment.domain.ShipmentStatus;
import com.courier.modules.shipment.domain.ShipmentStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShipmentPortalQueriesPodTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private final ShipmentRepository shipments = mock(ShipmentRepository.class);
    private final DeliveryAssignmentRepository deliveries = mock(DeliveryAssignmentRepository.class);
    private final ShipmentAssetRepository assets = mock(ShipmentAssetRepository.class);
    private final PodVerificationRepository pods = mock(PodVerificationRepository.class);
    private final FileStoragePort files = mock(FileStoragePort.class);
    private final ShipmentPortalQueries queries = new ShipmentPortalQueries(shipments, mock(ShipmentStatusHistoryRepository.class),
            mock(BranchLookupPort.class), deliveries, assets, pods, files);
    private Shipment shipment;
    private final Instant deliveredAt = Instant.parse("2026-11-06T09:30:00Z");

    @BeforeEach
    void setUp() {
        shipment = Shipment.builder().shipmentNumber("PUN-000001").trackingNumber("2611").status(ShipmentStatus.DELIVERED).build();
        shipment.setId(UUID.randomUUID());
        when(shipments.findByIdWithinCompany(shipment.getId(), COMPANY)).thenReturn(Optional.of(shipment));
        DeliveryAssignment a = mock(DeliveryAssignment.class);
        when(a.getReceiverName()).thenReturn("Ravi");
        when(a.getDeliveredAt()).thenReturn(deliveredAt);
        when(deliveries.findByShipmentIdWithinCompany(shipment.getId(), COMPANY)).thenReturn(Optional.of(a));
        ShipmentAsset photo = ShipmentAsset.builder().assetType(ShipmentAssetType.POD).kind("PHOTO").assetUrl("s3://pod/photo.jpg").build();
        when(assets.findAllByShipmentIdWithinCompany(shipment.getId(), COMPANY)).thenReturn(List.of(photo));
        when(files.presignGet(eq("s3://pod/photo.jpg"), any())).thenReturn("https://signed/photo.jpg");
    }

    private void pod(PodVerificationStatus status) {
        PodVerification v = mock(PodVerification.class);
        when(v.getVerificationStatus()).thenReturn(status);
        when(pods.findLatestByShipmentIdWithinCompany(shipment.getId(), COMPANY)).thenReturn(Optional.of(v));
    }

    @Test
    @DisplayName("an approved POD gives who, when, and a short-lived photo link")
    void approvedPod() {
        pod(PodVerificationStatus.PASS);

        var p = queries.proofOfDelivery(COMPANY, shipment.getId()).orElseThrow();

        assertThat(p.deliveredTo()).isEqualTo("Ravi");
        assertThat(p.deliveredAt()).isEqualTo(deliveredAt);
        assertThat(p.photoUrl()).isEqualTo("https://signed/photo.jpg");
        assertThat(p.signatureUrl()).isNull();
        assertThat(p.photoPending()).isFalse();
    }

    @Test
    @DisplayName("a POD not approved yet gives who and when, no photo link, and says the photo is being checked")
    void pendingPod() {
        pod(PodVerificationStatus.PENDING);

        var p = queries.proofOfDelivery(COMPANY, shipment.getId()).orElseThrow();

        assertThat(p.deliveredTo()).isEqualTo("Ravi");
        assertThat(p.photoUrl()).isNull();
        assertThat(p.photoPending()).isTrue();
        verify(files, never()).presignGet(any(), any());
    }

    @Test
    @DisplayName("nothing until the shipment is delivered")
    void notDeliveredYet() {
        shipment.setStatus(ShipmentStatus.OUT_FOR_DELIVERY);
        assertThat(queries.proofOfDelivery(COMPANY, shipment.getId())).isEmpty();
    }
}
