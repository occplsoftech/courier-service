package com.courier.modules.customer.domain;

import java.util.Optional;
import java.util.UUID;

/** Where a customer's home branch comes from (13.2): the branch serving a pincode, and whether a branch is the company's. */
public interface HomeBranchLookupPort {

    /** The active branch mapped to this pincode in the company, if any. */
    Optional<UUID> branchServingPincode(UUID companyId, UUID pincodeId);

    /** True when the branch exists, is active and belongs to the company. */
    boolean isActiveBranch(UUID companyId, UUID branchId);
}
