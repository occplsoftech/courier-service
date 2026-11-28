package com.courier.modules.finance.application;

import com.courier.modules.company.domain.DefaultRoleCatalog;
import com.courier.modules.finance.domain.BranchDirectoryPort;
import com.courier.modules.finance.domain.CashHandover;
import com.courier.modules.finance.domain.CashHandoverMode;
import com.courier.modules.finance.domain.CashHandoverRepository;
import com.courier.modules.finance.domain.CashHandoverStatus;
import com.courier.modules.shipment.application.storage.FileStoragePort;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.audit.domain.AuditAction;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CashHandoverServiceImplTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID BRANCH = UUID.randomUUID();
    private static final UUID OTHER_BRANCH = UUID.randomUUID();
    private static final UUID MANAGER = UUID.randomUUID();
    private static final UUID ACCOUNTS = UUID.randomUUID();
    private static final UUID RAVI = UUID.randomUUID();
    private static final String PROOF = "https://files.example/c/cash-handover-1.jpg";
    /** 2026-11-28 11:30 IST. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-28T06:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 28);

    @Mock private CashHandoverRepository repository;
    @Mock private BranchDirectoryPort branchDirectory;
    @Mock private AuditService auditService;
    @Mock private FileStoragePort fileStorage;

    private CashHandoverServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CashHandoverServiceImpl(repository, new FinanceBranchScope(branchDirectory), auditService,
                fileStorage, CLOCK);
        CompanyContext.setCompanyId(COMPANY);
        for (UUID u : List.of(MANAGER, ACCOUNTS, RAVI)) {
            when(branchDirectory.branchOfUser(u, COMPANY)).thenReturn(Optional.of(BRANCH));
        }
        when(branchDirectory.findBranch(any(), eq(COMPANY))).thenAnswer(i -> Optional.of(
                new BranchDirectoryPort.BranchRef(i.getArgument(0), COMPANY, "B", "Branch", true)));
        when(repository.save(any(CashHandover.class))).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        CompanyContext.clear();
        SecurityContextHolder.clearContext();
    }

    private void signedInAs(UUID userId, String... roles) {
        AuthenticatedUser principal = new AuthenticatedUser(userId, COMPANY, "u@test", Set.of(roles), "jti");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.authorities()));
    }

    private static CashHandoverService.SubmitCommand submit(UUID branch, String amount, LocalDate day, String proof) {
        return new CashHandoverService.SubmitCommand(branch, amount == null ? null : new BigDecimal(amount),
                CashHandoverMode.BANK_DEPOSIT, " slip 7 ", null, day, proof);
    }

    private CashHandover pendingFrom(UUID user, UUID branch) {
        CashHandover h = CashHandover.builder().branchId(branch).userId(user).amount(new BigDecimal("500"))
                .mode(CashHandoverMode.CASH).handoverDate(TODAY).proofImageUrl(PROOF).submittedAt(Instant.now(CLOCK)).build();
        h.setCompanyId(COMPANY);
        when(repository.findByIdWithinCompany(h.getId(), COMPANY)).thenReturn(Optional.of(h));
        return h;
    }

    // ------------------------------------------------------------------------------------------------ submit

    @Test
    @DisplayName("a delivery operator hands over their own cash with proof; it waits for approval")
    void submits() {
        signedInAs(RAVI, DefaultRoleCatalog.DELIVERY_OPERATOR);
        CashHandover h = service.submit(submit(null, "12600.50", null, PROOF));

        assertThat(h.getUserId()).isEqualTo(RAVI);
        assertThat(h.getBranchId()).isEqualTo(BRANCH);
        assertThat(h.getStatus()).isEqualTo(CashHandoverStatus.PENDING);
        assertThat(h.getAmount()).isEqualByComparingTo("12600.50");
        assertThat(h.getHandoverDate()).isEqualTo(TODAY);
        assertThat(h.getReference()).isEqualTo("slip 7");
        assertThat(h.getProofImageUrl()).isEqualTo(PROOF);
        assertThat(h.getDecidedBy()).isNull();
        verify(auditService).record(eq(AuditAction.CASH_HANDOVER_SUBMITTED), any(), any(), any());
    }

    @Test
    @DisplayName("no handover without a proof image, a positive amount and a date that is not in the future")
    void submitValidation() {
        signedInAs(RAVI, DefaultRoleCatalog.BOOKING_OPERATOR);
        assertThatThrownBy(() -> service.submit(submit(null, "100", null, null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("proof");
        assertThatThrownBy(() -> service.submit(submit(null, "100", null, "javascript:alert(1)")))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.submit(submit(null, "0", null, PROOF))).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.submit(submit(null, "10.555", null, PROOF))).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.submit(submit(null, "100", TODAY.plusDays(1), PROOF)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("future");
        assertThatThrownBy(() -> service.submit(submit(OTHER_BRANCH, "100", null, PROOF)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a proof image is stored under the company; other file types are refused")
    void uploadsProof() {
        signedInAs(RAVI, DefaultRoleCatalog.DELIVERY_OPERATOR);
        when(fileStorage.upload(any())).thenReturn(new FileStoragePort.StoredFile(PROOF, "k"));
        assertThat(service.uploadProof(new CashHandoverService.UploadProofCommand(new byte[]{1, 2}, "slip.JPG", "image/jpeg")))
                .isEqualTo(PROOF);
        ArgumentCaptor<FileStoragePort.UploadRequest> sent = ArgumentCaptor.forClass(FileStoragePort.UploadRequest.class);
        verify(fileStorage).upload(sent.capture());
        assertThat(sent.getValue().filename()).startsWith(COMPANY + "/cash-handover-").endsWith(".jpg");

        assertThatThrownBy(() -> service.uploadProof(new CashHandoverService.UploadProofCommand(new byte[]{1}, "x.pdf", "application/pdf")))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.uploadProof(new CashHandoverService.UploadProofCommand(new byte[0], "x.png", "image/png")))
                .isInstanceOf(BusinessRuleException.class);
    }

    // ------------------------------------------------------------------------------------------------ decide

    @Test
    @DisplayName("the branch manager approves after viewing the proof; a second decision is refused")
    void approves() {
        CashHandover h = pendingFrom(RAVI, BRANCH);
        signedInAs(MANAGER, Roles.BRANCH_MANAGER);
        CashHandover approved = service.approve(h.getId(), " counted ");

        assertThat(approved.getStatus()).isEqualTo(CashHandoverStatus.APPROVED);
        assertThat(approved.getDecidedBy()).isEqualTo(MANAGER);
        assertThat(approved.getDecidedAt()).isEqualTo(Instant.now(CLOCK));
        assertThat(approved.getDecisionRemarks()).isEqualTo("counted");
        verify(auditService).record(eq(AuditAction.CASH_HANDOVER_APPROVED), any(), any(), any());
        assertThatThrownBy(() -> service.reject(h.getId(), "late")).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already been approved");
    }

    @Test
    @DisplayName("the accounts desk rejects with a reason; without one it is refused")
    void rejects() {
        CashHandover h = pendingFrom(RAVI, BRANCH);
        signedInAs(ACCOUNTS, DefaultRoleCatalog.ACCOUNTS);
        assertThatThrownBy(() -> service.reject(h.getId(), "  ")).isInstanceOf(BusinessRuleException.class);
        CashHandover rejected = service.reject(h.getId(), "Slip unreadable");
        assertThat(rejected.getStatus()).isEqualTo(CashHandoverStatus.REJECTED);
        assertThat(rejected.getDecisionRemarks()).isEqualTo("Slip unreadable");
        verify(auditService).record(eq(AuditAction.CASH_HANDOVER_REJECTED), any(), any(), any());
    }

    @Test
    @DisplayName("an operator cannot approve; nobody approves their own; another branch's is not found")
    void whoDecides() {
        CashHandover ravis = pendingFrom(RAVI, BRANCH);
        UUID other = UUID.randomUUID();
        when(branchDirectory.branchOfUser(other, COMPANY)).thenReturn(Optional.of(BRANCH));
        signedInAs(other, DefaultRoleCatalog.BOOKING_OPERATOR);
        assertThatThrownBy(() -> service.approve(ravis.getId(), null)).isInstanceOf(AccessDeniedException.class);

        CashHandover managers = pendingFrom(MANAGER, BRANCH);
        signedInAs(MANAGER, Roles.BRANCH_MANAGER);
        assertThatThrownBy(() -> service.approve(managers.getId(), null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("your own");

        CashHandover elsewhere = pendingFrom(UUID.randomUUID(), OTHER_BRANCH);
        assertThatThrownBy(() -> service.approve(elsewhere.getId(), null)).isInstanceOf(ResourceNotFoundException.class);

        signedInAs(ACCOUNTS, DefaultRoleCatalog.ACCOUNTS);
        assertThat(service.approve(managers.getId(), null).isApproved())
                .as("the manager's own cash is approved by someone else").isTrue();
    }

    @Test
    @DisplayName("an approved handover is voided by whoever approved it or a company admin; a pending one cannot be voided")
    void voids() {
        CashHandover h = pendingFrom(RAVI, BRANCH);
        signedInAs(MANAGER, Roles.BRANCH_MANAGER);
        assertThatThrownBy(() -> service.voidHandover(h.getId(), "x")).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Only an approved");
        service.approve(h.getId(), null);

        signedInAs(ACCOUNTS, DefaultRoleCatalog.ACCOUNTS);
        assertThatThrownBy(() -> service.voidHandover(h.getId(), "x")).isInstanceOf(AccessDeniedException.class);

        signedInAs(MANAGER, Roles.BRANCH_MANAGER);
        CashHandover voided = service.voidHandover(h.getId(), "Counted twice");
        assertThat(voided.getStatus()).isEqualTo(CashHandoverStatus.VOIDED);
        assertThat(voided.getVoidedBy()).isEqualTo(MANAGER);
        assertThat(voided.getVoidReason()).isEqualTo("Counted twice");
        verify(auditService).record(eq(AuditAction.CASH_HANDOVER_VOIDED), any(), any(), any());
    }

    // ------------------------------------------------------------------------------------------------ reads

    @Test
    @DisplayName("an operator lists and sees pending only their own; a manager sees the branch's")
    void listScope() {
        CashHandover mine = CashHandover.builder().branchId(BRANCH).userId(RAVI).amount(BigDecimal.TEN)
                .mode(CashHandoverMode.CASH).handoverDate(TODAY).proofImageUrl(PROOF).submittedAt(Instant.now(CLOCK)).build();
        CashHandover theirs = CashHandover.builder().branchId(BRANCH).userId(UUID.randomUUID()).amount(BigDecimal.ONE)
                .mode(CashHandoverMode.UPI).handoverDate(TODAY).proofImageUrl(PROOF).submittedAt(Instant.now(CLOCK)).build();
        when(repository.findForBranchBetween(COMPANY, BRANCH, TODAY, TODAY)).thenReturn(List.of(mine, theirs));
        when(repository.findPendingForBranch(COMPANY, BRANCH)).thenReturn(List.of(mine, theirs));

        signedInAs(RAVI, DefaultRoleCatalog.DELIVERY_OPERATOR);
        assertThat(service.list(null, null, null, null)).containsExactly(mine);
        assertThat(service.pending(null)).containsExactly(mine);
        signedInAs(MANAGER, Roles.BRANCH_MANAGER);
        assertThat(service.list(null, null, null, null)).containsExactly(mine, theirs);
        assertThat(service.pending(null)).containsExactly(mine, theirs);
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("a window is at most 93 days and never ends before it starts")
    void window() {
        assertThatThrownBy(() -> CashHandoverServiceImpl.range(TODAY, TODAY.minusDays(1), CLOCK))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> CashHandoverServiceImpl.range(TODAY.minusDays(93), TODAY, CLOCK))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(CashHandoverServiceImpl.range(TODAY.minusDays(92), TODAY, CLOCK)).containsExactly(TODAY.minusDays(92), TODAY);
        assertThat(CashHandoverServiceImpl.range(null, null, CLOCK)).containsExactly(TODAY, TODAY);
    }
}
