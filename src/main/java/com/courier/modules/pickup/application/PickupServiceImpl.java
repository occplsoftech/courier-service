package com.courier.modules.pickup.application;

import com.courier.modules.company.application.BranchPincodeMappingService;
import com.courier.modules.company.application.BranchService;
import com.courier.modules.districtfreight.domain.PincodeCoverageLookupPort;
import com.courier.modules.company.application.UserService;
import com.courier.modules.company.domain.Branch;
import com.courier.modules.company.domain.BranchType;
import com.courier.modules.pdp.application.PdpEarningService;
import com.courier.modules.pickup.domain.PickupRequest;
import com.courier.modules.pickup.domain.PickupRequestRepository;
import com.courier.modules.pickup.domain.PickupStatus;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.audit.domain.AuditAction;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import com.courier.shared.security.AuthenticatedUser;
import com.courier.shared.security.Roles;
import com.courier.shared.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PickupServiceImpl implements PickupService {

    private static final String ENTITY = "PickupRequest";
    /** Who raises, edits, routes and cancels pickups: the company's admin only. A partner's own
     *  branch manager works assigned pickups (READ-gated actions below) but never routes them. */
    private static final String STAFF = "hasRole('" + Roles.COMPANY_ADMIN + "')";
    private static final String READ = "isAuthenticated()";

    private final PickupRequestRepository pickups;
    private final BranchService branchService;
    private final UserService userService;
    private final AuditService auditService;
    private final PdpEarningService earningService;
    private final BranchPincodeMappingService pincodeMappings;
    private final PincodeCoverageLookupPort coverage;
    private final com.courier.modules.company.domain.BranchRepository branches;

    @Override
    @Transactional
    @PreAuthorize(STAFF)
    public PickupRequest create(SaveCommand c) {
        UUID companyId = requireCompany();
        PickupRequest p = PickupRequest.builder().pickupNo(nextPickupNo(companyId)).build();
        apply(p, c);
        if (c.pdpBranchId() != null) {
            requirePartner(c.pdpBranchId());
            p.setPdpBranchId(c.pdpBranchId());
            p.setAssignedAt(Instant.now());
            p.setStatus(PickupStatus.ASSIGNED);
        }
        PickupRequest saved = pickups.save(p);
        audit(AuditAction.PICKUP_CREATED, saved);
        return saved;
    }

    @Override
    @Transactional
    public PickupRequest createFromCustomerPortal(SaveCommand c) {
        UUID companyId = requireCompany();
        if (c.customerId() == null) {
            throw new BusinessRuleException("A portal pickup must name its customer.");
        }
        PickupRequest p = PickupRequest.builder().pickupNo(nextPickupNo(companyId)).build();
        apply(p, c);
        // The branch the pickup pincode is mapped to owns it and assigns it to its pickup team.
        coverage.findByPincode(c.pincode())
                .flatMap(ref -> pincodeMappings.findBranchForPincode(ref.pincodeId()))
                .filter(b -> b.isActive() && b.getBranchType() != BranchType.PICKUP_DELIVERY_PARTNER)
                .ifPresent(b -> p.setBookingBranchId(b.getId()));
        PickupRequest saved = pickups.save(p);
        auditService.record(AuditAction.PICKUP_CREATED, ENTITY, saved.getId(),
                Map.of("pickupNo", saved.getPickupNo(), "status", saved.getStatus().name(), "source", "CUSTOMER_PORTAL"));
        log.info("Pickup {} requested from the customer portal for customer {}", saved.getPickupNo(), c.customerId());
        return saved;
    }

    @Override
    @Transactional
    public PickupRequest attachPortalShipment(UUID id, UUID shipmentId, String shipmentNo, UUID bookingBranchId) {
        PickupRequest p = load(id);
        p.setShipmentId(shipmentId);
        p.setShipmentNo(shipmentNo);
        p.setBookingBranchId(bookingBranchId);
        return pickups.save(p);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<PickupRequest> findByNumberForCustomer(String pickupNo, UUID customerId) {
        return pickups.findByPickupNo(requireCompany(), pickupNo).filter(p -> customerId.equals(p.getCustomerId()));
    }

    @Override
    @Transactional
    @PreAuthorize(STAFF)
    public PickupRequest update(UUID id, SaveCommand c) {
        PickupRequest p = load(id);
        if (p.getStatus() != PickupStatus.REQUESTED && p.getStatus() != PickupStatus.ASSIGNED) {
            throw new BusinessRuleException("Only a pickup that is still Requested or Assigned can be edited.");
        }
        if (c.expectedVersion() != null && !Objects.equals(p.getVersion(), c.expectedVersion())) {
            throw new ObjectOptimisticLockingFailureException(PickupRequest.class, p.getId());
        }
        apply(p, c);
        PickupRequest saved = pickups.save(p);
        audit(AuditAction.PICKUP_UPDATED, saved);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READ)
    public PickupRequest get(UUID id) {
        PickupRequest p = load(id);
        Scope own = scope();
        if ((own.pdp() != null && !own.pdp().equals(p.getPdpBranchId()))
                || (own.booking() != null && !own.booking().equals(p.getBookingBranchId()))) {
            throw new ResourceNotFoundException(ENTITY, id);
        }
        return p;
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READ)
    public Page<PickupRequest> search(PickupStatus status, UUID pdpBranchId, UUID bookingBranchId, String search, Pageable pageable) {
        Scope own = scope();
        String term = search == null || search.isBlank() ? null : search.trim();
        return pickups.search(requireCompany(), status, own.pdp() != null ? own.pdp() : pdpBranchId,
                own.booking() != null ? own.booking() : bookingBranchId, term, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READ)
    public Map<PickupStatus, Long> stats() {
        Map<PickupStatus, Long> out = new EnumMap<>(PickupStatus.class);
        for (PickupStatus s : PickupStatus.values()) {
            out.put(s, 0L);
        }
        for (Object[] row : pickups.countByStatus(requireCompany(), scope().pdp(), scope().booking())) {
            out.put((PickupStatus) row[0], (Long) row[1]);
        }
        return out;
    }

    @Override
    @Transactional
    @PreAuthorize(READ)
    public PickupRequest assign(UUID id, UUID pdpBranchId) {
        PickupRequest p = load(id);
        requireMayRoute(p);
        requirePartner(pdpBranchId);
        p.moveTo(PickupStatus.ASSIGNED);
        p.setPdpBranchId(pdpBranchId);
        p.setAssignedAt(Instant.now());
        p.setAcceptedAt(null);
        p.setRejectReason(null);
        PickupRequest saved = pickups.save(p);
        audit(AuditAction.PICKUP_ASSIGNED, saved);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READ)
    public java.util.Optional<UUID> suggestPartner(UUID id) {
        PickupRequest p = load(id);
        requireMayRoute(p);
        return coverage.findByPincode(p.getPincode())
                .flatMap(ref -> pincodeMappings.findBranchForPincode(ref.pincodeId()))
                .filter(b -> b.getBranchType() == BranchType.PICKUP_DELIVERY_PARTNER && b.isActive() && b.isAllowPickup())
                .map(Branch::getId);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READ)
    public java.util.List<PartnerOption> assignablePartners() {
        Scope own = scope();
        if (own.pdp() != null) {
            return java.util.List.of(); // a partner's own staff do not route pickups
        }
        return branches.findAllByCompanyIdAndStatusOrderByBranchCodeAsc(requireCompany(), com.courier.modules.company.domain.BranchStatus.ACTIVE)
                .stream().filter(b -> b.getBranchType() == BranchType.PICKUP_DELIVERY_PARTNER && b.isAllowPickup())
                .map(b -> new PartnerOption(b.getId(), b.getBranchCode(), b.getBranchName())).toList();
    }

    @Override
    @Transactional
    @PreAuthorize(READ)
    public PickupRequest accept(UUID id) {
        PickupRequest p = loadForPartner(id);
        p.moveTo(PickupStatus.ACCEPTED);
        p.setAcceptedAt(Instant.now());
        PickupRequest saved = pickups.save(p);
        audit(AuditAction.PICKUP_ACCEPTED, saved);
        return saved;
    }

    @Override
    @Transactional
    @PreAuthorize(READ)
    public PickupRequest reject(UUID id, String reason) {
        PickupRequest p = loadForPartner(id);
        String why = requireReason(reason, "A reason is required to reject a pickup.");
        p.moveTo(PickupStatus.REQUESTED);
        p.setRejectReason(why);
        p.setPdpBranchId(null);
        p.setAssignedAt(null);
        p.setAcceptedAt(null);
        PickupRequest saved = pickups.save(p);
        audit(AuditAction.PICKUP_REJECTED, saved);
        return saved;
    }

    @Override
    @Transactional
    @PreAuthorize(READ)
    public PickupRequest start(UUID id) {
        PickupRequest p = loadForPartner(id);
        p.moveTo(PickupStatus.OUT_FOR_PICKUP);
        p.setStartedAt(Instant.now());
        PickupRequest saved = pickups.save(p);
        audit(AuditAction.PICKUP_STARTED, saved);
        return saved;
    }

    @Override
    @Transactional
    @PreAuthorize(READ)
    public PickupRequest complete(UUID id, int actualParcels, BigDecimal actualWeightKg) {
        PickupRequest p = loadForPartner(id);
        if (actualParcels < 1) {
            throw new BusinessRuleException("Collected parcels must be at least 1.");
        }
        if (actualWeightKg == null || actualWeightKg.signum() <= 0) {
            throw new BusinessRuleException("Collected weight must be greater than zero.");
        }
        p.moveTo(PickupStatus.PICKED_UP);
        p.setActualParcels(actualParcels);
        p.setActualWeightKg(actualWeightKg);
        p.setPickedUpAt(Instant.now());
        PickupRequest saved = pickups.save(p);
        audit(AuditAction.PICKUP_COMPLETED, saved);
        if (saved.getShipmentId() != null) {
            // The customer's portal booking already issued the shipment: collecting the parcel finishes the pickup, no second booking.
            saved.moveTo(PickupStatus.BOOKED);
            saved = pickups.save(saved);
            earningService.recordPickup(saved.getPdpBranchId(), saved.getId(), saved.getPickupNo(), saved.getPincode(),
                    saved.getActualWeightKg(), java.time.LocalDate.now(java.time.ZoneOffset.UTC));
            audit(AuditAction.PICKUP_BOOKED, saved);
        }
        return saved;
    }

    @Override
    @Transactional
    @PreAuthorize(READ)
    public PickupRequest fail(UUID id, String reason) {
        PickupRequest p = loadForPartner(id);
        p.setFailReason(requireReason(reason, "A reason is required to mark a pickup failed."));
        p.moveTo(PickupStatus.FAILED);
        PickupRequest saved = pickups.save(p);
        audit(AuditAction.PICKUP_FAILED, saved);
        return saved;
    }

    @Override
    @Transactional
    @PreAuthorize(READ)
    public PickupRequest linkShipment(UUID id, UUID shipmentId, String shipmentNo) {
        PickupRequest p = loadForPartner(id);
        if (shipmentId == null) {
            throw new BusinessRuleException("A shipment is required.");
        }
        p.moveTo(PickupStatus.BOOKED);
        p.setShipmentId(shipmentId);
        p.setShipmentNo(shipmentNo);
        PickupRequest saved = pickups.save(p);
        // The partner is owed its pickup rate once the collected goods are booked. Accrued only.
        earningService.recordPickup(saved.getPdpBranchId(), saved.getId(), saved.getPickupNo(), saved.getPincode(),
                saved.getActualWeightKg(), java.time.LocalDate.now(java.time.ZoneOffset.UTC));
        audit(AuditAction.PICKUP_BOOKED, saved);
        return saved;
    }

    @Override
    @Transactional
    @PreAuthorize(STAFF)
    public PickupRequest cancel(UUID id, String reason) {
        PickupRequest p = load(id);
        p.setCancelReason(requireReason(reason, "A reason is required to cancel a pickup."));
        p.moveTo(PickupStatus.CANCELLED);
        PickupRequest saved = pickups.save(p);
        audit(AuditAction.PICKUP_CANCELLED, saved);
        return saved;
    }

    // ------------------------------------------------------------------ helpers

    private void apply(PickupRequest p, SaveCommand c) {
        p.setCustomerId(c.customerId());
        p.setContactName(trim(c.contactName()));
        p.setContactMobile(trim(c.contactMobile()));
        p.setAddressLine(trim(c.addressLine()));
        p.setCity(trim(c.city()));
        p.setDistrictId(c.districtId());
        p.setPincode(trim(c.pincode()));
        p.setExpectedParcels(c.expectedParcels() == null ? 1 : c.expectedParcels());
        p.setExpectedWeightKg(c.expectedWeightKg());
        p.setPreferredDate(c.preferredDate());
        p.setPreferredSlot(trim(c.preferredSlot()));
        p.setRemarks(trim(c.remarks()));
        p.applyInvariants();
    }

    private PickupRequest load(UUID id) {
        return pickups.findByIdWithinCompany(id, requireCompany())
                .orElseThrow(() -> new ResourceNotFoundException(ENTITY, id));
    }

    /** Loads a request the caller may work as the assigned partner (or as an admin). */
    private PickupRequest loadForPartner(UUID id) {
        PickupRequest p = load(id);
        UUID own = ownPartnerScope();
        if (own != null && !own.equals(p.getPdpBranchId())) {
            throw new BusinessRuleException("This pickup is not assigned to your branch.");
        }
        return p;
    }

    private Branch requirePartner(UUID branchId) {
        if (branchId == null) {
            throw new BusinessRuleException("A Pickup-Delivery Partner is required.");
        }
        // Straight from the repository: BranchService hides other branches from a branch manager, who must still be able to
        // assign a pickup of theirs to a partner.
        Branch b = branches.findByIdWithinCompany(branchId, requireCompany())
                .orElseThrow(() -> new ResourceNotFoundException("Branch", branchId));
        if (b.getBranchType() != BranchType.PICKUP_DELIVERY_PARTNER) {
            throw new BusinessRuleException("Branch %s is not a Pickup-Delivery Partner.".formatted(b.getBranchCode()));
        }
        if (!b.isActive()) {
            throw new BusinessRuleException("Partner %s is inactive.".formatted(b.getBranchCode()));
        }
        if (!b.isAllowPickup()) {
            throw new BusinessRuleException("Partner %s is not allowed to do pickups.".formatted(b.getBranchCode()));
        }
        return b;
    }

    /** The caller's own branch when they are branch-bound (not a company/platform admin), else null. */
    /** Which pickups the caller sees: all (admin / platform), the ones assigned to their partner branch, or the ones their own branch owns. */
    private record Scope(UUID pdp, UUID booking) {
    }

    private Scope scope() {
        AuthenticatedUser caller = SecurityUtils.requireCurrentUser();
        if (caller.isPlatformTier() || caller.hasRole(Roles.COMPANY_ADMIN)) {
            return new Scope(null, null);
        }
        UUID branchId = userService.getById(caller.userId()).getBranchId();
        if (branchId == null) {
            return new Scope(new UUID(0L, 0L), null); // a branch-bound caller with no branch matches nothing
        }
        BranchType type = branchService.getById(branchId).getBranchType();
        return type == BranchType.PICKUP_DELIVERY_PARTNER ? new Scope(branchId, null) : new Scope(null, branchId);
    }

    /** The company admin, or the manager of the branch the pickup belongs to, may assign it to a pickup partner. */
    private void requireMayRoute(PickupRequest p) {
        AuthenticatedUser caller = SecurityUtils.requireCurrentUser();
        if (caller.isPlatformTier() || caller.hasRole(Roles.COMPANY_ADMIN)) {
            return;
        }
        UUID own = scope().booking();
        if (own == null || !own.equals(p.getBookingBranchId())) {
            throw new com.courier.shared.exception.ForbiddenException(com.courier.shared.exception.ErrorCode.ACCESS_DENIED,
                    "Only the branch this pickup belongs to can assign it.");
        }
    }

    private UUID ownPartnerScope() {
        AuthenticatedUser caller = SecurityUtils.requireCurrentUser();
        if (caller.isPlatformTier() || caller.hasRole(Roles.COMPANY_ADMIN)) {
            return null;
        }
        UUID branchId = userService.getById(caller.userId()).getBranchId();
        // A branch-bound caller with no branch matches nothing, never everything.
        return branchId != null ? branchId : new UUID(0L, 0L);
    }

    /** PU-yyyyMMdd-NNNN, numbered per company per day. The unique key backstops a race. */
    private String nextPickupNo(UUID companyId) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant from = today.atStartOfDay().toInstant(ZoneOffset.UTC);
        long n = pickups.countCreatedBetween(companyId, from, from.plusSeconds(86_400)) + 1;
        return "PU-%tY%<tm%<td-%04d".formatted(today, n);
    }

    private void audit(AuditAction action, PickupRequest p) {
        auditService.record(action, ENTITY, p.getId(), Map.of("pickupNo", p.getPickupNo(), "status", p.getStatus().name()));
        log.info("Pickup {} -> {} by {}", p.getPickupNo(), p.getStatus(),
                SecurityUtils.getCurrentUserId().map(UUID::toString).orElse("system"));
    }

    private static String requireReason(String reason, String message) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException(message);
        }
        return reason.trim();
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private UUID requireCompany() {
        return CompanyContext.getCompanyId().orElseThrow(() -> new BusinessRuleException(
                "No company is bound to this request. Pickups belong to a company."));
    }
}
