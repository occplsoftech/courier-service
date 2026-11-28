package com.courier.modules.finance.application;

import com.courier.modules.finance.domain.CashHandover;
import com.courier.modules.finance.domain.CashHandoverMode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Cash a branch user hands over to their branch manager, the branch's ACCOUNTS desk or a company admin. Settles the
 * user's outstanding cash on the User Performance Report; never touches the branch wallet. See
 * {@link FinanceBranchScope} for who may record and read.
 */
public interface CashHandoverService {

    /**
     * @param branchId     the branch the cash belongs to; null = the caller's own. A branch-bound caller naming another
     *                     branch gets a 404
     * @param userId       who handed the cash over — never the caller themselves unless they are a company admin
     * @param handoverDate the day it changed hands (IST); today when null, never in the future
     */
    record RecordCommand(UUID branchId, UUID userId, BigDecimal amount, CashHandoverMode mode,
                         String reference, String remarks, LocalDate handoverDate) {
    }

    CashHandover record(RecordCommand command);

    /** Cancels a handover recorded by mistake: the receiver who recorded it, or a company admin. Reason required. */
    CashHandover voidHandover(UUID handoverId, String reason);

    /** A branch's handovers (voided ones included) on the days given, newest first; a caller without team access
     *  sees only their own. {@code userId} narrows to one user. */
    List<CashHandover> list(UUID branchId, LocalDate from, LocalDate to, UUID userId);
}
