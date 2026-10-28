package com.courier.modules.company.infrastructure;

import com.courier.modules.company.domain.Branch;
import com.courier.modules.company.domain.BranchCriteria;
import com.courier.modules.company.domain.BranchRepository;
import com.courier.modules.company.domain.BranchSpecifications;
import com.courier.modules.company.domain.CompanyUserRepository;
import com.courier.modules.company.domain.User;
import com.courier.modules.districtfreight.domain.BranchLookupPort;
import com.courier.shared.company.CompanyContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Answers District Level Freight's questions about branches (the "From Station" end of a
 * rate row), backed by the {@code branches} table.
 *
 * <p>A separate bean from {@link CompanyMasterBranchDirectory} (serves Master's Route) and
 * {@link CompanyBranchDirectory} (serves Finance), because each consumer owns its own
 * interface — same reasoning both of those already document. District Level Freight never
 * imports {@code Branch} or {@code BranchRepository} directly.
 */
@Component
@RequiredArgsConstructor
public class CompanyDistrictFreightBranchDirectory implements BranchLookupPort {

    private final BranchRepository branchRepository;
    private final CompanyUserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<BranchRef> findBranch(UUID branchId, UUID companyId) {
        if (branchId == null || companyId == null) {
            return Optional.empty();
        }
        return branchRepository.findByIdWithinCompany(branchId, companyId).map(this::toRef);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, BranchRef> findBranches(Collection<UUID> branchIds, UUID companyId) {
        if (branchIds == null || branchIds.isEmpty() || companyId == null) {
            return Map.of();
        }
        Set<UUID> wanted = branchIds.stream().filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (wanted.isEmpty()) {
            return Map.of();
        }
        BranchCriteria criteria = BranchCriteria.none().withCompanyId(companyId).withBranchIds(wanted);
        return branchRepository.findAll(BranchSpecifications.matching(criteria)).stream()
                .collect(Collectors.toMap(Branch::getId, this::toRef));
    }

    @Override
    @Transactional(readOnly = true)
    public List<BranchRef> findBranchesByLabel(String label, UUID companyId) {
        if (label == null || label.isBlank() || companyId == null) {
            return List.of();
        }
        String trimmed = label.trim();
        // findByBranchCodeIgnoreCase/findAllByBranchNameIgnoreCase carry no explicit company
        // predicate — they rely on the Hibernate filter, so bind it explicitly rather than
        // trust the ambient one.
        return CompanyContext.runAs(companyId, () -> {
            Optional<Branch> byCode = branchRepository.findByBranchCodeIgnoreCase(trimmed);
            if (byCode.isPresent()) {
                return byCode.map(this::toRef).stream().toList();
            }
            // Branch name is not unique (only branch code is) — a label naming a branch by
            // name applies to every branch that carries it.
            return branchRepository.findAllByBranchNameIgnoreCase(trimmed).stream()
                    .map(this::toRef).toList();
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findOwnBranchId(UUID userId, UUID companyId) {
        if (userId == null || companyId == null) {
            return Optional.empty();
        }
        return userRepository.findByIdWithinCompany(userId, companyId).map(User::getBranchId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findPostalCode(UUID branchId, UUID companyId) {
        if (branchId == null || companyId == null) {
            return Optional.empty();
        }
        return branchRepository.findByIdWithinCompany(branchId, companyId)
                .map(Branch::getPostalCode)
                .filter(code -> !code.isBlank())
                .map(String::trim);
    }

    private BranchRef toRef(Branch branch) {
        return new BranchRef(branch.getId(), branch.getBranchCode(), branch.getBranchName(),
                branch.isActive());
    }
}
