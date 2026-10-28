package com.courier.modules.districtfreight.domain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What District Level Freight needs to know about branches — the "From Station" end of a
 * rate row — and nothing more.
 *
 * <p>District Level Freight owns this interface; {@code modules/company} supplies the
 * adapter. Deliberately its own port, not a reuse of Master's own
 * {@code com.courier.modules.master.domain.BranchLookupPort} or Finance's
 * {@code BranchDirectoryPort} — importing either would make this module depend on
 * another feature's abstraction to talk about branches, the same reasoning Master's own
 * javadoc gives for not reusing Finance's.
 */
public interface BranchLookupPort {

    record BranchRef(UUID branchId, String branchCode, String branchName, boolean active) {
    }

    Optional<BranchRef> findBranch(UUID branchId, UUID companyId);

    Map<UUID, BranchRef> findBranches(Collection<UUID> branchIds, UUID companyId);

    /**
     * Resolves a "From Station" cell from an Excel import against either the branch code
     * or the branch name, case-insensitively — the sheet names a station by its familiar
     * label, not its id. Code is tried first since it's the stabler identifier; branch name
     * is not unique, so a name match can legitimately return more than one branch — the
     * caller applies the row to each.
     */
    List<BranchRef> findBranchesByLabel(String label, UUID companyId);

    /**
     * The branch a caller is placed at, i.e. the account's own {@code branchId} — not a
     * "From Station" lookup. Used to scope a BRANCH_MANAGER's view to their own branch's
     * rows rather than the whole company's.
     */
    Optional<UUID> findOwnBranchId(UUID userId, UUID companyId);

    /** The branch's postal code, which the zone tariff resolves to the shipment's from zone. Empty when not set. */
    Optional<String> findPostalCode(UUID branchId, UUID companyId);
}
