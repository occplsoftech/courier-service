package com.courier.modules.finance.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What Finance needs to know about branches — and nothing more.
 *
 * <p>The same seam auth uses for companies ({@code CompanyDirectoryPort}, implemented by
 * {@code CompanyDirectory}). Finance owns the interface; {@code modules/company}
 * supplies the adapter. That keeps the dependency arrow pointing at this module's own
 * abstraction instead of reaching into another feature's entities and repositories, which
 * the architecture forbids: a wallet has no business holding a {@code Branch}.
 *
 * <p>Every method is company-scoped explicitly. Finance never calls this without a bound
 * company, and the adapter must never answer across companies.
 */
public interface BranchDirectoryPort {

    /**
     * Identity of one branch, flattened. Carries only what a wallet view shows: the code
     * and name that label a statement, and whether the branch is still operational.
     */
    record BranchRef(UUID branchId, UUID companyId, String branchCode, String branchName,
                     boolean active) {
    }

    /** Identity of one user, flattened to what a wallet statement shows against
     *  {@code createdBy}/{@code updatedBy}: their display name. */
    record UserRef(UUID userId, UUID companyId, String displayName) {
    }

    /** The branch, if it exists within this company. Empty for a foreign or unknown id. */
    Optional<BranchRef> findBranch(UUID branchId, UUID companyId);

    /** The user, if it exists within this company. Empty for a foreign, unknown or system id. */
    Optional<UserRef> findUser(UUID userId, UUID companyId);

    /** The branch this user is placed at ({@code users.branch_id}), if any. */
    Optional<UUID> branchOfUser(UUID userId, UUID companyId);

    /** The branch that names this user as its manager, if any. */
    Optional<UUID> branchManagedBy(UUID userId, UUID companyId);

    /** Every active branch of this company — the Finance Report's company-wide statement
     *  iterates this to build one row per branch. */
    List<BranchRef> listBranches(UUID companyId);

    /**
     * Active users holding any of the role codes: at the branch (placed there or managing it), or company-wide when
     * {@code branchId} is null. Who is told about a cash handover waiting for approval.
     */
    default List<UUID> usersWithAnyRole(UUID companyId, UUID branchId, java.util.Set<String> roleCodes) {
        return List.of();
    }
}
