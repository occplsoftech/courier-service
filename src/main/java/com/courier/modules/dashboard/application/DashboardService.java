package com.courier.modules.dashboard.application;

import com.courier.modules.dashboard.api.dto.DashboardSummaryResponse;

public interface DashboardService {

    /**
     * Operational figures for the caller's company (scoped by {@code CompanyContext}
     * the same way every other company-owned query is), or across every company when
     * called with no company bound — a platform-level caller.
     */
    default DashboardSummaryResponse summary() {
        return summary(null);
    }

    /**
     * {@code branchId}: a company admin sees that branch's dashboard, exactly as its branch staff do (13.4); branch staff may only
     * name their own branch. Null: the caller's own view.
     */
    DashboardSummaryResponse summary(java.util.UUID branchId);
}
