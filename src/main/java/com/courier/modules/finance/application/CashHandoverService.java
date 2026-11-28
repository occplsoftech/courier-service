package com.courier.modules.finance.application;

import com.courier.modules.finance.domain.CashHandover;
import com.courier.modules.finance.domain.CashHandoverMode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Cash a branch user hands over. The user who holds the cash {@linkplain #submit submits} it with a proof image; a
 * branch manager, the branch's ACCOUNTS desk or a company admin views the proof and {@linkplain #approve approves} or
 * {@linkplain #reject rejects} it. Only approved handovers settle the user's outstanding cash on the User Performance
 * Report; the branch wallet is never touched. See {@link FinanceBranchScope} for who may do what.
 */
public interface CashHandoverService {

    record UploadProofCommand(byte[] content, String filename, String contentType) {
    }

    /**
     * @param branchId     the branch the cash belongs to; null = the caller's own. A branch-bound caller naming another
     *                     branch gets a 404
     * @param handoverDate the day the cash changed hands / was deposited (IST); today when null, never in the future
     * @param proofImageUrl from {@link #uploadProof}; required
     */
    record SubmitCommand(UUID branchId, BigDecimal amount, CashHandoverMode mode, String reference, String remarks,
                         LocalDate handoverDate, String proofImageUrl) {
    }

    /** Stores a proof image (JPEG / PNG / WEBP / HEIC) and returns its URL for {@link #submit}. */
    String uploadProof(UploadProofCommand command);

    /** The signed-in user hands over their own cash. Starts PENDING. */
    CashHandover submit(SubmitCommand command);

    /** The receiver saw the proof and the money: PENDING → APPROVED. Never your own handover. */
    CashHandover approve(UUID handoverId, String note);

    /** The receiver refuses it, with a reason: PENDING → REJECTED. Never your own handover. */
    CashHandover reject(UUID handoverId, String reason);

    /** An approved handover found to be wrong: APPROVED → VOIDED. Whoever approved it, or a company admin. */
    CashHandover voidHandover(UUID handoverId, String reason);

    /** A branch's handovers dated in the window, newest first; a caller without team access sees only their own.
     *  {@code userId} narrows to one user. */
    List<CashHandover> list(UUID branchId, LocalDate from, LocalDate to, UUID userId);

    /** Every handover of the branch waiting for approval, whatever its date, oldest first (own only without team
     *  access). */
    List<CashHandover> pending(UUID branchId);
}
