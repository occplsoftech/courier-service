package com.courier.modules.finance.application.event;

import com.courier.modules.finance.domain.BranchDirectoryPort;
import com.courier.modules.finance.domain.CashHandoverMode;
import com.courier.modules.finance.domain.CashHandoverStatus;
import com.courier.modules.support.application.NotificationService;
import com.courier.modules.support.domain.NotificationType;
import com.courier.shared.company.CompanyContext;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CashHandoverNotificationListenerTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID BRANCH = UUID.randomUUID();
    private static final UUID RAVI = UUID.randomUUID();
    private static final UUID MEERA = UUID.randomUUID();
    private static final UUID ACCOUNTS = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();

    @Mock private NotificationService notifications;
    @Mock private BranchDirectoryPort directory;

    private CashHandoverNotificationListener listener;

    @BeforeEach
    void setUp() {
        listener = new CashHandoverNotificationListener(notifications, directory);
        Map<UUID, String> names = Map.of(RAVI, "Ravi Kulkarni", MEERA, "Meera Joshi");
        when(directory.findUser(any(), eq(COMPANY))).thenAnswer(i -> Optional.ofNullable(names.get(i.getArgument(0)))
                .map(n -> new BranchDirectoryPort.UserRef(i.getArgument(0), COMPANY, n)));
        when(directory.usersWithAnyRole(COMPANY, null, CashHandoverNotificationListener.COMPANY_APPROVERS))
                .thenReturn(List.of(ADMIN));
    }

    @AfterEach
    void tearDown() {
        CompanyContext.clear();
    }

    private static CashHandoverEvent event(CashHandoverStatus status, UUID actor, String note) {
        return new CashHandoverEvent(COMPANY, UUID.randomUUID(), BRANCH, RAVI, actor, status, new BigDecimal("6200.0000"),
                CashHandoverMode.BANK_DEPOSIT, LocalDate.of(2026, 11, 28), note);
    }

    @Test
    @DisplayName("a submitted handover goes to the branch's manager and accounts desk, never to the one who sent it")
    void submittedToBranchApprovers() {
        when(directory.usersWithAnyRole(COMPANY, BRANCH, CashHandoverNotificationListener.BRANCH_APPROVERS))
                .thenReturn(List.of(MEERA, ACCOUNTS, RAVI, MEERA));

        listener.on(event(CashHandoverStatus.PENDING, RAVI, null));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notifications).notify(eq(MEERA), eq(NotificationType.CASH_HANDOVER_SUBMITTED), eq("Cash handover to approve"),
                message.capture(), isNull());
        verify(notifications).notify(eq(ACCOUNTS), eq(NotificationType.CASH_HANDOVER_SUBMITTED), anyString(), anyString(), isNull());
        verify(notifications, never()).notify(eq(RAVI), any(), anyString(), anyString(), any());
        verify(notifications, never()).notify(eq(ADMIN), any(), anyString(), anyString(), any());
        assertThat(message.getValue())
                .isEqualTo("Ravi Kulkarni handed over ₹6,200.00 (bank deposit, 28 Nov 2026). Check the proof and approve or reject it.");
    }

    @Test
    @DisplayName("a branch with no manager or accounts user falls back to the company admins")
    void fallsBackToCompanyAdmins() {
        when(directory.usersWithAnyRole(COMPANY, BRANCH, CashHandoverNotificationListener.BRANCH_APPROVERS))
                .thenReturn(List.of(RAVI));
        listener.on(event(CashHandoverStatus.PENDING, RAVI, null));
        verify(notifications).notify(eq(ADMIN), eq(NotificationType.CASH_HANDOVER_SUBMITTED), anyString(), anyString(), isNull());
    }

    @Test
    @DisplayName("approved, rejected and voided go back to the user who handed over, with the reason")
    void decisionsToTheUser() {
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);

        listener.on(event(CashHandoverStatus.APPROVED, MEERA, null));
        verify(notifications).notify(eq(RAVI), eq(NotificationType.CASH_HANDOVER_APPROVED), eq("Cash handover approved"),
                message.capture(), isNull());
        assertThat(message.getValue()).isEqualTo("Meera Joshi approved your ₹6,200.00 handover of 28 Nov 2026.");

        listener.on(event(CashHandoverStatus.REJECTED, MEERA, "Slip unreadable"));
        verify(notifications).notify(eq(RAVI), eq(NotificationType.CASH_HANDOVER_REJECTED), eq("Cash handover rejected"),
                message.capture(), isNull());
        assertThat(message.getValue()).contains("rejected your ₹6,200.00 handover").contains("Slip unreadable");

        listener.on(event(CashHandoverStatus.VOIDED, MEERA, "Counted twice"));
        verify(notifications).notify(eq(RAVI), eq(NotificationType.CASH_HANDOVER_VOIDED), anyString(), message.capture(), isNull());
        assertThat(message.getValue()).contains("Counted twice").contains("counts as cash you hold again");
    }

    @Test
    @DisplayName("a failure to notify is swallowed, so it can never undo the handover")
    void failureIsSwallowed() {
        doThrow(new IllegalStateException("db down")).when(notifications)
                .notify(any(), any(), anyString(), anyString(), any());
        assertThatCode(() -> listener.on(event(CashHandoverStatus.APPROVED, MEERA, null))).doesNotThrowAnyException();
    }
}
