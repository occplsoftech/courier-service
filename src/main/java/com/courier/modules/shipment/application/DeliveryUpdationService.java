package com.courier.modules.shipment.application;

import com.courier.modules.company.domain.BranchRepository;
import com.courier.modules.company.domain.CompanyUserRepository;
import com.courier.modules.company.domain.Branch;
import com.courier.modules.company.domain.User;
import com.courier.modules.master.domain.PackageType;
import com.courier.modules.master.domain.PackageTypeRepository;
import com.courier.modules.shipment.domain.DeliveryAssignment;
import com.courier.modules.shipment.domain.DeliveryAssignmentRepository;
import com.courier.modules.shipment.domain.DeliveryAssignmentStatus;
import com.courier.modules.shipment.domain.Shipment;
import com.courier.modules.shipment.domain.ShipmentAsset;
import com.courier.modules.shipment.domain.ShipmentAssetRepository;
import com.courier.modules.shipment.domain.ShipmentAssetType;
import com.courier.modules.shipment.domain.ShipmentRepository;
import com.courier.modules.shipment.domain.ShipmentStatus;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.security.AuthenticatedUser;
import com.courier.shared.security.Roles;
import com.courier.shared.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Delivery Updation's two lists, as in the previous software: shipments out for delivery waiting for their outcome
 * ("Pending for Delivery"), and deliveries already closed with how they were proved ("Delivered"). A company admin
 * sees every branch (or one, by {@code branchId}); anyone else their own delivery branch.
 */
@Service
@RequiredArgsConstructor
public class DeliveryUpdationService {

    public enum Tab { PENDING, DELIVERED }

    public record Row(UUID shipmentId, String trackingNumber, String shipmentNumber, LocalDate bookingDate,
                      String packageType, String bookingBranch, String destination, String consignee, ShipmentStatus status,
                      UUID deliveryUserId, String deliveryPerson, String drsNumber, String forwardingNumber,
                      Instant outForDeliveryAt, String assignedBy, Instant deliveredAt, String proofType,
                      String proofLabel, String receivedBy, String relation, String remarks, String updatedBy,
                      String photoUrl) {
    }

    private final DeliveryAssignmentRepository assignments;
    private final ShipmentRepository shipments;
    private final ShipmentAssetRepository assets;
    // Names come straight from the company's own records, not from UserService / BranchService / PackageTypeService:
    // those check whether the caller may *see* the record (a branch manager may not see another branch), and a refusal
    // inside this read-only transaction marks it rollback-only, so the whole list failed with a 500 for branch users.
    private final CompanyUserRepository users;
    private final BranchRepository branches;
    private final PackageTypeRepository packageTypes;

    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public Page<Row> list(Tab tab, String search, UUID branchId, int page, int size) {
        UUID companyId = CompanyContext.getCompanyId().orElseThrow(() -> new BusinessRuleException(
                "No company is bound to this request."));
        UUID branch = scopeBranch(branchId, companyId);
        String q = search == null || search.isBlank() ? null : "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
        boolean delivered = tab == Tab.DELIVERED;
        Sort sort = delivered ? Sort.by(Sort.Order.desc("deliveredAt")) : Sort.by(Sort.Order.desc("assignedAt"));
        Page<DeliveryAssignment> found = assignments.searchForUpdation(companyId,
                delivered ? DeliveryAssignmentStatus.DELIVERED : DeliveryAssignmentStatus.ASSIGNED, branch,
                delivered ? null : ShipmentStatus.OUT_FOR_DELIVERY, q,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100), sort));

        List<UUID> ids = found.getContent().stream().map(DeliveryAssignment::getShipmentId).toList();
        Map<UUID, Shipment> byId = ids.isEmpty() ? Map.of() : shipments.findAllByCompanyIdAndIdIn(companyId, ids).stream()
                .collect(Collectors.toMap(Shipment::getId, Function.identity()));
        Map<UUID, String> photos = new HashMap<>();
        if (delivered && !ids.isEmpty()) {
            for (ShipmentAsset a : assets.findByShipmentIdInAndAssetTypeWithinCompany(ids, ShipmentAssetType.POD, companyId)) {
                if ("PHOTO".equalsIgnoreCase(a.getKind())) photos.putIfAbsent(a.getShipmentId(), a.getAssetUrl());
            }
        }
        Names names = new Names(companyId);
        return found.map(a -> {
            Shipment s = byId.get(a.getShipmentId());
            return new Row(a.getShipmentId(), s == null ? null : s.getTrackingNumber(), s == null ? null : s.getShipmentNumber(),
                    s == null ? null : s.getBookingDate(), s == null ? null : names.packageType(s.getPackageTypeId()),
                    s == null ? null : names.branch(s.getBookingBranchId()), s == null ? null : s.getToCity(),
                    s == null ? null : s.getReceiverName(), s == null ? null : s.getStatus(),
                    a.getDeliveryUserId(), names.user(a.getDeliveryUserId()), a.getDrsNumber(),
                    s == null ? null : s.getForwardAwb(), a.getAssignedAt(), names.user(a.getCreatedBy()),
                    a.getDeliveredAt(), a.getProofType() == null ? null : a.getProofType().name(),
                    a.getProofType() == null ? null : a.getProofType().getLabel(), a.getReceiverName(),
                    a.getReceiverRelation(), a.getDeliveryRemarks(), delivered ? names.user(a.getUpdatedBy()) : null,
                    photos.get(a.getShipmentId()));
        });
    }

    /** Company admins and the platform see any branch; everyone else only their own. */
    private UUID scopeBranch(UUID requested, UUID companyId) {
        AuthenticatedUser caller = SecurityUtils.requireCurrentUser();
        if (caller.isPlatformTier() || caller.hasRole(Roles.COMPANY_ADMIN)) {
            return requested;
        }
        UUID own = users.findByIdWithinCompany(caller.userId(), companyId).map(User::getBranchId).orElse(null);
        if (own == null) {
            throw new BusinessRuleException("You are not assigned to a branch, so there are no deliveries to update.");
        }
        return own;
    }

    /** Per-call memo of id → display name. */
    private final class Names {
        private final UUID companyId;
        private final Map<UUID, String> people = new HashMap<>();
        private final Map<UUID, String> offices = new HashMap<>();
        private final Map<UUID, String> packages = new HashMap<>();

        Names(UUID companyId) {
            this.companyId = companyId;
        }

        String user(UUID id) {
            return id == null ? null : people.computeIfAbsent(id, k -> safe(() -> {
                User u = users.findByIdWithinCompany(k, companyId).orElse(null);
                if (u == null) return null;
                String full = ((u.getFirstName() == null ? "" : u.getFirstName()) + " "
                        + (u.getLastName() == null ? "" : u.getLastName())).trim();
                return !full.isEmpty() ? full : u.getDisplayName();
            }));
        }

        String branch(UUID id) {
            return id == null ? null : offices.computeIfAbsent(id, k -> safe(() -> {
                return branches.findByIdWithinCompany(k, companyId).map(Branch::getBranchName).orElse(null);
            }));
        }

        String packageType(UUID id) {
            return id == null ? null : packages.computeIfAbsent(id, k -> safe(() -> packageTypes.findByIdWithinCompany(k, companyId).map(PackageType::getName).orElse(null)));
        }
    }

    private static String safe(Supplier<String> s) {
        try {
            return s.get();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
