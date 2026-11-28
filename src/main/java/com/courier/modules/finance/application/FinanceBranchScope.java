package com.courier.modules.finance.application;

import com.courier.modules.company.domain.DefaultRoleCatalog;
import com.courier.modules.finance.domain.BranchDirectoryPort;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import com.courier.shared.security.AuthenticatedUser;
import com.courier.shared.security.Roles;
import com.courier.shared.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Who may see or record what on the User Performance Report and cash handovers.
 *
 * <ul>
 *   <li><b>Company-wide</b> (any branch): COMPANY_ADMIN, FINANCE_USER.</li>
 *   <li><b>Team</b> (every user of their own branch): those, plus BRANCH_MANAGER, HUB_MANAGER, ACCOUNTS, VIEWER.</li>
 *   <li>Everyone else (booking / delivery operators, drivers): <b>only their own row</b> ("My Performance").</li>
 *   <li><b>Hand over cash</b> (submit with proof): any company user, for their own cash.</li>
 *   <li><b>Approve / reject a handover</b>: COMPANY_ADMIN, BRANCH_MANAGER, ACCOUNTS — the people cash is handed to —
 *       never their own.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class FinanceBranchScope {

    /** Spring expression for the endpoints: signed-in company users, never a platform operator. */
    public static final String COMPANY_USERS_ONLY =
            "isAuthenticated() and !hasRole('" + Roles.SUPER_ADMIN + "') and !hasRole('" + Roles.PLATFORM_ADMIN + "')";

    private final BranchDirectoryPort branchDirectory;

    public UUID requireCompany() {
        return CompanyContext.getCompanyId().orElseThrow(() -> new BusinessRuleException(
                "No company is bound to this request; this report belongs to a company's branch."));
    }

    public AuthenticatedUser caller() {
        return SecurityUtils.requireCurrentUser();
    }

    public boolean companyWide(AuthenticatedUser caller) {
        return caller.hasRole(Roles.COMPANY_ADMIN) || caller.hasRole(DefaultRoleCatalog.FINANCE_USER);
    }

    public boolean seesTeam(AuthenticatedUser caller) {
        return companyWide(caller) || caller.hasRole(Roles.BRANCH_MANAGER) || caller.hasRole(Roles.HUB_MANAGER)
                || caller.hasRole(DefaultRoleCatalog.ACCOUNTS) || caller.hasRole(Roles.VIEWER);
    }

    public boolean canApproveHandover(AuthenticatedUser caller) {
        return caller.hasRole(Roles.COMPANY_ADMIN) || caller.hasRole(Roles.BRANCH_MANAGER)
                || caller.hasRole(DefaultRoleCatalog.ACCOUNTS);
    }

    /**
     * The branch the caller may act on. A company-wide caller names one (defaulting to their own); anyone else
     * always gets their own branch, and naming another one is answered as "not found", never "forbidden".
     */
    public UUID resolveBranch(UUID requested, UUID companyId, AuthenticatedUser caller) {
        Optional<UUID> own = ownBranch(caller.userId(), companyId);
        if (companyWide(caller)) {
            UUID branchId = requested != null ? requested : own.orElseThrow(() -> new BusinessRuleException(
                    "Choose a branch: you are not assigned to one."));
            branchDirectory.findBranch(branchId, companyId)
                    .orElseThrow(() -> new ResourceNotFoundException("Branch", branchId));
            return branchId;
        }
        UUID branchId = own.orElseThrow(() -> new BusinessRuleException(
                "You are not assigned to a branch, so there is nothing to report on."));
        if (requested != null && !requested.equals(branchId)) {
            throw new ResourceNotFoundException("Branch", requested);
        }
        return branchId;
    }

    public Optional<UUID> ownBranch(UUID userId, UUID companyId) {
        Optional<UUID> placement = branchDirectory.branchOfUser(userId, companyId);
        return placement.isPresent() ? placement : branchDirectory.branchManagedBy(userId, companyId);
    }

    public String displayName(UUID userId, UUID companyId) {
        return branchDirectory.findUser(userId, companyId)
                .map(BranchDirectoryPort.UserRef::displayName)
                .filter(n -> !n.isBlank())
                .orElse("Unknown user");
    }

    public boolean userExists(UUID userId, UUID companyId) {
        return branchDirectory.findUser(userId, companyId).isPresent();
    }
}
