package com.courier.modules.company.infrastructure;

import com.courier.modules.company.domain.Branch;
import com.courier.modules.company.domain.BranchRepository;
import com.courier.modules.shipment.domain.BranchLookupPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Answers Shipment Booking's questions about branches, backed by the {@code branches}
 * table.
 *
 * <p>A separate bean from {@link CompanyBranchDirectory} (Finance) and
 * {@link CompanyMasterBranchDirectory} (Master), because each consumer owns its own
 * interface — the same "small duplication over a worse dependency arrow" reasoning
 * {@code master.domain.BranchLookupPort}'s own class comment gives for not reusing
 * Finance's port.
 *
 * <p>The company is taken from the argument, never from the ambient {@code CompanyContext}:
 * a caller that forgot to bind one gets an empty result rather than another company's branch.
 */
@Component
@RequiredArgsConstructor
public class CompanyShipmentBranchDirectory implements BranchLookupPort {

    private final BranchRepository branchRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<BranchRef> findBranch(UUID branchId, UUID companyId) {
        if (branchId == null || companyId == null) {
            return Optional.empty();
        }
        return branchRepository.findByIdWithinCompany(branchId, companyId).map(this::toRef);
    }

    private BranchRef toRef(Branch branch) {
        return new BranchRef(branch.getId(), branch.getBranchCode(), branch.getBranchName(),
                branch.isActive());
    }

    @Override
    public Optional<CommissionRates> findCommissionRates(UUID branchId, UUID companyId) {
        if (branchId == null) {
            return Optional.empty();
        }
        return branchRepository.findByIdWithinCompany(branchId, companyId).map(b -> new CommissionRates(
                b.getCompanyServiceChargePercentage(), b.getCommissionOnBasicFreight(), b.getCommissionOnOtherCharges()));
    }
}
