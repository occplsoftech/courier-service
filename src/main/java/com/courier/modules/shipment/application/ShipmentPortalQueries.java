package com.courier.modules.shipment.application;

import com.courier.modules.shipment.domain.Shipment;
import com.courier.modules.shipment.domain.BranchLookupPort;
import com.courier.modules.shipment.domain.ShipmentRepository;
import com.courier.modules.shipment.domain.ShipmentStatusHistoryRepository;
import com.courier.modules.shipment.domain.ShipmentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only views of one business customer's shipments ({@code shipments.business_customer_id})
 * for the customer portal. No {@code @PreAuthorize}: the portal resolves the customer from the
 * signed-in account and passes it in; nothing else may call this with a customer id from a request.
 */
@Service
@RequiredArgsConstructor
public class ShipmentPortalQueries {

    private static final int MAX_RECENT = 50;

    private final ShipmentRepository repository;
    private final ShipmentStatusHistoryRepository history;
    private final BranchLookupPort branches;
    private final com.courier.modules.shipment.domain.DeliveryAssignmentRepository deliveries;
    private final com.courier.modules.shipment.domain.ShipmentAssetRepository assets;
    private final com.courier.modules.pod.domain.PodVerificationRepository podVerifications;
    private final com.courier.modules.shipment.application.storage.FileStoragePort files;

    /** How long a POD photo/signature link works (the same as public tracking's). */
    private static final java.time.Duration POD_LINK_TTL = java.time.Duration.ofMinutes(15);

    @Transactional(readOnly = true)
    public Map<ShipmentStatus, Long> statusCounts(UUID companyId, UUID customerId) {
        Map<ShipmentStatus, Long> counts = new EnumMap<>(ShipmentStatus.class);
        for (ShipmentStatus s : ShipmentStatus.values()) {
            counts.put(s, 0L);
        }
        repository.countByStatusForBusinessCustomer(companyId, customerId)
                .forEach(row -> counts.put(row.getStatus(), row.getTotal()));
        return counts;
    }

    @Transactional(readOnly = true)
    public List<PortalShipment> recent(UUID companyId, UUID customerId, int limit) {
        int size = Math.max(1, Math.min(limit, MAX_RECENT));
        return repository.findRecentForBusinessCustomer(companyId, customerId, PageRequest.of(0, size)).stream()
                .map(ShipmentPortalQueries::view)
                .toList();
    }

    /**
     * One of the customer's own shipments with its status history, oldest first. Empty when the shipment is
     * not this business customer's — the portal answers that as "not found", never as "forbidden".
     * Each step carries its branch name only; staff remarks and user ids stay internal.
     */
    @Transactional(readOnly = true)
    public Optional<PortalTimeline> timeline(UUID companyId, UUID customerId, UUID shipmentId) {
        return repository.findByIdWithinCompany(shipmentId, companyId)
                .filter(s -> customerId.equals(s.getBusinessCustomerId()))
                .map(s -> timelineOf(s, companyId));
    }

    /**
     * The timeline of the shipment a branch booked from this customer's own pickup request. The caller has already looked the
     * pickup up for that customer, so the link is the proof of ownership; the shipment itself need not carry the customer (a branch
     * booking made from a pickup does not always pick the Business Customer).
     */
    @Transactional(readOnly = true)
    public Optional<PortalTimeline> timelineOfPickupShipment(UUID companyId, UUID shipmentId) {
        return repository.findByIdWithinCompany(shipmentId, companyId).map(s -> timelineOf(s, companyId));
    }

    /** The last failed delivery attempt of a portal shipment and what the customer chose, when an attempt has failed. */
    @Transactional(readOnly = true)
    public Optional<DeliveryIssue> deliveryIssue(UUID companyId, UUID shipmentId) {
        return repository.findByIdWithinCompany(shipmentId, companyId).filter(s -> s.getFailedAttempts() > 0)
                .map(s -> new DeliveryIssue(s.getFailedAttempts(),
                        s.getUndeliveredReason() == null ? null : s.getUndeliveredReason().getLabel(), s.getUndeliveredNote(),
                        s.getUndeliveredAt(), s.isUndeliveredOpen(), s.isReturnFlagged(Instant.now()),
                        s.getUndeliveredChoice() == null ? null : s.getUndeliveredChoice().name(),
                        s.getUndeliveredChoice() == null ? null : s.getUndeliveredChoice().getLabel(), s.getUndeliveredChoiceDate(),
                        s.getReceiverAddress(), s.getReceiverContact()));
    }

    /**
     * Proof of delivery of a delivered portal shipment: who received it and when, always; the photo and signature (short-lived links)
     * only once the POD is approved, the same rule as public tracking. Empty until the shipment is delivered.
     */
    @Transactional(readOnly = true)
    public Optional<Pod> proofOfDelivery(UUID companyId, UUID shipmentId) {
        Optional<Shipment> found = repository.findByIdWithinCompany(shipmentId, companyId).filter(s -> s.getStatus() == ShipmentStatus.DELIVERED);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Shipment s = found.get();
        var assignment = deliveries.findByShipmentIdWithinCompany(shipmentId, companyId).orElse(null);
        var latest = podVerifications.findLatestByShipmentIdWithinCompany(shipmentId, companyId).orElse(null);
        boolean approved = latest != null && latest.getVerificationStatus() == com.courier.modules.pod.domain.PodVerificationStatus.PASS;
        String photo = null;
        String signature = null;
        var all = assets.findAllByShipmentIdWithinCompany(shipmentId, companyId);
        boolean hasPhotoOrSignature = all.stream().anyMatch(a -> a.getAssetType() == com.courier.modules.shipment.domain.ShipmentAssetType.POD);
        if (approved) {
            photo = presign(latestPod(all, "PHOTO"));
            signature = presign(latestPod(all, "SIGNATURE"));
        }
        return Optional.of(new Pod(s.getShipmentNumber(), s.getTrackingNumber(),
                assignment == null ? null : assignment.getReceiverName(),
                assignment != null && assignment.getDeliveredAt() != null ? assignment.getDeliveredAt() : null,
                photo, signature, hasPhotoOrSignature && !approved));
    }

    private static String latestPod(java.util.List<com.courier.modules.shipment.domain.ShipmentAsset> all, String kind) {
        return all.stream().filter(a -> a.getAssetType() == com.courier.modules.shipment.domain.ShipmentAssetType.POD && kind.equals(a.getKind()))
                .findFirst().map(com.courier.modules.shipment.domain.ShipmentAsset::getAssetUrl).orElse(null);
    }

    private String presign(String url) {
        return url == null ? null : files.presignGet(url, POD_LINK_TTL);
    }

    /** {@code photoPending}: a photo or signature exists but is not approved yet. */
    public record Pod(String shipmentNumber, String trackingNumber, String deliveredTo, Instant deliveredAt, String photoUrl,
                      String signatureUrl, boolean photoPending) {
    }

    /** When a portal shipment's COD amount was collected (and paid into the customer's wallet); empty until then. */
    @Transactional(readOnly = true)
    public Optional<Instant> codCollectedAt(UUID companyId, UUID shipmentId) {
        return repository.findByIdWithinCompany(shipmentId, companyId).map(Shipment::getCodCollectedAt);
    }

    public record DeliveryIssue(int attempts, String reason, String note, Instant at, boolean open, boolean returnFlagged,
                                String choice, String choiceLabel, LocalDate choiceDate, String receiverAddress, String receiverPhone) {
    }

    private PortalTimeline timelineOf(Shipment s, UUID companyId) {
        Map<UUID, String> names = new HashMap<>();
        List<PortalStep> steps = history.findAllByShipmentIdWithinCompany(s.getId(), companyId).stream()
                .map(h -> new PortalStep(h.getStatus(), h.getChangedAt(), h.getBranchId() == null ? null
                        : names.computeIfAbsent(h.getBranchId(), id -> branches.findBranch(id, companyId)
                                .map(BranchLookupPort.BranchRef::branchName).orElse(null))))
                .toList();
        return new PortalTimeline(view(s), steps, forwarded(s));
    }

    /** The courier a hub handed the shipment to, when it has (state BOOKED); the customer's tracking then follows that courier too. */
    private static Forwarded forwarded(Shipment s) {
        return s.isForwarded() ? new Forwarded(s.getForwardProvider(), s.getForwardOrderRef(), s.getForwardAwb(), s.getForwardLabelUrl(),
                s.getForwardedAt()) : null;
    }

    private static PortalShipment view(Shipment s) {
        return new PortalShipment(s.getId(), s.getShipmentNumber(), s.getTrackingNumber(), s.getBookingDate(),
                s.getStatus(), s.getReceiverName(), s.getNumberOfPackages(), s.getChargeableWeight(),
                s.getExpectedDeliveryDate(), s.getCreatedAt());
    }

    public record PortalTimeline(PortalShipment shipment, List<PortalStep> steps, Forwarded forwarded) {

        public PortalTimeline(PortalShipment shipment, List<PortalStep> steps) {
            this(shipment, steps, null);
        }
    }

    /** The courier partner a hub handed the shipment to (provider code, its order reference and AWB, label, and when). */
    public record Forwarded(String providerCode, String orderRef, String awb, String labelUrl, Instant at) {
    }

    public record PortalStep(ShipmentStatus status, Instant at, String branchName) {
    }

    /** What the portal shows of a shipment: no internal ids, charges or staff data. */
    public record PortalShipment(UUID id, String shipmentNumber, String trackingNumber, LocalDate bookingDate,
                                 ShipmentStatus status, String receiverName, Integer packages,
                                 BigDecimal chargeableWeight, LocalDate expectedDeliveryDate, Instant createdAt) {
    }
}
