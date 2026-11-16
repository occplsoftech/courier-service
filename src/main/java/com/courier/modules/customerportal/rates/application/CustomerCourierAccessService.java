package com.courier.modules.customerportal.rates.application;

import com.courier.modules.customer.application.CustomerPortalLookup;
import com.courier.modules.customerportal.rates.domain.CourierProviderAdapter;
import com.courier.modules.customerportal.rates.domain.CustomerCourierAccess;
import com.courier.modules.customerportal.rates.domain.CustomerCourierAccessRepository;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.audit.domain.AuditAction;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import com.courier.shared.security.Roles;
import com.courier.shared.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Which couriers a business customer sees in the customer portal: their own list when they have one; otherwise their home branch's list
 * (13.2); with neither, OCCPL Network only. A list is exactly those couriers (OCCPL Network included only when it is one of them). Managed
 * by the company admin, and by a branch manager for their own branch and the customers whose home branch it is.
 */
@Service
@RequiredArgsConstructor
public class CustomerCourierAccessService {

    public static final String DEFAULT_CODE = "OCCPL_NETWORK";
    private static final String COMPANY_ADMIN = "hasRole('" + Roles.COMPANY_ADMIN + "')";
    private static final String MANAGERS = "hasAnyRole('" + Roles.COMPANY_ADMIN + "', '" + Roles.BRANCH_MANAGER + "')";

    private final CustomerCourierAccessRepository repository;
    private final List<CourierProviderAdapter> adapters;
    private final CustomerPortalLookup customers;
    private final AuditService auditService;
    /** The branch level (13.2); absent in tests that build the service by hand. */
    private com.courier.modules.customerportal.rates.domain.BranchCourierAccessRepository branchAccess;
    private com.courier.modules.company.application.UserService users;
    private com.courier.modules.company.domain.BranchRepository branches;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setBranchLevel(com.courier.modules.customerportal.rates.domain.BranchCourierAccessRepository branchAccess,
                        com.courier.modules.company.application.UserService users,
                        com.courier.modules.company.domain.BranchRepository branches) {
        this.branchAccess = branchAccess;
        this.users = users;
        this.branches = branches;
    }

    /** Where the customer's couriers come from: their own list, their home branch's, or the default (OCCPL Network only). */
    public enum Source { CUSTOMER, BRANCH, DEFAULT }

    public record CourierOption(String code, String name, boolean connected, boolean enabled) {
    }

    /**
     * {@code customised}: the customer has a list of their own. Otherwise {@code source} says whether the home branch's list or the default
     * applies; {@code homeBranchId}/{@code homeBranchName} name the branch.
     */
    public record AccessView(UUID customerId, boolean customised, List<CourierOption> couriers, Source source, UUID homeBranchId,
                             String homeBranchName) {

        public AccessView(UUID customerId, boolean customised, List<CourierOption> couriers) {
            this(customerId, customised, couriers, customised ? Source.CUSTOMER : Source.DEFAULT, null, null);
        }
    }

    /** A branch's couriers: the default of every customer whose home branch it is and who has no list of their own. */
    public record BranchAccessView(UUID branchId, String branchName, boolean customised, List<CourierOption> couriers, long customers) {
    }

    /** No authorization check: called while pricing a quote for an already-resolved customer. */
    @Transactional(readOnly = true)
    public Set<String> allowedCodes(UUID companyId, UUID customerId) {
        return Set.copyOf(resolve(companyId, customerId).codes());
    }

    private record Resolved(List<String> codes, Source source, UUID homeBranchId) {
    }

    private Resolved resolve(UUID companyId, UUID customerId) {
        List<String> own = repository.findCodes(companyId, customerId);
        if (!own.isEmpty()) {
            return new Resolved(own, Source.CUSTOMER, homeBranch(companyId, customerId));
        }
        UUID branch = homeBranch(companyId, customerId);
        if (branch != null && branchAccess != null) {
            List<String> ofBranch = branchAccess.findCodes(companyId, branch);
            if (!ofBranch.isEmpty()) {
                return new Resolved(ofBranch, Source.BRANCH, branch);
            }
        }
        return new Resolved(List.of(DEFAULT_CODE), Source.DEFAULT, branch);
    }

    private UUID homeBranch(UUID companyId, UUID customerId) {
        return customers.findActiveBusinessCustomer(companyId, customerId)
                .map(com.courier.modules.customer.domain.Customer::getHomeBranchId).orElse(null);
    }

    @Transactional(readOnly = true)
    @PreAuthorize(MANAGERS)
    public AccessView view(UUID customerId) {
        UUID companyId = SecurityUtils.requireCurrentUser().companyId();
        requireCustomer(companyId, customerId);
        requireOwnCustomer(companyId, customerId);
        return build(companyId, customerId);
    }

    /** Replaces the customer's couriers. An empty list puts the customer back on the default (OCCPL Network only). */
    @Transactional
    @PreAuthorize(MANAGERS)
    public AccessView save(UUID customerId, List<String> providerCodes) {
        UUID companyId = SecurityUtils.requireCurrentUser().companyId();
        requireCustomer(companyId, customerId);
        requireOwnCustomer(companyId, customerId);
        Set<String> wanted = known(providerCodes);
        repository.deleteForCustomer(companyId, customerId);
        repository.flush();
        wanted.forEach(code -> repository.save(new CustomerCourierAccess(customerId, code)));
        auditService.record(AuditAction.CUSTOMER_COURIER_ACCESS_CHANGED, "Customer", customerId,
                Map.of("couriers", wanted.isEmpty() ? "(default: OCCPL_NETWORK)" : String.join(",", wanted)));
        return build(companyId, customerId);
    }

    // ------------------------------------------------------------------ branch level (13.2)

    @Transactional(readOnly = true)
    @PreAuthorize(MANAGERS)
    public BranchAccessView viewBranch(UUID branchId) {
        UUID companyId = SecurityUtils.requireCurrentUser().companyId();
        String name = requireBranch(companyId, branchId);
        return buildBranch(companyId, branchId, name);
    }

    /** Replaces a branch's couriers. An empty list: its customers without a list of their own go back to OCCPL Network only. */
    @Transactional
    @PreAuthorize(MANAGERS)
    public BranchAccessView saveBranch(UUID branchId, List<String> providerCodes) {
        UUID companyId = SecurityUtils.requireCurrentUser().companyId();
        String name = requireBranch(companyId, branchId);
        Set<String> wanted = known(providerCodes);
        branchAccess.deleteForBranch(companyId, branchId);
        branchAccess.flush();
        wanted.forEach(code -> branchAccess.save(new com.courier.modules.customerportal.rates.domain.BranchCourierAccess(branchId, code)));
        auditService.record(AuditAction.CUSTOMER_COURIER_ACCESS_CHANGED, "Branch", branchId,
                Map.of("couriers", wanted.isEmpty() ? "(none: OCCPL_NETWORK for its customers)" : String.join(",", wanted)));
        return buildBranch(companyId, branchId, name);
    }

    private BranchAccessView buildBranch(UUID companyId, UUID branchId, String name) {
        List<String> stored = branchAccess.findCodes(companyId, branchId);
        Set<String> enabled = stored.isEmpty() ? Set.of(DEFAULT_CODE) : Set.copyOf(stored);
        return new BranchAccessView(branchId, name, !stored.isEmpty(), options(companyId, enabled), customers.countBusinessCustomersOfBranch(companyId, branchId));
    }

    /** The branch's name, when it is an active branch of the company that the caller may manage (a branch manager: their own only). */
    private String requireBranch(UUID companyId, UUID branchId) {
        if (branchAccess == null || branches == null) {
            throw new BusinessRuleException("Branch courier access is not available.");
        }
        com.courier.modules.company.domain.Branch b = branches.findByIdWithinCompany(branchId, companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Branch", branchId));
        UUID own = ownBranch();
        if (own != null && !own.equals(branchId)) {
            throw new org.springframework.security.access.AccessDeniedException("You can manage your own branch's couriers only.");
        }
        return b.getBranchName();
    }

    /** A branch manager's own branch; null for a company admin (who manages every branch). */
    private UUID ownBranch() {
        com.courier.shared.security.AuthenticatedUser caller = SecurityUtils.requireCurrentUser();
        if (caller.hasRole(Roles.COMPANY_ADMIN) || users == null) {
            return null;
        }
        UUID own = users.getById(caller.userId()).getBranchId();
        if (own == null) {
            throw new org.springframework.security.access.AccessDeniedException("You are not assigned to a branch.");
        }
        return own;
    }

    /** A branch manager may change only the customers whose home branch is theirs. */
    private void requireOwnCustomer(UUID companyId, UUID customerId) {
        UUID own = ownBranch();
        if (own != null && !own.equals(homeBranch(companyId, customerId))) {
            throw new org.springframework.security.access.AccessDeniedException("This customer belongs to another branch.");
        }
    }

    private Set<String> known(List<String> providerCodes) {
        Set<String> known = new LinkedHashSet<>();
        adapters.forEach(a -> known.add(a.code()));
        Set<String> wanted = new LinkedHashSet<>();
        for (String raw : providerCodes == null ? List.<String>of() : providerCodes) {
            String code = raw == null ? "" : raw.trim().toUpperCase();
            if (!known.contains(code)) {
                throw new BusinessRuleException("Unknown courier " + raw + ".");
            }
            wanted.add(code);
        }
        return wanted;
    }

    private List<CourierOption> options(UUID companyId, Set<String> enabled) {
        return adapters.stream()
                .map(a -> new CourierOption(a.code(), a.displayName(), a.configured(companyId), enabled.contains(a.code())))
                .toList();
    }

    private AccessView build(UUID companyId, UUID customerId) {
        Resolved r = resolve(companyId, customerId);
        String branchName = r.homeBranchId() == null || branches == null ? null
                : branches.findByIdWithinCompany(r.homeBranchId(), companyId).map(com.courier.modules.company.domain.Branch::getBranchName)
                        .orElse(null);
        return new AccessView(customerId, r.source() == Source.CUSTOMER, options(companyId, Set.copyOf(r.codes())), r.source(),
                r.homeBranchId(), branchName);
    }

    private void requireCustomer(UUID companyId, UUID customerId) {
        customers.findActiveBusinessCustomer(companyId, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Business customer", customerId));
    }
}
