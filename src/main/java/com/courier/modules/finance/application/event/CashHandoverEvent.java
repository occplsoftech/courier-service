package com.courier.modules.finance.application.event;

import com.courier.modules.finance.domain.CashHandoverMode;
import com.courier.modules.finance.domain.CashHandoverStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A cash handover was submitted, approved, rejected or voided. Published inside the handover's own transaction and
 * acted on only after it commits ({@link CashHandoverNotificationListener}), so nobody is told about a handover that
 * rolled back, and a notification failure never undoes one.
 *
 * @param status the handover's status after this change (PENDING = just submitted)
 * @param actor  who did it: the user handing over (submitted), or the approver (approved / rejected / voided)
 * @param note   the rejection or void reason, or the approver's note; null when none
 */
public record CashHandoverEvent(UUID companyId, UUID handoverId, UUID branchId, UUID userId, UUID actor,
                                CashHandoverStatus status, BigDecimal amount, CashHandoverMode mode,
                                LocalDate handoverDate, String note) {
}
