package com.courier.modules.finance.application;

import com.courier.modules.company.domain.DefaultRoleCatalog;
import com.courier.modules.finance.domain.BranchDirectoryPort;
import com.courier.modules.finance.domain.CashHandover;
import com.courier.modules.finance.domain.CashHandoverMode;
import com.courier.modules.finance.domain.CashHandoverRepository;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CashHandoverServiceImplTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID BRANCH = UUID.randomUUID();
    private static final UUID OTHER_BRANCH = UUID.randomUUID();
    private static final UUID MANAGER = UUID.randomUUID();
    private static final UUID RAVI = UUID.randomUUID();
    /** 2026-11-28 11:30 IST. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-28T06:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 28);

    @Mock private CashHandoverRepository repository;
    @Mock private BranchDirectoryPort branchDirectory;
    @Mock private AuditService auditService;

    private CashHandoverServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CashHandoverServiceImpl(repository, new FinanceBranchScope(branchDirectory), auditService, CLOCK);
        CompanyContext.setCompanyId(COMPANY);
        when(branchDirectory.branchOfUser(MANAGER, COMPANY)).thenReturn(Optional.of(BRANCH));
        when(branchDirectory.branchOfUser(RAVI, COMPANY)).thenReturn(Optional.of(BRANCH));
        when(branchDirectory.findBranch(any(), eq(COMPANY))).thenAnswer(i -> Optional.of(
                new BranchDirectoryPort.BranchRef(i.getArgument(0), COMPANY, "B", "Branch", true)));
        when(branchDirectory.findUser(any(), eq(COMPANY))).thenAnswer(i -> Optional.of(
                new BranchDirectoryPort.UserRef(i.getArgument(0), COMPANY, "Someone")));
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

    private static CashHandoverService.RecordCommand cmd(UUID branch, UUID user, String amount, LocalDate day) {
        return new CashHandoverService.RecordCommand(branch, user, amount == null ? null : new BigDecimal(amount),
                CashHandoverMode.CASH, " slip 7 ", null, day);
    }

    @Test
    @DisplayName("a branch manager records cash received from a user of their branch, dated today by default")
    void records() {
        signedInAs(MANAGER, Roles.BRANCH_MANAGER);
        assertThatThrownBy(() -> service.record(cmd(OTHER_BRANCH, RAVI, "100", null)))
                .as("another branch is not theirs to record for").isInstanceOf(ResourceNotFoundException.class);
        CashHandover h = service.record(cmd(null, RAVI, "12600.50", null));

        assertThat(h.getBranchId()).as("a branch caller's own branch").isEqualTo(BRANCH);
        assertThat(h.getUserId()).isEqualTo(RAVI);
        assertThat(h.getReceivedBy()).isEqualTo(MANAGER);
        assertThat(h.getAmount()).isEqualByComparingTo("12600.50");
        assertThat(h.getHandoverDate()).isEqualTo(TODAY);
        assertThat(h.getReference()).isEqualTo("slip 7");
        assertThat(h.isVoided()).isFalse();
        verify(auditService).record(eq(AuditAction.CASH_HANDOVER_RECORDED), any(), any(), any());
    }

    @Test
    @DisplayName("the accounts desk may record; a booking operator may not")
    void whoRecords() {
        signedInAs(MANAGER, DefaultRoleCatalog.ACCOUNTS);
        assertThat(service.record(cmd(null, RAVI, "100", null)).getReceivedBy()).isEqualTo(MANAGER);

        signedInAs(RAVI, DefaultRoleCatalog.BOOKING_OPERATOR);
        assertThatThrownBy(() -> service.record(cmd(null, MANAGER, "100", null)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("nobody but a company admin records a handover from themselves")
    void notFromSelf() {
        signedInAs(MANAGER, Roles.BRANCH_MANAGER);
        assertThatThrownBy(() -> service.record(cmd(null, MANAGER, "100", null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("yourself");

        signedInAs(MANAGER, Roles.COMPANY_ADMIN);
        assertThat(service.record(cmd(BRANCH, MANAGER, "100", null)).getUserId()).isEqualTo(MANAGER);
    }

    @Test
    @DisplayName("refuses a zero amount, a future date and an unknown user")
    void validation() {
        signedInAs(MANAGER, Roles.BRANCH_MANAGER);
        assertThatThrownBy(() -> service.record(cmd(null, RAVI, "0", null))).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.record(cmd(null, RAVI, "10.555", null))).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.record(cmd(null, RAVI, "100", TODAY.plusDays(1))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("future");
        UUID stranger = UUID.randomUUID();
        when(branchDirectory.findUser(stranger, COMPANY)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.record(cmd(null, stranger, "100", null)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("whoever recorded a handover voids it with a reason, once; another manager may not")
    void voids() {
        CashHandover h = CashHandover.builder().branchId(BRANCH).userId(RAVI).amount(new BigDecimal("500"))
                .mode(CashHandoverMode.CASH).handoverDate(TODAY).receivedBy(MANAGER).receivedAt(Instant.now(CLOCK)).build();
        h.setCompanyId(COMPANY);
        when(repository.findByIdWithinCompany(h.getId(), COMPANY)).thenReturn(Optional.of(h));

        UUID otherManager = UUID.randomUUID();
        when(branchDirectory.branchOfUser(otherManager, COMPANY)).thenReturn(Optional.of(BRANCH));
        signedInAs(otherManager, Roles.BRANCH_MANAGER);
        assertThatThrownBy(() -> service.voidHandover(h.getId(), "typo")).isInstanceOf(AccessDeniedException.class);

        signedInAs(MANAGER, Roles.BRANCH_MANAGER);
        assertThatThrownBy(() -> service.voidHandover(h.getId(), " ")).isInstanceOf(BusinessRuleException.class);
        CashHandover voided = service.voidHandover(h.getId(), "Wrong amount");
        assertThat(voided.isVoided()).isTrue();
        assertThat(voided.getVoidedBy()).isEqualTo(MANAGER);
        assertThat(voided.getVoidReason()).isEqualTo("Wrong amount");
        assertThatThrownBy(() -> service.voidHandover(h.getId(), "again"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("already");
        verify(auditService).record(eq(AuditAction.CASH_HANDOVER_VOIDED), any(), any(), any());
    }

    @Test
    @DisplayName("a handover of another branch is not found for a branch manager")
    void otherBranchHidden() {
        CashHandover h = CashHandover.builder().branchId(OTHER_BRANCH).userId(RAVI).amount(BigDecimal.TEN)
                .mode(CashHandoverMode.CASH).handoverDate(TODAY).receivedBy(MANAGER).receivedAt(Instant.now(CLOCK)).build();
        when(repository.findByIdWithinCompany(h.getId(), COMPANY)).thenReturn(Optional.of(h));
        signedInAs(MANAGER, Roles.BRANCH_MANAGER);
        assertThatThrownBy(() -> service.voidHandover(h.getId(), "x")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("an operator lists only their own handovers; a manager sees the branch's")
    void listScope() {
        CashHandover mine = CashHandover.builder().branchId(BRANCH).userId(RAVI).amount(BigDecimal.TEN)
                .mode(CashHandoverMode.CASH).handoverDate(TODAY).receivedBy(MANAGER).receivedAt(Instant.now(CLOCK)).build();
        CashHandover theirs = CashHandover.builder().branchId(BRANCH).userId(UUID.randomUUID()).amount(BigDecimal.ONE)
                .mode(CashHandoverMode.UPI).handoverDate(TODAY).receivedBy(MANAGER).receivedAt(Instant.now(CLOCK)).build();
        when(repository.findForBranchBetween(COMPANY, BRANCH, TODAY, TODAY)).thenReturn(List.of(mine, theirs));

        signedInAs(RAVI, DefaultRoleCatalog.DELIVERY_OPERATOR);
        assertThat(service.list(null, null, null, null)).containsExactly(mine);
        signedInAs(MANAGER, Roles.BRANCH_MANAGER);
        assertThat(service.list(null, null, null, null)).containsExactly(mine, theirs);
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
