package com.courier.modules.company.infrastructure;

import com.courier.modules.company.domain.Branch;
import com.courier.modules.company.domain.BranchRepository;
import com.courier.modules.company.domain.BranchStatus;
import com.courier.modules.company.domain.CompanyUserRepository;
import com.courier.modules.company.domain.User;
import com.courier.modules.finance.domain.BranchDirectoryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Answers Finance's questions about branches, backed by the {@code branches} and
 * {@code users} tables.
 *
 * <p>The same arrangement as {@link CompanyDirectory}: the consuming module owns the
 * interface, this module supplies the adapter. Finance therefore never imports
 * {@code Branch}, {@code BranchRepository} or the company's user rows — it gets a flat
 * {@link BranchDirectoryPort.BranchRef} and nothing it could accidentally mutate.
 *
 * <p>Every lookup carries the company explicitly rather than trusting the ambient
 * {@code CompanyContext}, so a caller that forgot to bind one gets no rows instead of every
 * company's.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompanyBranchDirectory implements BranchDirectoryPort {

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
    public Optional<UserRef> findUser(UUID userId, UUID companyId) {
        if (userId == null || companyId == null) {
            return Optional.empty();
        }
        return userRepository.findByIdWithinCompany(userId, companyId)
                .map(u -> new UserRef(u.getId(), u.getCompanyId(), u.effectiveDisplayName()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> branchOfUser(UUID userId, UUID companyId) {
        if (userId == null || companyId == null) {
            return Optional.empty();
        }
        return userRepository.findByIdWithinCompany(userId, companyId).map(User::getBranchId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> branchManagedBy(UUID userId, UUID companyId) {
        if (userId == null || companyId == null) {
            return Optional.empty();
        }
        // A user manages at most one branch, but the column has no unique key, so take the
        // first deterministically rather than blowing up on an unexpected second row.
        return branchRepository.findFirstByCompanyIdAndManagerId(companyId, userId,
                        PageRequest.of(0, 1))
                .stream().findFirst().map(Branch::getId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BranchRef> listBranches(UUID companyId) {
        if (companyId == null) {
            return List.of();
        }
        return branchRepository.findAllByCompanyIdAndStatusOrderByBranchCodeAsc(companyId, BranchStatus.ACTIVE)
                .stream().map(this::toRef).toList();
    }

    private BranchRef toRef(Branch branch) {
        return new BranchRef(branch.getId(), branch.getCompanyId(), branch.getBranchCode(),
                branch.getBranchName(), branch.isActive());
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> usersWithAnyRole(UUID companyId, UUID branchId, java.util.Set<String> roleCodes) {
        if (companyId == null || roleCodes == null || roleCodes.isEmpty()) {
            return List.of();
        }
        return userRepository.findActiveIdsWithAnyRole(com.courier.shared.domain.TimeOrderedUuid.toBytes(companyId),
                        branchId == null ? null : com.courier.shared.domain.TimeOrderedUuid.toBytes(branchId), roleCodes)
                .stream().map(com.courier.shared.domain.TimeOrderedUuid::fromBytes).toList();
    }
}
