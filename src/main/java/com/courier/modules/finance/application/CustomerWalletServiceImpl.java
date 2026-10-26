package com.courier.modules.finance.application;

import com.courier.modules.customer.application.CustomerService;
import com.courier.modules.company.domain.DefaultRoleCatalog;
import com.courier.modules.finance.application.event.WalletEvent;
import com.courier.modules.finance.domain.Wallet;
import com.courier.modules.finance.domain.WalletNumberGenerator;
import com.courier.modules.finance.domain.WalletRepository;
import com.courier.modules.finance.domain.WalletTransaction;
import com.courier.modules.finance.domain.WalletTransactionRepository;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.audit.domain.AuditAction;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import com.courier.shared.security.Roles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Opens and reads customer wallets. See {@link CustomerWalletService}.
 *
 * <p>The wallet row is created here and nowhere else; no method in this class changes a
 * balance. Moving money on a customer wallet goes through the same ledger-first paths as a
 * branch wallet (later parts), so the balance and the ledger cannot disagree.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerWalletServiceImpl implements CustomerWalletService {

    private static final String ENTITY = "Wallet";
    static final int MAX_PAGE_SIZE = 100;
    private static final int NUMBER_ATTEMPTS = 5;

    /** Same reach as the company-wide branch wallet reads. */
    private static final String FINANCE_READERS = "hasAnyRole('" + Roles.COMPANY_ADMIN
            + "', '" + DefaultRoleCatalog.FINANCE_USER + "')";

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository transactionRepository;
    private final CustomerService customerService;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public Wallet provisionForCustomer(UUID companyId, UUID customerId) {
        if (companyId == null || customerId == null) {
            throw new BusinessRuleException("A customer wallet needs a company and a customer.");
        }
        return walletRepository.findByCustomerIdWithinCompany(customerId, companyId)
                .orElseGet(() -> open(companyId, customerId));
    }

    @Override
    @Transactional
    @PreAuthorize(FINANCE_READERS)
    public Wallet getForCustomer(UUID customerId) {
        UUID companyId = requireCompany();
        // 404 for a customer of another company (getById is company-scoped), so a staff user
        // learns nothing about customers that are not theirs.
        customerService.getById(customerId);
        return provisionForCustomer(companyId, customerId);
    }

    @Override
    @Transactional
    @PreAuthorize(FINANCE_READERS)
    public Page<WalletTransaction> ledgerForCustomer(UUID customerId, int page, int size) {
        Wallet wallet = getForCustomer(customerId);
        return ledgerOfWallet(wallet.getCompanyId(), wallet.getId(), page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<WalletTransaction> ledgerOfWallet(UUID companyId, UUID walletId, int page, int size) {
        // The wallet must be a wallet of that company: a ledger is never read by wallet id alone.
        walletRepository.findByIdWithinCompany(walletId, companyId)
                .orElseThrow(() -> new ResourceNotFoundException(ENTITY, walletId));
        PageRequest request = PageRequest.of(Math.max(page, 0), clampSize(size),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return transactionRepository.pageByWalletWithinCompany(walletId, companyId, request);
    }

    // ------------------------------------------------------------------ internals

    private Wallet open(UUID companyId, UUID customerId) {
        Wallet saved = walletRepository.save(Wallet.forCustomer(customerId, nextWalletNumber()));

        // A concurrent first access could race us. uk_wallets_company_customer is the arbiter:
        // the loser gets a DataIntegrityViolationException and the retry finds the winner's
        // wallet. At most once in a customer's lifetime.
        log.info("Wallet {} created for customer {} in company {}",
                saved.getWalletNumber(), customerId, companyId);
        auditService.record(AuditAction.WALLET_CREATED, ENTITY, saved.getId(),
                Map.of("walletNumber", saved.getWalletNumber(), "customerId", customerId.toString()));
        eventPublisher.publishEvent(new WalletEvent.CustomerWalletCreated(
                saved.getId(), companyId, customerId, saved.getWalletNumber(), Instant.now()));
        return saved;
    }

    private String nextWalletNumber() {
        for (int attempt = 0; attempt < NUMBER_ATTEMPTS; attempt++) {
            String candidate = WalletNumberGenerator.walletNumber();
            if (!walletRepository.existsByWalletNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Could not generate a unique wallet number in " + NUMBER_ATTEMPTS + " attempts");
    }

    private static int clampSize(int size) {
        return size <= 0 ? 20 : Math.min(size, MAX_PAGE_SIZE);
    }

    private static UUID requireCompany() {
        return CompanyContext.getCompanyId().orElseThrow(() -> new BusinessRuleException(
                "No company is bound to this request. A customer wallet belongs to a customer of a "
                        + "company, so this must be done by a user of that company."));
    }
}
