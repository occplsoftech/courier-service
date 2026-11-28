package com.courier.modules.finance.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * User Performance Report: for one branch and a window of days (IST, at most 93), one row per user with what they
 * booked, took at the counter, delivered and collected on delivery, the branch-wallet debits under their name, and
 * the cash they have handed over and still hold. A caller without team access ({@link FinanceBranchScope#seesTeam})
 * gets only their own row ("My Performance").
 */
public interface UserPerformanceService {

    UserPerformance.Report report(UUID branchId, LocalDate from, LocalDate to, UserPerformance.Attribution attribution);

    /**
     * What is behind one figure of one row. {@code userId} null: the work done without a signed-in user (bookings,
     * counter) or the debits under no user's name (wallet).
     */
    List<UserPerformance.DetailRow> details(UUID branchId, LocalDate from, LocalDate to, UUID userId,
                                            UserPerformance.Metric metric, UserPerformance.Attribution attribution);
}
