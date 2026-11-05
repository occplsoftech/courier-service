package com.courier.modules.company.application;

import com.courier.modules.company.application.command.CreateBranchCommand;
import com.courier.modules.company.application.command.UpdateBranchCommand;
import com.courier.modules.company.domain.Branch;
import com.courier.modules.company.domain.BranchCriteria;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Use cases for company branches.
 *
 * <p><b>Audiences:</b>
 * <ul>
 *   <li>{@code COMPANY_ADMIN} — full management of every branch in the company.</li>
 *   <li>{@code BRANCH_MANAGER} — may update and assign users to the branch they manage
 *       (the one whose {@code managerId} is them); nothing else.</li>
 *   <li>{@code SUPER_ADMIN} — read across companies.</li>
 *   <li>Any other company user — read only the branch they are assigned to.</li>
 * </ul>
 * The branch-level scoping is applied in the implementation, since no URL rule can
 * express "the branch they manage".
 */
public interface BranchService {

    /**
     * Creates a branch, its login account and — through the {@code BranchCreated} event —
     * its wallet. The user is created in the same transaction as the branch: a branch that
     * nobody can sign in to is not a branch anyone asked for, so the two succeed or fail
     * together. The wallet stays outside the transaction, as it always has.
     */
    BranchCreation create(CreateBranchCommand command);

    /**
     * @param branch the created branch
     * @param userId the account created with it
     * @param userEmail its login address
     * @param temporaryPassword generated password, returned once; null when the
     *                          administrator supplied one
     * @param assignedAsManager whether that account was also made the branch manager
     * @param branchManagerRoleId   the company role that account was granted
     * @param branchManagerRoleCode its code — {@code BRANCH_MANAGER}, unless the catalogue
     *                              is ever renamed
     */
    record BranchCreation(Branch branch, UUID userId, String userEmail,
                          String temporaryPassword, boolean assignedAsManager,
                          UUID branchManagerRoleId, String branchManagerRoleCode) {
    }

    Branch update(UUID id, UpdateBranchCommand command);

    Branch getById(UUID id);

    /**
     * The branch's current DRS charge/qty rate — company-scoped like every other read, but
     * deliberately <b>not</b> gated by the caller's own branch visibility {@link #getById}
     * enforces (a {@code BRANCH_MANAGER} calling {@code getById} on a branch other than
     * their own 404s). A company-wide report (e.g. Vendor Audit) needs every branch's
     * current rate side by side, not just the caller's own. Zero for a branch id absent
     * from the caller's company — never throws.
     */
    BigDecimal drsChargePerQtyOf(UUID branchId);

    /** Same not-visibility-gated shape as {@link #drsChargePerQtyOf} — the wallet
     *  dashboard's pending-commission figures need this for the booking branch, which is
     *  not always the caller's own branch (a {@code COMPANY_ADMIN} picking another
     *  branch's wallet). False for a branch id absent from the caller's company. */
    boolean instantCommissionOf(UUID branchId);

    Page<Branch> search(BranchCriteria criteria, Pageable pageable);

    /**
     * Every active branch of the caller's company, ignoring the branch-scoped visibility
     * {@link #search} applies for a non-admin. For pickers where any authenticated company
     * user legitimately needs the full list — e.g. Shipment Booking's delivery branch, or
     * the Rate Calculator — the same way any authenticated user already reads the pincode
     * or service-type masters. Branch codes and names are not the sensitive part of a
     * branch record; a booking clerk simply cannot book to a destination they cannot see.
     */
    List<Branch> listActiveDirectory();

    /** Soft delete. The row is retained; code and name stay reserved. */
    void delete(UUID id);

    Branch activate(UUID id);

    Branch deactivate(UUID id);

    Branch assignManager(UUID id, UUID managerId);

    /** Sets (or, with null, clears) the hub this branch sends its Loading Sheets to. */
    Branch assignHub(UUID id, UUID hubId);

    /** Sets (or, when blank, clears) the name this branch's pickup address has at Delhivery / Shiprocket. */
    Branch setCourierPickupLocation(UUID id, String name);

    /**
     * Places the given users at this branch (sets their {@code branchId}). Only users of
     * the same company are affected; unknown or foreign ids are reported back, not
     * silently applied.
     *
     * @return the codes... actually the ids of the users that were assigned
     */
    AssignUsersResult assignUsers(UUID id, Collection<UUID> userIds);

    /**
     * @param assigned ids placed at the branch
     * @param skipped  ids already at the branch
     * @param rejected ids that are not users of this company
     */
    record AssignUsersResult(List<UUID> assigned, List<UUID> skipped, List<UUID> rejected) {
    }
}
