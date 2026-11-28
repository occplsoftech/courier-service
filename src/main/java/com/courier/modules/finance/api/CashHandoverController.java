package com.courier.modules.finance.api;

import com.courier.modules.finance.api.dto.CashHandoverDecisionRequest;
import com.courier.modules.finance.api.dto.CashHandoverResponse;
import com.courier.modules.finance.api.dto.ProofImageUploadResponse;
import com.courier.modules.finance.api.dto.SubmitCashHandoverRequest;
import com.courier.modules.finance.application.CashHandoverService;
import com.courier.modules.finance.application.FinanceBranchScope;
import com.courier.modules.finance.domain.CashHandover;
import com.courier.shared.api.ApiResponse;
import com.courier.shared.exception.BusinessRuleException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cash a branch user hands over: they submit it with a proof image, a branch manager / the accounts desk / a company
 * admin views the proof and approves or rejects it. Only approved handovers settle outstanding cash. Moves no wallet
 * money.
 */
@RestController
@RequestMapping("/api/v1/branch-wallet/cash-handovers")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Cash Handovers", description = "Branch users hand over collected cash with proof; the branch approves")
public class CashHandoverController {

    private final CashHandoverService service;
    private final FinanceBranchScope scope;

    @PostMapping(value = "/upload-proof", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a handover proof image",
            description = "JPEG / PNG / WEBP / HEIC up to 10 MB: deposit slip, UPI screenshot or photo of the cash. "
                    + "Returns the URL to pass as proofImageUrl when submitting.")
    public ApiResponse<ProofImageUploadResponse> uploadProof(@RequestParam("file") MultipartFile file) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new BusinessRuleException("The uploaded file could not be read. Please retry.");
        }
        String url = service.uploadProof(new CashHandoverService.UploadProofCommand(content, file.getOriginalFilename(),
                file.getContentType()));
        return ApiResponse.success(new ProofImageUploadResponse(url), "File uploaded");
    }

    @PostMapping
    @Operation(summary = "Hand over your cash",
            description = "Any company user, for their own cash. Starts PENDING; a branch manager, the accounts desk or a "
                    + "company admin approves or rejects it after viewing the proof.")
    public ResponseEntity<ApiResponse<CashHandoverResponse>> submit(@Valid @RequestBody SubmitCashHandoverRequest body) {
        CashHandover saved = service.submit(new CashHandoverService.SubmitCommand(body.branchId(), body.amount(),
                body.mode(), body.reference(), body.remarks(), body.handoverDate(), body.proofImageUrl()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(toResponse(saved, new HashMap<>()), "Sent for approval"));
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve a handover after viewing its proof",
            description = "BRANCH_MANAGER, ACCOUNTS or COMPANY_ADMIN of the branch; never your own. reason = optional note.")
    public ApiResponse<CashHandoverResponse> approve(@PathVariable UUID id,
                                                     @Valid @RequestBody(required = false) CashHandoverDecisionRequest body) {
        return ApiResponse.success(toResponse(service.approve(id, body == null ? null : body.reason()), new HashMap<>()),
                "Cash handover approved");
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Reject a handover", description = "Same people as approve; a reason is required.")
    public ApiResponse<CashHandoverResponse> reject(@PathVariable UUID id,
                                                    @Valid @RequestBody CashHandoverDecisionRequest body) {
        return ApiResponse.success(toResponse(service.reject(id, body.reason()), new HashMap<>()), "Cash handover rejected");
    }

    @PostMapping("/{id}/void")
    @Operation(summary = "Void an approved handover found to be wrong",
            description = "Whoever approved it, or a company admin; a reason is required. Counts as outstanding again.")
    public ApiResponse<CashHandoverResponse> voidHandover(@PathVariable UUID id,
                                                          @Valid @RequestBody CashHandoverDecisionRequest body) {
        return ApiResponse.success(toResponse(service.voidHandover(id, body.reason()), new HashMap<>()), "Cash handover voided");
    }

    @GetMapping
    @Operation(summary = "List a branch's cash handovers dated in a window",
            description = "Any status, newest first, at most 93 days. Without team access you see only your own.")
    public ApiResponse<List<CashHandoverResponse>> list(
            @RequestParam(required = false) UUID branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID userId) {
        Map<UUID, String> names = new HashMap<>();
        return ApiResponse.success(service.list(branchId, from, to, userId).stream().map(h -> toResponse(h, names)).toList());
    }

    @GetMapping("/pending")
    @Operation(summary = "Handovers waiting for approval",
            description = "Every pending handover of the branch, whatever its date, oldest first (only your own without "
                    + "team access).")
    public ApiResponse<List<CashHandoverResponse>> pending(@RequestParam(required = false) UUID branchId) {
        Map<UUID, String> names = new HashMap<>();
        return ApiResponse.success(service.pending(branchId).stream().map(h -> toResponse(h, names)).toList());
    }

    private CashHandoverResponse toResponse(CashHandover h, Map<UUID, String> names) {
        UUID companyId = h.getCompanyId();
        java.util.function.Function<UUID, String> name = id -> id == null ? null
                : names.computeIfAbsent(id, k -> scope.displayName(k, companyId));
        return new CashHandoverResponse(h.getId(), h.getBranchId(), h.getUserId(), name.apply(h.getUserId()),
                h.getAmount(), h.getMode().name(), h.getReference(), h.getRemarks(), h.getHandoverDate(),
                h.getProofImageUrl(), h.getSubmittedAt(), h.getStatus().name(),
                h.getDecidedBy(), name.apply(h.getDecidedBy()), h.getDecidedAt(), h.getDecisionRemarks(),
                h.getVoidedBy(), name.apply(h.getVoidedBy()), h.getVoidedAt(), h.getVoidReason());
    }
}
