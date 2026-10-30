package com.courier.modules.pod.api;

import com.courier.modules.pod.api.dto.BulkPodUploadRowResponse;
import com.courier.modules.pod.api.dto.DeliveredShipmentPodResponse;
import com.courier.modules.pod.api.dto.PodVerificationResponse;
import com.courier.modules.pod.application.PodVerificationService;
import com.courier.modules.pod.domain.PodVerification;
import com.courier.modules.shipment.domain.Shipment;
import com.courier.modules.shipment.domain.ShipmentAsset;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@lombok.RequiredArgsConstructor
public class PodVerificationMapper {

    private final com.courier.modules.shipment.application.storage.SignedLinks links;

    public PodVerificationResponse toResponse(PodVerification v, Shipment shipment,
                                               String photoUrl, String signatureUrl) {
        return new PodVerificationResponse(
                v.getId(), v.getShipmentId(),
                shipment == null ? null : shipment.getShipmentNumber(),
                shipment == null ? null : shipment.getTrackingNumber(),
                v.getPodDocumentId(), links.of(photoUrl), links.of(signatureUrl),
                v.getVerificationStatus().name(), v.getVerificationScore(), v.reasons(),
                v.getDetectedReceiverName(), v.getDetectedAwb(), v.getDetectedShipmentNumber(), v.getDetectedDate(),
                v.getDeliveryDate(), v.getDeliveredBy(), v.getPodDate(), v.getPodTime(),
                v.getEntryStatus() == null ? null : v.getEntryStatus().name(), v.getRemark(),
                v.isSignatureDetected(), v.isStampDetected(), v.getImageQuality(),
                v.getAiProvider(), v.getAiModel(), v.getVerifiedAt(),
                v.getReviewedBy(), v.getReviewedAt(), v.getReviewRemarks());
    }

    /** One Bulk POD Upload result row — {@code verificationResponse} is only passed for a
     *  {@code MATCHED} outcome. */
    public BulkPodUploadRowResponse toBulkRow(PodVerificationService.BulkUploadPodOutcome outcome,
                                               PodVerificationResponse verificationResponse) {
        return new BulkPodUploadRowResponse(
                outcome.filename(), outcome.matchStatus().name(), outcome.message(),
                outcome.detectedShipmentNumber(), outcome.detectedAwb(),
                outcome.shipmentId(), outcome.shipmentNumber(), outcome.trackingNumber(),
                verificationResponse);
    }

    /** One POD Review table row — {@code v} and {@code deliveredAt} are null for a delivered
     *  shipment with no POD verification ever run. */
    public DeliveredShipmentPodResponse toDeliveredRow(Shipment s, PodVerification v, Instant receivedAt,
                                                         Instant deliveredAt, List<ShipmentAsset> podAssets) {
        return new DeliveredShipmentPodResponse(
                s.getId(), s.getShipmentNumber(), s.getTrackingNumber(),
                s.getBookingBranchId(), s.getDeliveryBranchId(),
                s.getReceiverName(), receivedAt, deliveredAt,
                v == null ? null : v.getId(),
                v == null ? null : v.getVerificationStatus().name(),
                v == null ? null : v.getVerificationScore(),
                v == null ? null : v.reasons(),
                latestAssetUrl(podAssets, "PHOTO"), latestAssetUrl(podAssets, "SIGNATURE"),
                v == null ? null : v.getAiProvider(), v == null ? null : v.getAiModel());
    }

    private String latestAssetUrl(List<ShipmentAsset> assets, String kind) {
        return assets.stream().filter(a -> kind.equals(a.getKind()))
                .findFirst().map(ShipmentAsset::getAssetUrl).map(links::of).orElse(null);
    }
}
