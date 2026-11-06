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
