package com.courier.modules.finance.application;

import com.courier.modules.finance.application.WalletTopupRequestService.UploadProofImageCommand;
import com.courier.modules.finance.domain.BranchDirectoryPort;
import com.courier.modules.finance.domain.ReferenceType;
import com.courier.modules.finance.domain.SubTransactionType;
import com.courier.modules.finance.domain.TopupRequestStatus;
import com.courier.modules.finance.domain.Wallet;
import com.courier.modules.finance.domain.WalletTopupRequest;
import com.courier.modules.finance.domain.WalletTopupRequestRepository;
import com.courier.modules.finance.domain.WalletTransaction;
import com.courier.modules.shipment.application.storage.FileStoragePort;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.audit.domain.AuditAction;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.security.AuthenticatedUser;
import com.courier.shared.security.Roles;
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
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Top-up requests raised by a customer, and how an admin's decision reaches the right wallet. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WalletTopupRequestServiceImplTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID BRANCH = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    @Mock private WalletTopupRequestRepository repository;
    @Mock private WalletService walletService;
    @Mock private BranchDirectoryPort branchDirectory;
    @Mock private AuditService auditService;
    @Mock private FileStoragePort fileStoragePort;
    @Mock private CustomerWalletService customerWallets;

    private WalletTopupRequestServiceImpl service;
    private Wallet wallet;

    @BeforeEach
    void setUp() {
        service = new WalletTopupRequestServiceImpl(repository, walletService, branchDirectory, auditService,
                fileStoragePort, customerWallets);
        CompanyContext.setCompanyId(TENANT);
        signedInAs(Roles.CUSTOMER);
        wallet = Wallet.forCustomer(CUSTOMER, "WLT2610CUSTOMER1");
        wallet.setCompanyId(TENANT);
        when(customerWallets.provisionForCustomer(TENANT, CUSTOMER)).thenReturn(wallet);
        when(repository.save(any(WalletTopupRequest.class))).thenAnswer(i -> {
            WalletTopupRequest r = i.getArgument(0);
            if (r.getId() == null) {
                r.setId(UUID.randomUUID());
            }
            return r;
        });
    }

    @AfterEach
    void tearDown() {
        CompanyContext.clear();
        SecurityContextHolder.clearContext();
    }

    private void signedInAs(String... roles) {
        AuthenticatedUser principal = new AuthenticatedUser(USER, TENANT, "user@legacy.test", Set.of(roles), "jti");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.authorities()));
    }

    // ----------------------------------------------------------------- customer raises

    @Test
    @DisplayName("a customer's request is pending, against their wallet and no branch")
    void raisesRequest() {
        WalletTopupRequest r = service.createForCustomer(CUSTOMER, new BigDecimal("5000"), " UTR 123 ",
                " https://files/proof.png ");

        assertThat(r.getCustomerId()).isEqualTo(CUSTOMER);
        assertThat(r.getBranchId()).isNull();
        assertThat(r.getWalletId()).isEqualTo(wallet.getId());
        assertThat(r.getStatus()).isEqualTo(TopupRequestStatus.PENDING);
        assertThat(r.getRequestedAmount()).isEqualByComparingTo("5000");
        assertThat(r.getRemarks()).isEqualTo("UTR 123");
        assertThat(r.getProofImageUrl()).isEqualTo("https://files/proof.png");
        assertThat(r.getRequestedBy()).isEqualTo(USER);
        verify(auditService).record(eq(AuditAction.WALLET_TOPUP_REQUESTED), any(), any(), any());
    }

    @Test
    @DisplayName("raising a request moves no money")
    void movesNothing() {
        service.createForCustomer(CUSTOMER, new BigDecimal("5000"), null, "https://files/proof.png");

        verify(walletService, never()).creditForCustomer(any());
        verify(walletService, never()).credit(any());
    }

    @Test
    @DisplayName("proof of payment is mandatory")
    void proofRequired() {
        assertThatThrownBy(() -> service.createForCustomer(CUSTOMER, new BigDecimal("5000"), null, " "))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("proof");
        assertThatThrownBy(() -> service.createForCustomer(CUSTOMER, new BigDecimal("5000"), null, null))
                .isInstanceOf(BusinessRuleException.class);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("the amount must be greater than zero")
    void amountRequired() {
        assertThatThrownBy(() -> service.createForCustomer(CUSTOMER, BigDecimal.ZERO, null, "https://files/p.png"))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.createForCustomer(CUSTOMER, null, null, "https://files/p.png"))
                .isInstanceOf(BusinessRuleException.class);

        verify(repository, never()).save(any());
    }

    // ------------------------------------------------------------------ customer reads

    @Test
    @DisplayName("a customer lists only their own requests, with the page size capped")
    void listsOwnRequests() {
        when(repository.pageByCustomerWithinCompany(eq(CUSTOMER), eq(TENANT), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.searchForCustomer(CUSTOMER, -2, 9999);

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).pageByCustomerWithinCompany(eq(CUSTOMER), eq(TENANT), page.capture());
        assertThat(page.getValue().getPageNumber()).isZero();
        assertThat(page.getValue().getPageSize()).isEqualTo(100);
        assertThat(page.getValue().getSort().getOrderFor("createdAt").isDescending()).isTrue();
    }

    // --------------------------------------------------------------------- proof upload

    @Test
    @DisplayName("only image files are accepted as proof")
    void proofMustBeAnImage() {
        assertThatThrownBy(() -> service.uploadProofImageForCustomer(
                new UploadProofImageCommand(new byte[]{1}, "statement.pdf", "application/pdf")))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("images");

        verify(fileStoragePort, never()).upload(any());
    }

    @Test
    @DisplayName("an image is stored under the company and its URL returned")
    void storesProof() {
        when(fileStoragePort.upload(any())).thenReturn(
                new FileStoragePort.StoredFile("https://files/proof.png", "key"));

        String url = service.uploadProofImageForCustomer(
                new UploadProofImageCommand(new byte[]{1}, "slip.PNG", "image/png"));

        assertThat(url).isEqualTo("https://files/proof.png");
        ArgumentCaptor<FileStoragePort.UploadRequest> upload = ArgumentCaptor.forClass(FileStoragePort.UploadRequest.class);
        verify(fileStoragePort).upload(upload.capture());
        // The service passes the storage key as the "filename" of the upload request.
        assertThat(upload.getValue().filename()).startsWith(TENANT + "/photo-").endsWith(".png");
        assertThat(upload.getValue().keyPrefix()).isEqualTo("topup-proof");
    }

    // ------------------------------------------------------------------------ decisions

    private WalletTopupRequest pending(UUID customerId, UUID branchId) {
        WalletTopupRequest r = WalletTopupRequest.builder().walletId(wallet.getId())
                .customerId(customerId).branchId(branchId).requestedAmount(new BigDecimal("750"))
                .status(TopupRequestStatus.PENDING).requestedBy(USER).build();
        r.setId(UUID.randomUUID());
        r.setCompanyId(TENANT);
        when(repository.findByIdWithinCompany(r.getId(), TENANT)).thenReturn(Optional.of(r));
        return r;
    }

    private WalletTransaction ledgerEntry() {
        return WalletTransaction.builder().transactionNo("TXN1").amount(new BigDecimal("750"))
                .walletId(wallet.getId()).build();
    }

    @Test
    @DisplayName("approving a customer's request credits the customer's wallet, not a branch's")
    void approveCreditsCustomerWallet() {
        signedInAs(Roles.COMPANY_ADMIN);
        WalletTopupRequest r = pending(CUSTOMER, null);
        WalletTransaction entry = ledgerEntry();
        entry.setId(UUID.randomUUID());
        when(walletService.creditForCustomer(any())).thenReturn(entry);

        WalletTopupRequest decided = service.approve(r.getId(), "received");

        assertThat(decided.getStatus()).isEqualTo(TopupRequestStatus.APPROVED);
        assertThat(decided.getTransactionId()).isEqualTo(entry.getId());
        verify(walletService).creditForCustomer(org.mockito.ArgumentMatchers.argThat(
                (com.courier.modules.finance.application.command.CustomerCreditCommand c) ->
                        c.customerId().equals(CUSTOMER)
                                && c.amount().compareTo(new BigDecimal("750")) == 0
                                && c.subTransactionType() == SubTransactionType.MCR
                                && c.referenceType() == ReferenceType.MANUAL
                                && c.referenceId().equals(r.getId().toString())
                                && c.remarks().contains("received")));
        verify(walletService, never()).credit(any());
        verify(auditService).record(eq(AuditAction.WALLET_TOPUP_APPROVED), any(), any(),
                org.mockito.ArgumentMatchers.argThat(m -> m.toString().contains("customerId")));
    }

    @Test
    @DisplayName("approving a branch's request still credits the branch wallet")
    void approveCreditsBranchWallet() {
        signedInAs(Roles.COMPANY_ADMIN);
        WalletTopupRequest r = pending(null, BRANCH);
        WalletTransaction entry = ledgerEntry();
        entry.setId(UUID.randomUUID());
        when(walletService.credit(any())).thenReturn(entry);

        service.approve(r.getId(), null);

        verify(walletService).credit(any());
        verify(walletService, never()).creditForCustomer(any());
    }

    @Test
    @DisplayName("rejecting a customer's request moves nothing and does not need a branch")
    void rejectCustomerRequest() {
        signedInAs(Roles.COMPANY_ADMIN);
        WalletTopupRequest r = pending(CUSTOMER, null);

        WalletTopupRequest decided = service.reject(r.getId(), "not received");

        assertThat(decided.getStatus()).isEqualTo(TopupRequestStatus.REJECTED);
        verify(walletService, never()).creditForCustomer(any());
        verify(auditService).record(eq(AuditAction.WALLET_TOPUP_REJECTED), any(), any(),
                org.mockito.ArgumentMatchers.argThat(m -> m.toString().contains("customerId")));
    }

    @Test
    @DisplayName("a request that was already decided cannot be decided again")
    void decidedOnce() {
        signedInAs(Roles.COMPANY_ADMIN);
        WalletTopupRequest r = pending(CUSTOMER, null);
        r.setStatus(TopupRequestStatus.APPROVED);

        assertThatThrownBy(() -> service.approve(r.getId(), null)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.reject(r.getId(), null)).isInstanceOf(BusinessRuleException.class);

        verify(walletService, never()).creditForCustomer(any());
    }
}
