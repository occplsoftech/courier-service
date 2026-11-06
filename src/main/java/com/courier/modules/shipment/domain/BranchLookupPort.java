package com.courier.modules.shipment.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * What Shipment Booking needs to know about branches, and nothing more — the two ends of
 * a booking.
 *
 * <p>Shipment owns the interface; {@code modules/company} supplies the adapter. The same
 * seam auth uses for companies ({@code CompanyDirectoryPort}), Finance uses for wallets
 * ({@code BranchDirectoryPort}), and Master uses for routes ({@code BranchLookupPort}) —
 * and, per Master's own documented reasoning, deliberately <b>not</b> a reuse of either of
 * those: importing Finance's port to talk about branches would make Shipment depend on
 * Finance for something that has nothing to do with money, which is a worse arrow than the
 * small duplication of a four-field record.
 *
 * <p>Booking needs this for the branch label shown on a list/detail response — branch
 * *existence and activity* is already established by {@code RouteService.findByBranches}
 * inside the Pricing Engine's own {@code RouteValidation} (a route only ever names two
 * real branches, validated when the route itself was created), so this port is a read for
 * display, not a second existence check.
 *
 * <p>The company is passed explicitly on every call, never taken from the ambient
 * {@code CompanyContext} — a caller that forgot to bind one gets nothing rather than
 * everything.
 */
public interface BranchLookupPort {

    /** Identity of one branch, flattened. */
    record BranchRef(UUID branchId, String branchCode, String branchName, boolean active) {
    }

    /** The branch, if it exists within this company. Empty for a foreign or unknown id. */
    Optional<BranchRef> findBranch(UUID branchId, UUID companyId);

    /** A branch's commission settings (percentages), the same ones a counter booking uses. */
    record CommissionRates(java.math.BigDecimal companyServiceChargePercentage, java.math.BigDecimal commissionOnBasicFreight,
                           java.math.BigDecimal commissionOnOtherCharges) {
    }

    /** The branch's commission settings, read without the staff-only branch rules (a customer's portal booking needs them too). */
    default Optional<CommissionRates> findCommissionRates(UUID branchId, UUID companyId) {
        return Optional.empty();
    }
}
