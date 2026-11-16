package com.courier.modules.company.infrastructure;

import com.courier.modules.company.domain.Branch;
import com.courier.modules.company.domain.BranchPincodeMappingRepository;
import com.courier.modules.company.domain.BranchRepository;
import com.courier.modules.customer.domain.HomeBranchLookupPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** A business customer's home branch (13.2), from the company's branch to pincode mapping. No authorization: a system lookup. */
@Component
@RequiredArgsConstructor
public class CompanyHomeBranchLookup implements HomeBranchLookupPort {

    private final BranchPincodeMappingRepository mappings;
    private final BranchRepository branches;

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> branchServingPincode(UUID companyId, UUID pincodeId) {
        if (companyId == null || pincodeId == null) {
            return Optional.empty();
        }
        return mappings.findByCompanyIdAndPincodeId(companyId, pincodeId)
                .map(m -> m.getBranchId())
                .filter(id -> isActiveBranch(companyId, id));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActiveBranch(UUID companyId, UUID branchId) {
        return branchId != null && branches.findByIdWithinCompany(branchId, companyId).filter(Branch::isActive).isPresent();
    }
}
