package com.courier.modules.company.application;

import com.courier.modules.auth.application.UserProvisioningService;
import com.courier.modules.auth.application.UserProvisioningService.ProvisionedBranchUser;
import com.courier.modules.auth.domain.Role;
import com.courier.modules.company.application.command.CreateBranchCommand;
import com.courier.modules.company.application.command.UpdateBranchCommand;
import com.courier.modules.company.application.event.BranchEvent;
import com.courier.modules.company.application.geocoding.BranchGeocoder;
import com.courier.modules.company.application.geocoding.GeocodingPort;
import com.courier.modules.company.domain.Branch;
import com.courier.modules.company.domain.BranchCriteria;
import com.courier.modules.company.domain.BranchRepository;
import com.courier.modules.company.domain.BranchSpecifications;
import com.courier.modules.company.domain.BranchStatus;
import com.courier.modules.company.domain.BranchType;
import com.courier.modules.company.domain.CompanyRepository;
import com.courier.modules.company.domain.CompanyUserRepository;
import com.courier.modules.company.domain.User;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.audit.domain.AuditAction;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.DuplicateResourceException;
import com.courier.shared.exception.ResourceNotFoundException;
import com.courier.shared.security.AuthenticatedUser;
import com.courier.shared.security.Roles;
import com.courier.shared.security.SecurityUtils;
import com.courier.shared.company.CompanyContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Branch use cases.
 *
 * <p>Per-method {@code @PreAuthorize} gates by role tier; the finer "only the branch you
 * manage / are assigned to" is enforced in code, because it depends on the caller's own
 * user row (their {@code branchId}) and on which branch names them as manager.
 *
 * <p>Company isolation is the project's two layers: the Hibernate filter, plus
 * {@code findByIdWithinCompany} on every single-row load. A foreign branch id returns 404.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BranchServiceImpl implements BranchService {

    private static final String ENTITY = "Branch";
    private static final String COMPANY_ADMIN_ONLY = "hasRole('" + Roles.COMPANY_ADMIN + "')";
    private static final String ADMIN_OR_BRANCH_MANAGER =
            "hasAnyRole('" + Roles.COMPANY_ADMIN + "', '" + Roles.BRANCH_MANAGER + "')";
    private static final String READERS = "isAuthenticated()";

    /** How many suffixed addresses are tried before a derived login is given up on. */
    private static final int EMAIL_SUFFIX_ATTEMPTS = 20;

    private final BranchRepository repository;
    private final CompanyUserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final UserProvisioningService userProvisioningService;
    private final BranchRoleProvisioningService branchRoleProvisioningService;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final GeocodingPort geocodingPort;

    // ------------------------------------------------------------------- create

    @Override
    @Transactional
    @PreAuthorize(COMPANY_ADMIN_ONLY)
    public BranchCreation create(CreateBranchCommand command) {
        UUID companyId = requireCompany();
        String code = Branch.normaliseCode(command.branchCode());
        String name = command.branchName() == null ? null : command.branchName().trim();

        requireCodeAvailable(companyId, code, null);
        if (command.managerId() != null) {
            requireCompanyUser(companyId, command.managerId());
        }

        Branch branch = Branch.builder()
                .branchCode(code)
                .branchName(name)
                .branchType(command.branchType())
                .status(BranchStatus.ACTIVE)
                .email(command.email())
                .mobile(command.mobile())
                .alternateMobile(command.alternateMobile())
                .managerId(command.managerId())
                .addressLine1(command.addressLine1())
                .addressLine2(command.addressLine2())
                .country(command.country())
                .state(command.state())
                .city(command.city())
                .district(command.district())
                .taluka(command.taluka())
                .postalCode(command.postalCode())
                .latitude(command.latitude())
                .longitude(command.longitude())
                .openingTime(command.openingTime())
                .closingTime(command.closingTime())
                .workingDays(command.workingDays())
                .allowBooking(orTrue(command.allowBooking()))
                .allowDelivery(orTrue(command.allowDelivery()))
                .allowPickup(orTrue(command.allowPickup()))
                .allowManifest(orTrue(command.allowManifest()))
                .allowCashCollection(orTrue(command.allowCashCollection()))
                .allowWallet(Boolean.TRUE.equals(command.allowWallet()))
                .instantCommission(orTrue(command.instantCommission()))
                .remarks(command.remarks())
                .gstNumber(command.gstNumber())
                .panNumber(command.panNumber())
                .gstPercentage(orDefault(command.gstPercentage(), DEFAULT_GST_PERCENTAGE))
                .commissionOnOtherCharges(orDefault(command.commissionOnOtherCharges(),
                        DEFAULT_COMMISSION_ON_OTHER_CHARGES))
                .commissionOnBasicFreight(orDefault(command.commissionOnBasicFreight(),
                        DEFAULT_COMMISSION_ON_BASIC_FREIGHT))
                .companyServiceChargePercentage(orDefault(command.companyServiceChargePercentage(),
                        DEFAULT_COMPANY_SERVICE_CHARGE_PERCENTAGE))
                .drsChargePerQty(orDefault(command.drsChargePerQty(), DEFAULT_DRS_CHARGE_PER_QTY))
                .deliveryCommissionRatePerKg(orDefault(command.deliveryCommissionRatePerKg(),
                        DEFAULT_DELIVERY_COMMISSION_RATE_PER_KG))
                .deliveryCommissionMinWeightKg(orDefault(command.deliveryCommissionMinWeightKg(),
                        DEFAULT_DELIVERY_COMMISSION_MIN_WEIGHT_KG))
                .build();

        branch.applyInvariants();
        if (branch.getLatitude() == null || branch.getLongitude() == null) {
            geocodeInto(branch);
        }
        Branch saved = repository.save(branch);

        ProvisionedBranchUser user = provisionUser(companyId, saved, command.branchUser());
        placeAtBranch(companyId, user.userId(), saved.getId());

        // The permissioned role behind that account. Auth gave it the JWT authority; this
        // gives it the company role the roles screen manages, creating the company's
        // BRANCH_MANAGER role if it has none. Inside this transaction on purpose — a branch
        // whose manager holds no role is the half-provisioned state the transaction exists
        // to prevent. The wallet is the deliberate exception (decision 47).
        BranchRoleProvisioningService.BranchManagerRoleAssignment roleAssignment =
                branchRoleProvisioningService.ensureBranchManagerRole(companyId, user.userId());

        // A hub's account also needs HUB_MANAGER, or it signs in to a nav with no Hub
        // Operations in it (the menu and routes are gated on that role).
        if (saved.getBranchType() == BranchType.HUB) {
            branchRoleProvisioningService.ensureHubManagerRole(companyId, user.userId());
        }

        // A branch created without a manager gets the account that was just made for it.
        // A branch created *with* one keeps that person; the new account is staff, not the
        // manager, and overwriting an explicit choice would be a surprise.
        boolean assignedAsManager = saved.getManagerId() == null;
        if (assignedAsManager) {
            saved.assignManager(user.userId());
            saved = repository.save(saved);
        }

        log.info("Branch {} ({}) created in company {} by {} with user {} holding {}",
                saved.getBranchCode(), saved.getId(), companyId, currentActor(), user.email(),
                roleAssignment.role().getRoleCode());
        auditService.record(AuditAction.BRANCH_CREATED, ENTITY, saved.getId(),
                Map.of("branchCode", saved.getBranchCode(),
                        "branchType", saved.getBranchType().name(),
                        "branchUserId", user.userId().toString(),
                        "branchUserEmail", user.email(),
                        "assignedAsManager", assignedAsManager,
                        "branchManagerRoleId", roleAssignment.role().getId().toString(),
                        "branchManagerRoleCreated", roleAssignment.roleCreated()));
        eventPublisher.publishEvent(new BranchEvent.BranchCreated(
                saved.getId(), companyId, saved.getBranchCode(), saved.getBranchType(), Instant.now()));

        return new BranchCreation(saved, user.userId(), user.email(),
                user.temporaryPassword(), assignedAsManager,
                roleAssignment.role().getId(), roleAssignment.role().getRoleCode());
    }

    /**
     * Fills in latitude/longitude from whatever address fields the administrator gave
     * (taluka/city/district/state/postal code) when they left both blank. Best-effort: a
     * geocoding miss leaves the branch exactly as it would have been before this existed —
     * it never blocks or fails the create.
     */
    private void geocodeInto(Branch branch) {
        BranchGeocoder.fillIfMissing(geocodingPort, branch);
    }

    // --------------------------------------------------------- the branch's own user

    /**
     * Creates the branch's login account through auth's provisioning service — the same
     * seam a company's first administrator goes through. This module never writes a
     * password or touches the {@code users} table to create a row.
     *
     * <p>An address the administrator typed is used as typed: if it is taken, the whole
     * create fails with a 409 naming it, because silently signing them in as
     * {@code latur-2@…} is worse than the error. A <em>derived</em> address carries no such
     * intent, so a collision there is suffixed instead.
     */
    private ProvisionedBranchUser provisionUser(UUID companyId, Branch branch,
                                                CreateBranchCommand.NewBranchUser requested) {
        CreateBranchCommand.NewBranchUser spec = requested == null
                ? new CreateBranchCommand.NewBranchUser(null, null, null, null, null)
                : requested;

        boolean explicitEmail = isNotBlank(spec.email());
        String email = explicitEmail
                ? spec.email().trim().toLowerCase()
                : availableDerivedEmail(companyId, branch);

        String firstName = isNotBlank(spec.firstName()) ? spec.firstName().trim() : branch.getBranchName();
        String lastName = isNotBlank(spec.lastName()) ? spec.lastName().trim() : "Branch";
        String mobile = isNotBlank(spec.mobile()) ? spec.mobile().trim() : branch.getMobile();

        return userProvisioningService.provisionBranchUser(
                new UserProvisioningService.NewBranchUserCommand(
                        companyId, email, firstName, lastName, mobile, spec.password(),
                        Set.of(Role.BRANCH_MANAGER)));
    }

    /**
     * {@code <branch-code>@<company-code>.local} — {@code .local} because the address is
     * invented and must never be mistaken for a mailbox that can receive verification or
     * password-reset mail. Suffixed on collision so reusing a branch code after a soft
     * delete still yields a usable login.
     */
    private String availableDerivedEmail(UUID companyId, Branch branch) {
        String localPart = slug(branch.getBranchCode());
        String domain = companyRepository.findByCompanyId(companyId)
                .map(c -> slug(c.getCompanyCode()))
                .orElse("company") + ".local";

        String candidate = localPart + "@" + domain;
        for (int suffix = 2; userRepository.findByEmail(candidate).isPresent(); suffix++) {
            if (suffix > EMAIL_SUFFIX_ATTEMPTS) {
                throw new BusinessRuleException(
                        "Could not derive a free login address for branch " + branch.getBranchCode()
                                + "; supply one in branchUser.email.");
            }
            candidate = localPart + "-" + suffix + "@" + domain;
        }
        return candidate;
    }

    /**
     * Sets {@code users.branch_id}. The row was created a moment ago through auth's entity;
     * it is loaded here through this module's own entity, which is the one that maps the
     * column — the shared-kernel arrangement described in {@code MEMORY/modules/user.md}.
     */
    private void placeAtBranch(UUID companyId, UUID userId, UUID branchId) {
        User user = userRepository.findByIdWithinCompany(userId, companyId)
                .orElseThrow(() -> new IllegalStateException(
                        "Branch user " + userId + " was provisioned but cannot be read back"));
        user.assignBranch(branchId);
        userRepository.save(user);
    }

    private static String slug(String value) {
        if (value == null || value.isBlank()) {
            return "branch";
        }
        String slug = value.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        return slug.isBlank() ? "branch" : slug;
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    // ------------------------------------------------------------------- update

    @Override
    @Transactional
    @PreAuthorize(ADMIN_OR_BRANCH_MANAGER)
    public Branch update(UUID id, UpdateBranchCommand command) {
        UUID companyId = requireCompany();
        Branch branch = loadOrThrow(id, companyId);
        requireManageable(branch);
        requireCurrentVersion(branch, command.expectedVersion());

        String code = Branch.normaliseCode(command.branchCode());
        String name = command.branchName() == null ? null : command.branchName().trim();
        requireCodeAvailable(companyId, code, id);

        Map<String, Object> before = snapshot(branch);

        branch.setBranchCode(code);
        branch.setBranchName(name);
        branch.setBranchType(command.branchType());
        branch.setEmail(command.email());
        branch.setMobile(command.mobile());
        branch.setAlternateMobile(command.alternateMobile());
        branch.setAddressLine1(command.addressLine1());
        branch.setAddressLine2(command.addressLine2());
        branch.setCountry(command.country());
        branch.setState(command.state());
        branch.setCity(command.city());
        branch.setDistrict(command.district());
        branch.setTaluka(command.taluka());
        branch.setPostalCode(command.postalCode());
        branch.setLatitude(command.latitude());
        branch.setLongitude(command.longitude());
        branch.setOpeningTime(command.openingTime());
        branch.setClosingTime(command.closingTime());
        branch.setWorkingDays(command.workingDays());
        branch.setAllowBooking(orTrue(command.allowBooking()));
        branch.setAllowDelivery(orTrue(command.allowDelivery()));
        branch.setAllowPickup(orTrue(command.allowPickup()));
        branch.setAllowManifest(orTrue(command.allowManifest()));
        branch.setAllowCashCollection(orTrue(command.allowCashCollection()));
        branch.setAllowWallet(Boolean.TRUE.equals(command.allowWallet()));
        branch.setInstantCommission(orTrue(command.instantCommission()));
        branch.setRemarks(command.remarks());
        branch.setGstNumber(command.gstNumber());
        branch.setPanNumber(command.panNumber());
        branch.setGstPercentage(command.gstPercentage());
        branch.setCommissionOnOtherCharges(command.commissionOnOtherCharges());
        branch.setCommissionOnBasicFreight(command.commissionOnBasicFreight());
        branch.setCompanyServiceChargePercentage(command.companyServiceChargePercentage());
        branch.setDrsChargePerQty(command.drsChargePerQty());
        branch.setDeliveryCommissionRatePerKg(command.deliveryCommissionRatePerKg());
        branch.setDeliveryCommissionMinWeightKg(command.deliveryCommissionMinWeightKg());

        // Same fallback as create(): only when the caller left both blank. An explicit
        // pair (including one that clears a previous geocode) is a deliberate choice and
        // is never second-guessed.
        if (branch.getLatitude() == null || branch.getLongitude() == null) {
            geocodeInto(branch);
        }

        branch.applyInvariants();
        Branch saved = repository.save(branch);

        Map<String, Object> changes = changeDetails(before, snapshot(saved));
        log.info("Branch {} updated in company {} by {} ({} field(s))",
                saved.getBranchCode(), companyId, currentActor(), changes.size());
        auditService.record(AuditAction.BRANCH_UPDATED, ENTITY, saved.getId(), changes);
        eventPublisher.publishEvent(new BranchEvent.BranchUpdated(
                saved.getId(), companyId, changes.keySet(), Instant.now()));

        return saved;
    }

    // -------------------------------------------------------------------- reads

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READERS)
    public Branch getById(UUID id) {
        AuthenticatedUser caller = SecurityUtils.requireCurrentUser();
        if (caller.isSuperAdmin() && CompanyContext.getCompanyId().isEmpty()) {
            return repository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException(ENTITY, id));
        }
        Branch branch = loadOrThrow(id, CompanyContext.requireCompanyId());
        requireVisible(caller, branch);
        return branch;
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READERS)
    public BigDecimal drsChargePerQtyOf(UUID branchId) {
        return repository.findByIdWithinCompany(branchId, CompanyContext.requireCompanyId())
                .map(Branch::getDrsChargePerQty)
                .orElse(BigDecimal.ZERO);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READERS)
    public boolean instantCommissionOf(UUID branchId) {
        return repository.findByIdWithinCompany(branchId, CompanyContext.requireCompanyId())
                .map(Branch::isInstantCommission)
                .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READERS)
    public Page<Branch> search(BranchCriteria criteria, Pageable pageable) {
        BranchCriteria safe = criteria == null ? BranchCriteria.none() : criteria;
        AuthenticatedUser caller = SecurityUtils.requireCurrentUser();

        BranchCriteria effective = CompanyContext.getCompanyId().map(safe::withCompanyId).orElse(safe);

        // A non-admin sees only the branches they may: the one they are placed at, plus
        // any they manage. Pinned here, never taken from the request.
        if (!caller.isSuperAdmin() && !caller.hasRole(Roles.COMPANY_ADMIN)) {
            effective = effective.withBranchIds(visibleBranchIds(caller));
        }

        return repository.findAll(BranchSpecifications.matching(effective), pageable);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READERS)
    public List<Branch> listActiveDirectory() {
        UUID companyId = requireCompany();
        return repository.findAllByCompanyIdAndStatusOrderByBranchCodeAsc(companyId, BranchStatus.ACTIVE);
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    @Transactional
    @PreAuthorize(COMPANY_ADMIN_ONLY)
    public Branch activate(UUID id) {
        UUID companyId = requireCompany();
        Branch branch = loadOrThrow(id, companyId);
        if (branch.isActive()) {
            return branch;
        }
        branch.activate();
        Branch saved = repository.save(branch);
        auditService.record(AuditAction.BRANCH_ACTIVATED, ENTITY, saved.getId(),
                Map.of("branchCode", saved.getBranchCode()));
        eventPublisher.publishEvent(new BranchEvent.BranchActivated(saved.getId(), companyId, Instant.now()));
        return saved;
    }

    @Override
    @Transactional
    @PreAuthorize(COMPANY_ADMIN_ONLY)
    public Branch deactivate(UUID id) {
        UUID companyId = requireCompany();
        Branch branch = loadOrThrow(id, companyId);
        if (!branch.isActive()) {
            return branch;
        }
        branch.deactivate();
        Branch saved = repository.save(branch);
        auditService.record(AuditAction.BRANCH_DEACTIVATED, ENTITY, saved.getId(),
                Map.of("branchCode", saved.getBranchCode()));
        eventPublisher.publishEvent(new BranchEvent.BranchDeactivated(saved.getId(), companyId, Instant.now()));
        return saved;
    }

    @Override
    @Transactional
    @PreAuthorize(COMPANY_ADMIN_ONLY)
    public void delete(UUID id) {
        UUID companyId = requireCompany();
        Branch branch = loadOrThrow(id, companyId);

        // Soft delete only, and deactivate alongside so a restored row is not silently
        // operational. Users placed here keep their branchId — reassigning them is a
        // deliberate User Management action, not a side effect of deleting the branch.
        branch.deactivate();
        branch.softDelete(SecurityUtils.getCurrentUserId().orElse(null));
        repository.save(branch);

        log.warn("Branch {} ({}) soft deleted in company {} by {}",
                branch.getBranchCode(), branch.getId(), companyId, currentActor());
        auditService.record(AuditAction.DELETED, ENTITY, branch.getId(),
                Map.of("branchCode", branch.getBranchCode()));
    }

    // ------------------------------------------------------------------- manager

    @Override
    @Transactional
    @PreAuthorize(COMPANY_ADMIN_ONLY)
    public Branch assignManager(UUID id, UUID managerId) {
        UUID companyId = requireCompany();
        Branch branch = loadOrThrow(id, companyId);

        if (managerId != null) {
            requireCompanyUser(companyId, managerId);
        }
        UUID previous = branch.getManagerId();
        branch.assignManager(managerId);
        Branch saved = repository.save(branch);

        // Granting that user the BRANCH_MANAGER role, or placing them at this branch, is
        // separate — done through User Management. This endpoint only names the manager.
        log.info("Branch {} manager set to {} (was {}) by {}",
                saved.getBranchCode(), managerId, previous, currentActor());
        auditService.record(AuditAction.BRANCH_MANAGER_ASSIGNED, ENTITY, saved.getId(),
                Map.of("branchCode", saved.getBranchCode(),
                        "managerId", String.valueOf(managerId),
                        "previousManagerId", String.valueOf(previous)));
        eventPublisher.publishEvent(new BranchEvent.ManagerAssigned(
                saved.getId(), companyId, managerId, previous, Instant.now()));

        return saved;
    }

    @Override
    @Transactional
    @PreAuthorize(COMPANY_ADMIN_ONLY)
    public Branch assignHub(UUID id, UUID hubId) {
        UUID companyId = requireCompany();
        Branch branch = loadOrThrow(id, companyId);
        if (hubId != null) {
            if (hubId.equals(id)) {
                throw new BusinessRuleException("A branch cannot be its own hub.");
            }
            Branch hub = loadOrThrow(hubId, companyId);
            if (hub.getBranchType() != BranchType.HUB) {
                throw new BusinessRuleException("The selected branch is not a hub.");
            }
            if (hub.getStatus() != BranchStatus.ACTIVE) {
                throw new BusinessRuleException("The selected hub is not active.");
            }
        }
        UUID previous = branch.getAssignedHubId();
        branch.assignHub(hubId);
        Branch saved = repository.save(branch);
        log.info("Branch {} hub set to {} (was {}) by {}",
                saved.getBranchCode(), hubId, previous, currentActor());
        auditService.record(AuditAction.BRANCH_HUB_ASSIGNED, ENTITY, saved.getId(),
                Map.of("branchCode", saved.getBranchCode(),
                        "hubId", String.valueOf(hubId),
                        "previousHubId", String.valueOf(previous)));
        return saved;
    }

    @Override
    @Transactional
    @PreAuthorize(COMPANY_ADMIN_ONLY)
    public Branch setCourierPickupLocation(UUID id, String name) {
        UUID companyId = requireCompany();
        Branch branch = loadOrThrow(id, companyId);
        String clean = name == null || name.isBlank() ? null : name.trim();
        if (clean != null && clean.length() > 120) {
            throw new BusinessRuleException("The pickup location name can be at most 120 characters.");
        }
        String previous = branch.getCourierPickupLocation();
        branch.setCourierPickupLocation(clean);
        Branch saved = repository.save(branch);
        auditService.record(AuditAction.BRANCH_UPDATED, ENTITY, saved.getId(),
                Map.of("branchCode", saved.getBranchCode(), "courierPickupLocation", String.valueOf(clean),
                        "previous", String.valueOf(previous)));
        return saved;
    }

    // --------------------------------------------------------------- assign users

    @Override
    @Transactional
    @PreAuthorize(ADMIN_OR_BRANCH_MANAGER)
    public AssignUsersResult assignUsers(UUID id, Collection<UUID> userIds) {
        UUID companyId = requireCompany();
        Branch branch = loadOrThrow(id, companyId);
        requireManageable(branch);

        Set<UUID> requested = userIds == null ? Set.of()
                : userIds.stream().filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        if (requested.isEmpty()) {
            throw new BusinessRuleException("No users were supplied.");
        }

        List<User> found = userRepository.findAllByIdInWithinCompany(requested, companyId);
        Set<UUID> foundIds = found.stream().map(User::getId).collect(Collectors.toSet());

        List<UUID> assigned = new ArrayList<>();
        List<UUID> skipped = new ArrayList<>();
        for (User user : found) {
            if (id.equals(user.getBranchId())) {
                skipped.add(user.getId());
                continue;
            }
            user.assignBranch(id);
            userRepository.save(user);
            assigned.add(user.getId());
        }
        // Ids that did not resolve to a user of this company.
        List<UUID> rejected = requested.stream().filter(uid -> !foundIds.contains(uid)).toList();

        log.info("Branch {}: assigned {} user(s), skipped {}, rejected {} by {}",
                branch.getBranchCode(), assigned.size(), skipped.size(), rejected.size(), currentActor());
        auditService.record(AuditAction.BRANCH_USERS_ASSIGNED, ENTITY, branch.getId(),
                Map.of("branchCode", branch.getBranchCode(),
                        "assigned", assigned.stream().map(UUID::toString).toList(),
                        "rejected", rejected.stream().map(UUID::toString).toList()));

        return new AssignUsersResult(assigned, skipped, rejected);
    }

    // -------------------------------------------------------------------- helpers

    private Branch loadOrThrow(UUID id, UUID companyId) {
        return repository.findByIdWithinCompany(id, companyId)
                .orElseThrow(() -> new ResourceNotFoundException(ENTITY, id));
    }

    /**
     * A branch manager may only act on the branch they manage. A company admin may act on
     * any. 403 rather than 404 here: the caller reached a branch of their own company that
     * they simply may not manage — that is a permission answer, not a hidden resource.
     */
    private void requireManageable(Branch branch) {
        AuthenticatedUser caller = SecurityUtils.requireCurrentUser();
        if (caller.hasRole(Roles.COMPANY_ADMIN)) {
            return;
        }
        if (caller.hasRole(Roles.BRANCH_MANAGER)
                && caller.userId().equals(branch.getManagerId())) {
            return;
        }
        throw new com.courier.shared.exception.ForbiddenException(
                "You may only manage the branch you are assigned to.");
    }

    /** Read visibility for a non-admin: the branch they manage or are placed at. */
    private void requireVisible(AuthenticatedUser caller, Branch branch) {
        if (caller.isSuperAdmin() || caller.hasRole(Roles.COMPANY_ADMIN)) {
            return;
        }
        if (!visibleBranchIds(caller).contains(branch.getId())) {
            throw new ResourceNotFoundException(ENTITY, branch.getId());
        }
    }

    /** The branches a non-admin caller may see: the one they manage, and their placement. */
    private Set<UUID> visibleBranchIds(AuthenticatedUser caller) {
        Set<UUID> ids = new HashSet<>();
        userRepository.findByIdWithinCompany(caller.userId(), CompanyContext.requireCompanyId())
                .map(User::getBranchId)
                .ifPresent(branchId -> {
                    if (branchId != null) {
                        ids.add(branchId);
                    }
                });
        if (caller.hasRole(Roles.BRANCH_MANAGER)) {
            // Also whichever branch names them as manager, even if their placement differs.
            managedBranchId(caller).ifPresent(ids::add);
        }
        return ids;
    }

    private java.util.Optional<UUID> managedBranchId(AuthenticatedUser caller) {
        BranchCriteria managed = new BranchCriteria(CompanyContext.requireCompanyId(), null, null,
                caller.userId(), null, null, null, null, null, null, null, null);
        return repository.findAll(BranchSpecifications.matching(managed),
                        org.springframework.data.domain.PageRequest.of(0, 1))
                .stream().findFirst().map(Branch::getId);
    }

    private void requireCompanyUser(UUID companyId, UUID userId) {
        userRepository.findByIdWithinCompany(userId, companyId)
                .orElseThrow(() -> new BusinessRuleException(
                        "The manager must be a user of this company."));
    }

    private void requireCodeAvailable(UUID companyId, String code, UUID excludeId) {
        if (repository.isCodeTaken(companyId, code, excludeId)) {
            throw new DuplicateResourceException(ENTITY, "branchCode", code);
        }
    }

    private UUID requireCompany() {
        return CompanyContext.getCompanyId().orElseThrow(() -> new BusinessRuleException(
                "No company is bound to this request. Branches belong to a company, so this "
                        + "operation must be performed by a user of that company."));
    }

    private void requireCurrentVersion(Branch branch, Long expectedVersion) {
        if (expectedVersion == null) {
            return;
        }
        if (!Objects.equals(branch.getVersion(), expectedVersion)) {
            throw new ObjectOptimisticLockingFailureException(Branch.class, branch.getId());
        }
    }

    private static boolean orTrue(Boolean value) {
        return value == null || value;
    }

    private static final java.math.BigDecimal DEFAULT_GST_PERCENTAGE = new java.math.BigDecimal("18.00");
    private static final java.math.BigDecimal DEFAULT_COMMISSION_ON_OTHER_CHARGES = new java.math.BigDecimal("20.00");
    private static final java.math.BigDecimal DEFAULT_COMMISSION_ON_BASIC_FREIGHT = new java.math.BigDecimal("10.00");
    private static final java.math.BigDecimal DEFAULT_COMPANY_SERVICE_CHARGE_PERCENTAGE = new java.math.BigDecimal("10.00");
    private static final java.math.BigDecimal DEFAULT_DRS_CHARGE_PER_QTY = new java.math.BigDecimal("2.00");
    private static final java.math.BigDecimal DEFAULT_DELIVERY_COMMISSION_RATE_PER_KG = new java.math.BigDecimal("1.50");
    private static final java.math.BigDecimal DEFAULT_DELIVERY_COMMISSION_MIN_WEIGHT_KG = new java.math.BigDecimal("10.00");

    private static java.math.BigDecimal orDefault(java.math.BigDecimal value, java.math.BigDecimal fallback) {
        return value == null ? fallback : value;
    }

    private Map<String, Object> snapshot(Branch b) {
        Map<String, Object> v = new java.util.LinkedHashMap<>();
        v.put("branchName", b.getBranchName());
        v.put("branchType", b.getBranchType() == null ? null : b.getBranchType().name());
        v.put("email", b.getEmail());
        v.put("mobile", b.getMobile());
        v.put("alternateMobile", b.getAlternateMobile());
        v.put("addressLine1", b.getAddressLine1());
        v.put("addressLine2", b.getAddressLine2());
        v.put("country", b.getCountry());
        v.put("state", b.getState());
        v.put("city", b.getCity());
        v.put("district", b.getDistrict());
        v.put("taluka", b.getTaluka());
        v.put("postalCode", b.getPostalCode());
        v.put("latitude", String.valueOf(b.getLatitude()));
        v.put("longitude", String.valueOf(b.getLongitude()));
        v.put("openingTime", String.valueOf(b.getOpeningTime()));
        v.put("closingTime", String.valueOf(b.getClosingTime()));
        v.put("workingDays", b.getWorkingDays());
        v.put("allowBooking", b.isAllowBooking());
        v.put("allowDelivery", b.isAllowDelivery());
        v.put("allowPickup", b.isAllowPickup());
        v.put("allowManifest", b.isAllowManifest());
        v.put("allowCashCollection", b.isAllowCashCollection());
        v.put("allowWallet", b.isAllowWallet());
        v.put("instantCommission", b.isInstantCommission());
        v.put("remarks", b.getRemarks());
        v.put("gstNumber", b.getGstNumber());
        v.put("panNumber", b.getPanNumber());
        v.put("gstPercentage", String.valueOf(b.getGstPercentage()));
        v.put("commissionOnOtherCharges", String.valueOf(b.getCommissionOnOtherCharges()));
        v.put("commissionOnBasicFreight", String.valueOf(b.getCommissionOnBasicFreight()));
        v.put("companyServiceChargePercentage", String.valueOf(b.getCompanyServiceChargePercentage()));
        v.put("drsChargePerQty", String.valueOf(b.getDrsChargePerQty()));
        v.put("deliveryCommissionRatePerKg", String.valueOf(b.getDeliveryCommissionRatePerKg()));
        v.put("deliveryCommissionMinWeightKg", String.valueOf(b.getDeliveryCommissionMinWeightKg()));
        return v;
    }

    private Map<String, Object> changeDetails(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> changes = new java.util.LinkedHashMap<>();
        before.forEach((field, oldValue) -> {
            Object newValue = after.get(field);
            if (!Objects.equals(oldValue, newValue)) {
                Map<String, Object> pair = new HashMap<>();
                pair.put("from", oldValue);
                pair.put("to", newValue);
                changes.put(field, pair);
            }
        });
        return changes;
    }

    private String currentActor() {
        return SecurityUtils.getCurrentUser()
                .map(user -> user.email() == null ? user.userId().toString() : user.email())
                .orElse("system");
    }
}
