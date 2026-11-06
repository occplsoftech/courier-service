package com.courier.modules.shipment.application;

import com.courier.modules.company.domain.Branch;
import com.courier.modules.company.domain.BranchRepository;
import com.courier.modules.pod.domain.PodVerification;
import com.courier.modules.pod.domain.PodVerificationRepository;
import com.courier.modules.pod.domain.PodVerificationStatus;
import com.courier.modules.shipment.api.dto.PublicShipmentTicketRequest;
import com.courier.modules.shipment.api.dto.PublicShipmentTicketResponse;
import com.courier.modules.shipment.api.dto.PublicTicketEventResponse;
import com.courier.modules.shipment.api.dto.PublicTrackDetailResponse;
import com.courier.modules.shipment.api.dto.PublicTrackEventResponse;
import com.courier.modules.shipment.api.dto.PublicTrackPodResponse;
import com.courier.modules.shipment.api.dto.PublicTrackResponse;
import com.courier.modules.shipment.api.dto.PublicTrackTicketResponse;
import com.courier.modules.shipment.application.storage.FileStoragePort;
import com.courier.modules.shipment.domain.Shipment;
import com.courier.modules.shipment.domain.ShipmentStatus;
import com.courier.modules.shipment.domain.ShipmentAsset;
import com.courier.modules.shipment.domain.ShipmentAssetRepository;
import com.courier.modules.shipment.domain.ShipmentAssetType;
import com.courier.modules.shipment.domain.ShipmentRepository;
import com.courier.modules.shipment.domain.ShipmentStatusHistoryRepository;
import com.courier.modules.support.domain.CompanyTicketSequenceRepository;
import com.courier.modules.support.domain.Ticket;
import com.courier.modules.support.domain.TicketCategory;
import com.courier.modules.support.domain.TicketCategoryRepository;
import com.courier.modules.support.domain.TicketPriority;
import com.courier.modules.support.domain.TicketRepository;
import com.courier.modules.support.domain.TicketStatus;
import com.courier.modules.support.domain.TicketStatusHistory;
import com.courier.modules.support.domain.TicketStatusHistoryRepository;
import com.courier.shared.domain.TimeOrderedUuid;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ErrorCode;
import com.courier.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PublicTrackingServiceImpl implements PublicTrackingService {

    private static final String ENTITY = "Shipment";
    private static final Duration POD_LINK_TTL = Duration.ofMinutes(15);
    private static final String COMPLAINT_CATEGORY_NAME = "Shipment Issue";
    // TicketStatusHistory.changedByUserId is NOT NULL — there is no established "system
    // actor" convention in this codebase yet (raiseSystemTicket passes null there too, but
    // that path has never actually been exercised against the real DB; it would fail the
    // same way). A nil UUID reads unambiguously as "no human actor" without inventing a
    // fake user row.
    private static final UUID PUBLIC_ACTOR_ID = new UUID(0L, 0L);

    private final ShipmentRepository shipmentRepository;
    private final ShipmentStatusHistoryRepository shipmentStatusHistoryRepository;
    private final ShipmentAssetRepository shipmentAssetRepository;
    private final PodVerificationRepository podVerificationRepository;
    private final TicketRepository ticketRepository;
    private final TicketCategoryRepository ticketCategoryRepository;
    private final TicketStatusHistoryRepository ticketStatusHistoryRepository;
    private final CompanyTicketSequenceRepository ticketSequenceRepository;
    private final BranchRepository branchRepository;
    private final FileStoragePort fileStoragePort;
    private final PublicTrackVerificationLimiter verificationLimiter;
    private final java.util.Optional<com.courier.modules.shipment.domain.CourierBookingTrackingPort> courierBookings;

    @Override
    @Transactional(readOnly = true)
    public PublicTrackResponse track(String number) {
        Shipment shipment;
        try {
            shipment = findShipment(number);
        } catch (ResourceNotFoundException notOurs) {
            // Not an OCCPL shipment: it may be a customer-portal booking delivered by an outside courier.
            var courier = courierBookings.flatMap(p -> p.findByAwb(number));
            if (courier.isEmpty()) {
                throw notOurs;
            }
            return fromCourier(courier.get());
        }

        var timeline = shipmentStatusHistoryRepository.findAllByShipmentIdOrderByChangedAtAsc(shipment.getId())
                .stream()
                .map(h -> new PublicTrackEventResponse(h.getStatus(), h.getChangedAt()))
                .toList();

        return new PublicTrackResponse(
                shipment.getTrackingNumber(), shipment.getShipmentNumber(), shipment.getStatus(),
                shipment.getBookingDate(), shipment.getExpectedDeliveryDate(),
                shipment.getFromCity(), shipment.getToCity(), timeline);
    }

    @Override
    @Transactional(readOnly = true)
    public PublicTrackDetailResponse verifyAndGetDetail(String number, String phoneLast4) {
        Shipment shipment = findShipment(number);
        verifyPhone(shipment, phoneLast4);

        UUID companyId = shipment.getCompanyId();
        String currentLocation = resolveCurrentLocation(shipment, companyId);
        PublicTrackPodResponse pod = resolvePod(shipment, companyId);
        List<PublicTrackTicketResponse> tickets = ticketRepository
                .findAllByRelatedShipmentIdWithinCompany(shipment.getId(), companyId)
                .stream()
                .map(t -> toTicketResponse(t, companyId))
                .toList();

        return new PublicTrackDetailResponse(
                shipment.getSenderName(), shipment.getSenderAddress(), shipment.getSenderContact(),
                shipment.getReceiverName(), shipment.getReceiverAddress(), shipment.getReceiverContact(),
                currentLocation, pod, tickets);
    }

    @Override
    @Transactional
    public PublicShipmentTicketResponse raiseTicket(String number, PublicShipmentTicketRequest request) {
        Shipment shipment = findShipment(number);
        verifyPhone(shipment, request.phoneLast4());

        UUID companyId = shipment.getCompanyId();
        TicketCategory category = ticketCategoryRepository.findByNameIgnoreCase(COMPLAINT_CATEGORY_NAME)
                .orElseThrow(() -> {
                    log.error("Public shipment ticket raised but category '{}' is missing", COMPLAINT_CATEGORY_NAME);
                    return new BusinessRuleException(ErrorCode.SERVICE_UNAVAILABLE,
                            "This isn't available right now. Please call or email us directly.");
                });

        Ticket ticket = Ticket.builder()
                .ticketNumber(nextTicketNumber(companyId))
                .subject(request.subject())
                .description("Raised from the public tracking page for " + shipment.getTrackingNumber()
                        + " (" + shipment.getShipmentNumber() + ").\n\n" + request.message())
                .categoryId(category.getId())
                .priority(TicketPriority.MEDIUM)
                .status(TicketStatus.OPEN)
                .relatedShipmentId(shipment.getId())
                .escalated(false)
                .build();
        // Same reasoning as PublicLeadServiceImpl: no CompanyContext is bound for an
        // unauthenticated request, so this must be set explicitly rather than relying on
        // CompanyEntityListener's auto-stamp.
        ticket.setCompanyId(companyId);
        Ticket saved = ticketRepository.save(ticket);

        // Same "one immutable row per transition, fromStatus null at creation" shape
        // TicketServiceImpl.create() writes — without this, the ticket would show a
        // status-update flow of nothing, unlike every authenticated-path ticket.
        TicketStatusHistory history = TicketStatusHistory.builder()
                .ticketId(saved.getId())
                .fromStatus(null)
                .toStatus(TicketStatus.OPEN)
                .changedByUserId(PUBLIC_ACTOR_ID)
                .remarks("Raised via public tracking page")
                .build();
        history.setCompanyId(companyId);
        ticketStatusHistoryRepository.save(history);

        log.info("Public shipment ticket {} raised for {}", saved.getTicketNumber(), shipment.getTrackingNumber());
        return new PublicShipmentTicketResponse(saved.getTicketNumber());
    }

    /** Shared second-factor gate for both {@link #verifyAndGetDetail} and {@link
     *  #raiseTicket} — same rate-limiter key (shipment number), so raising a ticket can't
     *  be used as a second avenue to brute-force the phone code. */
    private void verifyPhone(Shipment shipment, String phoneLast4) {
        String limiterKey = shipment.getShipmentNumber();
        verificationLimiter.checkAllowed(limiterKey);

        if (!matchesLast4(shipment.getReceiverContact(), phoneLast4)) {
            verificationLimiter.recordFailure(limiterKey);
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED,
                    "Verification failed. Check the phone number and try again.");
        }
        verificationLimiter.reset(limiterKey);
    }

    private String nextTicketNumber(UUID companyId) {
        byte[] companyIdBytes = TimeOrderedUuid.toBytes(companyId);
        ticketSequenceRepository.advance(companyIdBytes);
        long serial = ticketSequenceRepository.nextValue();
        return "TKT-%06d".formatted(serial);
    }

    private static final java.time.ZoneId INDIA = java.time.ZoneId.of("Asia/Kolkata");

    /** An outside courier's booking as the public page shows it: booked, then the courier's last saved stage. */
    private static PublicTrackResponse fromCourier(com.courier.modules.shipment.domain.CourierBookingTrackingPort.CourierTrack t) {
        ShipmentStatus status = switch (t.stage() == null ? "" : t.stage()) {
            case "PICKED_UP" -> ShipmentStatus.IN_SCAN;
            case "IN_TRANSIT" -> ShipmentStatus.DISPATCHED;
            case "OUT_FOR_DELIVERY" -> ShipmentStatus.OUT_FOR_DELIVERY;
            case "DELIVERED" -> ShipmentStatus.DELIVERED;
            case "RETURNED" -> ShipmentStatus.RETURNED;
            case "CANCELLED" -> ShipmentStatus.CANCELLED;
            default -> ShipmentStatus.BOOKED;
        };
        List<PublicTrackEventResponse> timeline = new java.util.ArrayList<>();
        timeline.add(new PublicTrackEventResponse(ShipmentStatus.BOOKED, t.bookedAt()));
        if (status != ShipmentStatus.BOOKED) {
            timeline.add(new PublicTrackEventResponse(status, t.statusAt()));
        }
        return new PublicTrackResponse(t.trackingNumber(), t.reference(), status,
                t.bookedAt() == null ? null : t.bookedAt().atZone(INDIA).toLocalDate(), null, t.fromCity(), t.toCity(), timeline,
                t.courierName(), t.statusText(), t.location());
    }

    private Shipment findShipment(String number) {
        String trimmed = number == null ? "" : number.trim();
        if (trimmed.isEmpty()) {
            throw new ResourceNotFoundException(ENTITY, number);
        }

        List<Shipment> matches = shipmentRepository
                .findAllByTrackingNumberOrShipmentNumberForPublicTracking(trimmed);
        if (matches.isEmpty()) {
            throw new ResourceNotFoundException(ENTITY, trimmed);
        }
        if (matches.size() > 1) {
            // tracking_number is only unique per company — two different companies can
            // genuinely share one. There is no way to tell which the caller means without
            // some other identifying detail, and guessing risks showing one customer's
            // shipment to someone else entirely, so this fails the same way "not found"
            // does rather than picking one.
            log.warn("Public tracking lookup for '{}' matched {} shipments across companies; "
                    + "refusing to guess", trimmed, matches.size());
            throw new ResourceNotFoundException(ENTITY, trimmed);
        }
        return matches.get(0);
    }

    private boolean matchesLast4(String contact, String phoneLast4) {
        if (contact == null || phoneLast4 == null) {
            return false;
        }
        String digits = contact.replaceAll("\\D", "");
        if (digits.length() < 4) {
            return false;
        }
        return digits.substring(digits.length() - 4).equals(phoneLast4);
    }

    private String resolveCurrentLocation(Shipment shipment, UUID companyId) {
        UUID branchId = shipment.getCurrentLocationId();
        if (branchId == null) {
            return null;
        }
        return branchRepository.findByIdWithinCompany(branchId, companyId)
                .map(this::formatBranchLocation)
                .orElse(null);
    }

    private String formatBranchLocation(Branch branch) {
        if (branch.getCity() != null && !branch.getCity().isBlank()) {
            return branch.getBranchName() + ", " + branch.getCity();
        }
        return branch.getBranchName();
    }

    /**
     * Only surfaced once a human reviewer has approved the capture ({@code PASS}) — a
     * shipment still awaiting review, or with a rejected capture, gets no POD block. Photo
     * and signature links are always presigned here: this response goes to an
     * unauthenticated caller, so a permanent link is never handed out, unlike the
     * authenticated POD endpoints.
     */
    private PublicTrackPodResponse resolvePod(Shipment shipment, UUID companyId) {
        Optional<PodVerification> latest = podVerificationRepository
                .findLatestByShipmentIdWithinCompany(shipment.getId(), companyId);
        if (latest.isEmpty() || latest.get().getVerificationStatus() != PodVerificationStatus.PASS) {
            return null;
        }
        PodVerification verification = latest.get();

        List<ShipmentAsset> assets = shipmentAssetRepository
                .findAllByShipmentIdWithinCompany(shipment.getId(), companyId);
        String photoUrl = latestAssetUrl(assets, ShipmentAssetType.POD, "PHOTO");
        String signatureUrl = latestAssetUrl(assets, ShipmentAssetType.POD, "SIGNATURE");

        return new PublicTrackPodResponse(
                photoUrl != null ? fileStoragePort.presignGet(photoUrl, POD_LINK_TTL) : null,
                signatureUrl != null ? fileStoragePort.presignGet(signatureUrl, POD_LINK_TTL) : null,
                verification.getDeliveredBy(),
                verification.getVerifiedAt());
    }

    /** Same "newest row for this (type, kind) wins" idiom as {@code ShipmentMapper}. */
    private String latestAssetUrl(List<ShipmentAsset> assets, ShipmentAssetType type, String kind) {
        return assets.stream()
                .filter(a -> a.getAssetType() == type && kind.equals(a.getKind()))
                .findFirst()
                .map(ShipmentAsset::getAssetUrl)
                .orElse(null);
    }

    private PublicTrackTicketResponse toTicketResponse(Ticket ticket, UUID companyId) {
        List<PublicTicketEventResponse> history = ticketStatusHistoryRepository
                .findByTicket(ticket.getId(), companyId)
                .stream()
                .map(h -> new PublicTicketEventResponse(h.getToStatus().name(), h.getCreatedAt()))
                .toList();

        return new PublicTrackTicketResponse(
                ticket.getTicketNumber(), ticket.getSubject(),
                ticket.getStatus() != null ? ticket.getStatus().name() : null,
                ticket.getPriority() != null ? ticket.getPriority().name() : null,
                ticket.getCreatedAt(), ticket.getResolvedAt(), history);
    }
}
