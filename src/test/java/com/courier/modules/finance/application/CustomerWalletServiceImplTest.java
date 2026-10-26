package com.courier.modules.finance.application;

import com.courier.modules.customer.application.CustomerService;
import com.courier.modules.finance.application.event.WalletEvent;
import com.courier.modules.finance.domain.Wallet;
import com.courier.modules.finance.domain.WalletOwnerType;
import com.courier.modules.finance.domain.WalletRepository;
import com.courier.modules.finance.domain.WalletTransaction;
import com.courier.modules.finance.domain.WalletTransactionRepository;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.audit.domain.AuditAction;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Opening and reading customer wallets, with the repositories mocked. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CustomerWalletServiceImplTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();

    @Mock private WalletRepository walletRepository;
    @Mock private WalletTransactionRepository transactionRepository;
    @Mock private CustomerService customerService;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private CustomerWalletServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CustomerWalletServiceImpl(walletRepository, transactionRepository, customerService,
                auditService, eventPublisher);
        CompanyContext.setCompanyId(COMPANY);
        when(walletRepository.save(any(Wallet.class))).thenAnswer(i -> i.getArgument(0));
        when(walletRepository.existsByWalletNumber(anyString())).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        CompanyContext.clear();
    }

    @Test
    @DisplayName("a customer without a wallet gets an empty, active one")
    void opensWallet() {
        when(walletRepository.findByCustomerIdWithinCompany(CUSTOMER, COMPANY)).thenReturn(Optional.empty());

        Wallet w = service.provisionForCustomer(COMPANY, CUSTOMER);

        assertThat(w.getOwnerType()).isEqualTo(WalletOwnerType.CUSTOMER);
        assertThat(w.getCustomerId()).isEqualTo(CUSTOMER);
        assertThat(w.getBranchId()).isNull();
        assertThat(w.getWalletNumber()).startsWith("WLT");
        assertThat(w.getAvailableBalance()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("opening is audited and announced once")
    void auditedAndAnnounced() {
        when(walletRepository.findByCustomerIdWithinCompany(CUSTOMER, COMPANY)).thenReturn(Optional.empty());

        Wallet w = service.provisionForCustomer(COMPANY, CUSTOMER);

        verify(auditService).record(eq(AuditAction.WALLET_CREATED), eq("Wallet"), any(), any());
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOfSatisfying(WalletEvent.CustomerWalletCreated.class, e -> {
            assertThat(e.customerId()).isEqualTo(CUSTOMER);
            assertThat(e.companyId()).isEqualTo(COMPANY);
            assertThat(e.walletNumber()).isEqualTo(w.getWalletNumber());
        });
    }

    @Test
    @DisplayName("an existing wallet is returned as it is, and nothing is created")
    void idempotent() {
        Wallet existing = Wallet.forCustomer(CUSTOMER, "WLT2610EXISTING1");
        when(walletRepository.findByCustomerIdWithinCompany(CUSTOMER, COMPANY)).thenReturn(Optional.of(existing));

        Wallet w = service.provisionForCustomer(COMPANY, CUSTOMER);

        assertThat(w).isSameAs(existing);
        verify(walletRepository, never()).save(any());
        verify(auditService, never()).record(any(), any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("a company and a customer are both required")
    void requiresIds() {
        assertThatThrownBy(() -> service.provisionForCustomer(null, CUSTOMER))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.provisionForCustomer(COMPANY, null))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("a staff read of a customer that is not in the company opens nothing")
    void staffReadOfForeignCustomer() {
        when(customerService.getById(CUSTOMER)).thenThrow(new ResourceNotFoundException("Customer", CUSTOMER));

        assertThatThrownBy(() -> service.getForCustomer(CUSTOMER)).isInstanceOf(ResourceNotFoundException.class);

        verify(walletRepository, never()).save(any());
    }

    @Test
    @DisplayName("a staff read without a company bound is refused")
    void staffReadNeedsCompany() {
        CompanyContext.clear();

        assertThatThrownBy(() -> service.getForCustomer(CUSTOMER)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("a ledger is only read for a wallet of that company")
    void ledgerNeedsWalletInCompany() {
        UUID walletId = UUID.randomUUID();
        when(walletRepository.findByIdWithinCompany(walletId, COMPANY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ledgerOfWallet(COMPANY, walletId, 0, 20))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(transactionRepository, never()).pageByWalletWithinCompany(any(), any(), any());
    }

    @Test
    @DisplayName("the ledger is newest first and the page size is capped")
    void ledgerPaging() {
        Wallet w = Wallet.forCustomer(CUSTOMER, "WLT2610EXISTING1");
        UUID walletId = UUID.randomUUID();
        when(walletRepository.findByIdWithinCompany(walletId, COMPANY)).thenReturn(Optional.of(w));
        when(transactionRepository.pageByWalletWithinCompany(eq(walletId), eq(COMPANY), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.<WalletTransaction>of()));

        Page<WalletTransaction> page = service.ledgerOfWallet(COMPANY, walletId, -3, 5000);

        assertThat(page).isEmpty();
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionRepository).pageByWalletWithinCompany(eq(walletId), eq(COMPANY), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(CustomerWalletServiceImpl.MAX_PAGE_SIZE);
        assertThat(pageable.getValue().getSort().getOrderFor("createdAt").getDirection())
                .isEqualTo(Sort.Direction.DESC);
    }
}
