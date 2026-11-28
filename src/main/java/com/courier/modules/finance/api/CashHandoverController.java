package com.courier.modules.finance.api;

import com.courier.modules.finance.api.dto.CashHandoverResponse;
import com.courier.modules.finance.api.dto.RecordCashHandoverRequest;
import com.courier.modules.finance.api.dto.VoidCashHandoverRequest;
import com.courier.modules.finance.application.CashHandoverService;
import com.courier.modules.finance.application.FinanceBranchScope;
import com.courier.modules.finance.domain.CashHandover;
import com.courier.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Cash a branch user hands over to their manager / the accounts desk. Moves no wallet money. */
@RestController
@RequestMapping("/api/v1/branch-wallet/cash-handovers")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Cash Handovers", description = "Cash collected by branch users, handed over to the branch")
public class CashHandoverController {

    private final CashHandoverService service;
    private final FinanceBranchScope scope;

    @PostMapping
    @Operation(summary = "Record a cash handover",
            description = "BRANCH_MANAGER, ACCOUNTS or COMPANY_ADMIN — whoever received the cash. Never from yourself "
                    + "(a company admin excepted). Settles the user's outstanding cash on the User Performance Report.")
    public ResponseEntity<ApiResponse<CashHandoverResponse>> record(@Valid @RequestBody RecordCashHandoverRequest body) {
        CashHandover saved = service.record(new CashHandoverService.RecordCommand(body.branchId(), body.userId(),
                body.amount(), body.mode(), body.reference(), body.remarks(), body.handoverDate()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(toResponse(saved, new HashMap<>()), "Cash handover recorded"));
    }

    @PostMapping("/{id}/void")
    @Operation(summary = "Void a cash handover recorded by mistake",
            description = "Whoever recorded it, or a company admin. The amount counts as outstanding again.")
    public ApiResponse<CashHandoverResponse> voidHandover(@PathVariable UUID id,
                                                          @Valid @RequestBody VoidCashHandoverRequest body) {
        return ApiResponse.success(toResponse(service.voidHandover(id, body.reason()), new HashMap<>()),
                "Cash handover voided");
    }

    @GetMapping
    @Operation(summary = "List a branch's cash handovers",
            description = "Voided ones included, newest first, at most 93 days. Without team access you see only your own.")
    public ApiResponse<List<CashHandoverResponse>> list(
            @RequestParam(required = false) UUID branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID userId) {
        Map<UUID, String> names = new HashMap<>();
        return ApiResponse.success(service.list(branchId, from, to, userId).stream()
                .map(h -> toResponse(h, names)).toList());
    }

    private CashHandoverResponse toResponse(CashHandover h, Map<UUID, String> names) {
        UUID companyId = h.getCompanyId();
        java.util.function.Function<UUID, String> name = id -> id == null ? null
                : names.computeIfAbsent(id, k -> scope.displayName(k, companyId));
        return new CashHandoverResponse(h.getId(), h.getBranchId(), h.getUserId(), name.apply(h.getUserId()),
                h.getAmount(), h.getMode().name(), h.getReference(), h.getRemarks(), h.getHandoverDate(),
                h.getReceivedBy(), name.apply(h.getReceivedBy()), h.getReceivedAt(),
                h.isVoided(), h.getVoidedBy(), name.apply(h.getVoidedBy()), h.getVoidedAt(), h.getVoidReason());
    }
}
