package com.courier.modules.crossing.application;

import com.courier.modules.crossing.domain.HubOutScan;
import com.courier.modules.crossing.domain.HubOutScanRepository;
import com.courier.modules.crossing.domain.ShipmentException;
import com.courier.modules.crossing.domain.ShipmentExceptionCriteria;
import com.courier.modules.crossing.domain.ShipmentExceptionRepository;
import com.courier.modules.crossing.domain.ShipmentExceptionSpecifications;
import com.courier.modules.crossing.domain.ShipmentExceptionStatus;
import com.courier.modules.crossing.domain.ShipmentExceptionType;
import com.courier.modules.manifest.application.ManifestService;
import com.courier.modules.manifest.domain.Manifest;
import com.courier.modules.manifest.domain.ManifestCriteria;
import com.courier.modules.manifest.domain.ManifestStatus;
import com.courier.modules.shipment.application.ShipmentService;
import com.courier.modules.shipment.application.ShipmentService.MovementOutcome;
import com.courier.modules.shipment.domain.Shipment;
import com.courier.modules.shipment.domain.ShipmentCriteria;
import com.courier.modules.shipment.domain.ShipmentStatus;
import com.courier.modules.support.application.TicketCategoryService;
import com.courier.modules.support.application.TicketService;
import com.courier.modules.support.application.command.CreateTicketCommand;
import com.courier.modules.support.domain.Ticket;
import com.courier.modules.support.domain.TicketCategory;
import com.courier.modules.support.domain.TicketPriority;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.audit.domain.AuditAction;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import com.courier.shared.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** See {@link HubOperationsService}. */
@Slf4j
@Service
@RequiredArgsConstructor
public class HubOperationsServiceImpl implements HubOperationsService {

    private static final String EXCEPTION_ENTITY = "ShipmentException";

    private final HubOutScanRepository hubOutScanRepository;
    private final ShipmentExceptionRepository exceptionRepository;
    private final ShipmentService shipmentService;
    private final ManifestService manifestService;
    private final TicketService ticketService;
    private final TicketCategoryService ticketCategoryService;
    private final AuditService auditService;

    @Override
    @Transactional
    @PreAuthorize("hasAuthority('HUB_OUT_SCAN')")
    public List<MovementOutcome> outScan(UUID manifestId, UUID hubBranchId, List<String> trackingNumbers) {
        // Company- and manifest-scoping guard: a foreign or unknown manifest 404s here,
        // before a single tracking number is looked at.
        Manifest manifest = manifestService.getById(manifestId);

        List<MovementOutcome> outcomes = new ArrayList<>(trackingNumbers.size());
        for (String trackingNumber : trackingNumbers) {
            outcomes.add(outScanOne(manifest, hubBranchId, trackingNumber));
        }
        return outcomes;
    }

    private MovementOutcome outScanOne(Manifest manifest, UUID hubBranchId, String trackingNumber) {
        Shipment shipment;
        try {
            shipment = shipmentService.getByTrackingNumber(trackingNumber);
        } catch (ResourceNotFoundException e) {
            return new MovementOutcome(trackingNumber, false, "No such tracking number.");
        }
        if (!Objects.equals(shipment.getManifestId(), manifest.getId())) {
            return new MovementOutcome(trackingNumber, false, "Not on this Load Sheet.");
        }
        if (shipment.getStatus() != ShipmentStatus.MANIFEST_CREATED) {
            return new MovementOutcome(trackingNumber, false,
                    "Cannot be out-scanned — currently " + shipment.getStatus() + ".");
        }
        // Pre-check, not catch-after-flush: a flush that throws leaves the Hibernate
        // session unusable for the rest of this bulk call's other tracking numbers (a
        // second, unrelated item then 500s instead of getting its own outcome) — found
        // live during verification, not by the mocked unit test. The unique constraint
        // stays as the structural, DB-level backstop against a genuine race between two
        // concurrent requests; this check is what makes the common case a clean outcome.
        if (hubOutScanRepository.existsByCompanyIdAndManifestIdAndShipmentId(
                manifest.getCompanyId(), manifest.getId(), shipment.getId())) {
            return new MovementOutcome(trackingNumber, false, "Already out-scanned.");
        }

        HubOutScan scan = HubOutScan.builder()
                .manifestId(manifest.getId())
                .shipmentId(shipment.getId())
                .hubBranchId(hubBranchId)
                .scannedBy(currentUserId())
                .scannedAt(Instant.now())
                .build();
        hubOutScanRepository.save(scan);

        auditService.record(AuditAction.HUB_OUT_SCANNED, "Shipment", shipment.getId(),
                Map.of("manifestId", manifest.getId().toString(), "manifestNumber", manifest.getManifestNumber(),
                        "hubBranchId", hubBranchId.toString()));
        return new MovementOutcome(trackingNumber, true, "Out-scanned.");
    }

    @Override
    @Transactional
    @PreAuthorize("hasAuthority('HUB_EXCEPTION_MANAGE')")
    public ShipmentException raiseException(UUID shipmentId, UUID hubBranchId, ShipmentExceptionType type,
                                             String remarks) {
        if (type == null) {
            throw new BusinessRuleException("An exception needs a type.");
        }
        // Company-scoping guard: a foreign or unknown shipment 404s here. ShipmentException
        // itself gets companyId stamped by CompanyEntityListener on save, same as every
        // other CompanyOwnedEntity.
        Shipment shipment = shipmentService.getById(shipmentId);

        Optional<Ticket> ticket = raiseExceptionTicket(shipment, hubBranchId, type, remarks);

        ShipmentException exception = ShipmentException.builder()
                .shipmentId(shipment.getId())
                .hubBranchId(hubBranchId)
                .exceptionType(type)
                .status(ShipmentExceptionStatus.OPEN)
                .remarks(remarks)
                .raisedBy(currentUserId())
                .raisedAt(Instant.now())
                .ticketId(ticket.map(Ticket::getId).orElse(null))
                .build();
        ShipmentException saved = exceptionRepository.save(exception);

        log.info("Exception {} raised for shipment {} at hub {} by {} (ticket {})",
                type, shipment.getId(), hubBranchId, currentActor(),
                ticket.map(Ticket::getTicketNumber).orElse("none"));
        auditService.record(AuditAction.HUB_EXCEPTION_RAISED, EXCEPTION_ENTITY, saved.getId(),
                Map.of("shipmentId", shipment.getId().toString(), "type", type.name(),
                        "hubBranchId", hubBranchId.toString(),
                        "ticketNumber", ticket.map(Ticket::getTicketNumber).orElse("")));

        return saved;
    }

    /**
     * Best-effort, mirrors {@code ShipmentServiceImpl.raiseShortageTicket} exactly: a
     * missing/deactivated "Shipment Issue" category just skips the ticket with a warning,
     * never blocks or rolls back raising the exception itself.
     */
    private Optional<Ticket> raiseExceptionTicket(Shipment shipment, UUID hubBranchId, ShipmentExceptionType type,
                                                   String remarks) {
        Optional<TicketCategory> category = ticketCategoryService.listCategories().stream()
                .filter(TicketCategory::isActive)
                .filter(c -> "Shipment Issue".equalsIgnoreCase(c.getName()))
                .findFirst();
        if (category.isEmpty()) {
            log.warn("No 'Shipment Issue' ticket category found — skipping auto-raised ticket for "
                    + "{} exception on shipment {}", type, shipment.getId());
            return Optional.empty();
        }
        String subject = "%s — shipment %s at hub".formatted(type.name(), shipment.getShipmentNumber());
        String description = remarks == null || remarks.isBlank()
                ? "Hub Operations raised a %s exception against this shipment.".formatted(type.name())
                : remarks;
        Ticket ticket = ticketService.create(new CreateTicketCommand(
                subject, description, category.get().getId(), null, TicketPriority.HIGH,
                shipment.getId(), null, hubBranchId, null));
        return Optional.of(ticket);
    }

    @Override
    @Transactional
    @PreAuthorize("hasAuthority('HUB_EXCEPTION_MANAGE')")
    public ShipmentException resolveException(UUID id, String resolutionRemarks) {
        UUID companyId = requireCompany();
        ShipmentException exception = exceptionRepository.findByIdWithinCompany(id, companyId)
                .orElseThrow(() -> new ResourceNotFoundException(EXCEPTION_ENTITY, id));
        exception.resolve(currentUserId(), resolutionRemarks);
        ShipmentException saved = exceptionRepository.save(exception);

        auditService.record(AuditAction.HUB_EXCEPTION_RESOLVED, EXCEPTION_ENTITY, saved.getId(),
                Map.of("shipmentId", saved.getShipmentId().toString()));
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('HUB_EXCEPTION_MANAGE') or hasAuthority('HUB_READ')")
    public ShipmentException getException(UUID id) {
        UUID companyId = requireCompany();
        return exceptionRepository.findByIdWithinCompany(id, companyId)
                .orElseThrow(() -> new ResourceNotFoundException(EXCEPTION_ENTITY, id));
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('HUB_EXCEPTION_MANAGE') or hasAuthority('HUB_READ')")
    public Page<ShipmentException> searchExceptions(ShipmentExceptionCriteria criteria, Pageable pageable) {
        UUID companyId = requireCompany();
        ShipmentExceptionCriteria safe =
                (criteria == null ? ShipmentExceptionCriteria.none() : criteria).scopedTo(companyId);
        return exceptionRepository.findAll(ShipmentExceptionSpecifications.matching(safe), pageable);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('HUB_READ')")
    public HubDashboardStats dashboard(UUID hubBranchId) {
        UUID companyId = requireCompany();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant from = today.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        long todaysInbound = shipmentService.countArrivalsAt(hubBranchId, from, to);

        long pendingInScan = shipmentService.search(
                atLocation(null, hubBranchId, Set.of(ShipmentStatus.DISPATCHED)),
                PageRequest.of(0, 1)).getTotalElements();

        long shipmentsAtHub = shipmentService.search(
                atLocation(hubBranchId, null,
                        Set.of(ShipmentStatus.READY_FOR_MANIFEST, ShipmentStatus.IN_SCAN,
                                ShipmentStatus.MANIFEST_CREATED)),
                PageRequest.of(0, 1)).getTotalElements();

        long pendingSorting = shipmentService.search(
                atLocation(hubBranchId, null, Set.of(ShipmentStatus.READY_FOR_MANIFEST)),
                PageRequest.of(0, 1)).getTotalElements();

        long readyForDispatch = shipmentService.search(
                atLocation(hubBranchId, null, Set.of(ShipmentStatus.MANIFEST_CREATED)),
                PageRequest.of(0, 1)).getTotalElements();

        List<Manifest> hubManifests = manifestService
                .search(new ManifestCriteria(null, hubBranchId, null, null), PageRequest.of(0, 500,
                        Sort.by(Sort.Direction.DESC, "createdAt")))
                .getContent();
        long dispatchedToday = hubManifests.stream()
                .filter(Manifest::isDispatched)
                .filter(m -> m.getDispatchedAt() != null && !m.getDispatchedAt().isBefore(from)
                        && m.getDispatchedAt().isBefore(to))
                .count();
        long todaysLoadSheets = hubManifests.stream()
                .filter(m -> m.getCreatedAt() != null && !m.getCreatedAt().isBefore(from)
                        && m.getCreatedAt().isBefore(to))
                .count();

        long pendingExceptions = exceptionRepository.countByCompanyIdAndHubBranchIdAndStatus(
                companyId, hubBranchId, ShipmentExceptionStatus.OPEN);

        return new HubDashboardStats(todaysInbound, pendingInScan, shipmentsAtHub, pendingSorting,
                readyForDispatch, dispatchedToday, pendingExceptions, todaysLoadSheets);
    }

    /** {@code currentLocationId} xor {@code nextLocationId} — exactly one of the pair is
     *  non-null, matching {@code ShipmentCriteria}'s own field order. */
    private ShipmentCriteria atLocation(UUID currentLocationId, UUID nextLocationId, Set<ShipmentStatus> statuses) {
        return new ShipmentCriteria(statuses, null, null, currentLocationId, nextLocationId, null,
                null, null, null, null, null, null, null, null);
    }

    private UUID requireCompany() {
        return CompanyContext.getCompanyId().orElseThrow(() -> new BusinessRuleException(
                "No company is bound to this request. Hub Operations belongs to a company, "
                        + "so this operation must be performed by a user of that company."));
    }

    private UUID currentUserId() {
        return SecurityUtils.getCurrentUserId().orElse(null);
    }

    private String currentActor() {
        return SecurityUtils.getCurrentUser()
                .map(user -> user.email() == null ? user.userId().toString() : user.email())
                .orElse("system");
    }
}
