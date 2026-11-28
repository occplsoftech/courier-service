package com.courier.modules.finance.api;

import com.courier.modules.finance.application.UserPerformance;
import com.courier.modules.finance.application.UserPerformanceService;
import com.courier.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** User Performance Report: per user of a branch — bookings, deliveries, cash, wallet debits, cash handed over. */
@RestController
@RequestMapping("/api/v1/branch-wallet/user-performance")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "User Performance Report", description = "Per-user bookings, deliveries, cash and wallet debits of a branch")
public class UserPerformanceController {

    private final UserPerformanceService service;

    @GetMapping
    @Operation(summary = "One row per user of the branch for the days given (IST, at most 93)",
            description = "Company admin / finance: any branch. Branch manager, hub manager, accounts, viewer: every "
                    + "user of their own branch. Anyone else: only their own row. attribution = WORK (default: debits "
                    + "under the user whose work caused them) or ENTERED_BY (whoever was signed in).")
    public ApiResponse<UserPerformance.Report> report(
            @RequestParam(required = false) UUID branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UserPerformance.Attribution attribution) {
        return ApiResponse.success(service.report(branchId, from, to, attribution));
    }

    @GetMapping("/details")
    @Operation(summary = "What is behind one figure: bookings, counter payments, deliveries, wallet entries or handovers",
            description = "userId omitted: work done without a signed-in user, or (metric WALLET) the debits under no "
                    + "user's name.")
    public ApiResponse<List<UserPerformance.DetailRow>> details(
            @RequestParam(required = false) UUID branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID userId,
            @RequestParam UserPerformance.Metric metric,
            @RequestParam(required = false) UserPerformance.Attribution attribution) {
        return ApiResponse.success(service.details(branchId, from, to, userId, metric, attribution));
    }
}
