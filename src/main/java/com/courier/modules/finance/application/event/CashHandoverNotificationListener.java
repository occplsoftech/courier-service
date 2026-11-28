package com.courier.modules.finance.application.event;

import com.courier.modules.company.domain.DefaultRoleCatalog;
import com.courier.modules.finance.domain.BranchDirectoryPort;
import com.courier.modules.finance.domain.CashHandoverMode;
import com.courier.modules.support.application.NotificationService;
import com.courier.modules.support.domain.NotificationType;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.security.Roles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Tells people about cash handovers in the in-app notification feed (the bell):
 * <ul>
 *   <li><b>Submitted</b> → the branch's approvers: users placed at or managing the branch with BRANCH_MANAGER or
 *       ACCOUNTS; when the branch has none, the company admins. Never the person who submitted it.</li>
 *   <li><b>Approved / rejected / voided</b> → the user who handed the cash over, with the reason when there is one.</li>
 * </ul>
 * AFTER_COMMIT and REQUIRES_NEW, the same shape as the wallet listeners; any failure is logged and swallowed — a
 * missed notification must never undo a handover.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CashHandoverNotificationListener {

    static final Set<String> BRANCH_APPROVERS = Set.of(Roles.BRANCH_MANAGER, DefaultRoleCatalog.ACCOUNTS);
    static final Set<String> COMPANY_APPROVERS = Set.of(Roles.COMPANY_ADMIN);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final int MAX_MESSAGE = 500;

    private final NotificationService notifications;
    private final BranchDirectoryPort directory;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(CashHandoverEvent event) {
        try {
            CompanyContext.runAs(event.companyId(), () -> send(event));
        } catch (Exception e) {
            log.error("Cash handover {} ({}): notification failed", event.handoverId(), event.status(), e);
        }
    }

    private void send(CashHandoverEvent e) {
        String amount = rupees(e.amount());
        String day = e.handoverDate() == null ? "" : e.handoverDate().format(DAY);
        String actor = name(e.actor(), e.companyId());
        switch (e.status()) {
            case PENDING -> {
                String message = "%s handed over %s (%s, %s). Check the proof and approve or reject it."
                        .formatted(actor, amount, modeLabel(e.mode()), day);
                for (UUID recipient : approvers(e)) {
                    notifications.notify(recipient, NotificationType.CASH_HANDOVER_SUBMITTED,
                            "Cash handover to approve", clip(message), null);
                }
            }
            case APPROVED -> notifications.notify(e.userId(), NotificationType.CASH_HANDOVER_APPROVED,
                    "Cash handover approved",
                    clip("%s approved your %s handover of %s.%s".formatted(actor, amount, day,
                            e.note() == null ? "" : " Note: " + e.note())), null);
            case REJECTED -> notifications.notify(e.userId(), NotificationType.CASH_HANDOVER_REJECTED,
                    "Cash handover rejected",
                    clip("%s rejected your %s handover of %s: %s. Send it again with the right proof."
                            .formatted(actor, amount, day, e.note())), null);
            case VOIDED -> notifications.notify(e.userId(), NotificationType.CASH_HANDOVER_VOIDED,
                    "Cash handover voided",
                    clip("%s voided your approved %s handover of %s: %s. It counts as cash you hold again."
                            .formatted(actor, amount, day, e.note())), null);
        }
    }

    /** The branch's managers and accounts desk, else the company admins; never the one who submitted it. */
    List<UUID> approvers(CashHandoverEvent e) {
        List<UUID> branch = directory.usersWithAnyRole(e.companyId(), e.branchId(), BRANCH_APPROVERS).stream()
                .filter(u -> !u.equals(e.userId())).distinct().toList();
        if (!branch.isEmpty()) {
            return branch;
        }
        return directory.usersWithAnyRole(e.companyId(), null, COMPANY_APPROVERS).stream()
                .filter(u -> !u.equals(e.userId())).distinct().toList();
    }

    private String name(UUID userId, UUID companyId) {
        if (userId == null) return "Someone";
        return directory.findUser(userId, companyId).map(BranchDirectoryPort.UserRef::displayName)
                .filter(n -> !n.isBlank()).orElse("Someone");
    }

    static String rupees(BigDecimal amount) {
        NumberFormat f = NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN"));
        f.setMinimumFractionDigits(2);
        f.setMaximumFractionDigits(2);
        return "₹" + f.format(amount == null ? BigDecimal.ZERO : amount);
    }

    private static String modeLabel(CashHandoverMode mode) {
        if (mode == null) return "cash";
        return switch (mode) {
            case CASH -> "cash";
            case BANK_DEPOSIT -> "bank deposit";
            case UPI -> "UPI";
        };
    }

    private static String clip(String message) {
        return message.length() <= MAX_MESSAGE ? message : message.substring(0, MAX_MESSAGE - 1) + "…";
    }
}
