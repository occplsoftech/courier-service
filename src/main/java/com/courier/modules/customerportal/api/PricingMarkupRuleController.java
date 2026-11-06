package com.courier.modules.customerportal.api;

import com.courier.modules.customerportal.rates.application.MarkupRuleService;
import com.courier.modules.customerportal.rates.application.MarkupRuleService.CreateCommand;
import com.courier.modules.customerportal.rates.domain.ChargeComponent;
import com.courier.modules.customerportal.rates.domain.PricingMarkupRule;
import com.courier.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Company admins manage the markup on external courier rates (initially 10 %). */
@RestController
@RequestMapping("/api/v1/pricing-markup-rules")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Customer portal pricing", description = "Markup on external courier rates")
public class PricingMarkupRuleController {

    private final MarkupRuleService service;

    @GetMapping
    @Operation(summary = "All markup rules, newest first", description = "`COMPANY_ADMIN`.")
    public ApiResponse<List<RuleResponse>> list() {
        return ApiResponse.success(service.list().stream().map(RuleResponse::of).toList());
    }

    @PostMapping
    @Operation(summary = "Add a markup rule",
            description = "`COMPANY_ADMIN`. Scope: providerCode and customerId, each blank for all. An open rule of the "
                    + "same scope is closed when the new one starts. Never applies to tax. Audited.")
    public ResponseEntity<ApiResponse<RuleResponse>> create(@Valid @RequestBody CreateRuleRequest r) {
        PricingMarkupRule rule = service.create(new CreateCommand(r.providerCode(), r.customerId(), r.enabled(),
                r.percent(), r.appliesTo(), r.effectiveFrom(), r.effectiveTo(), r.notes()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(RuleResponse.of(rule), "Markup rule added"));
    }

    public record CreateRuleRequest(@Size(max = 40) String providerCode, UUID customerId, boolean enabled,
                                    @NotNull BigDecimal percent, @NotEmpty Set<ChargeComponent> appliesTo,
                                    Instant effectiveFrom, Instant effectiveTo, @Size(max = 255) String notes) {
    }

    public record RuleResponse(UUID id, String providerCode, UUID customerId, boolean enabled, BigDecimal percent,
                               Set<ChargeComponent> appliesTo, Instant effectiveFrom, Instant effectiveTo, String notes,
                               Instant createdAt) {
        static RuleResponse of(PricingMarkupRule r) {
            return new RuleResponse(r.getId(), r.getProviderCode(), r.getCustomerId(), r.isMarkupEnabled(),
                    r.getMarkupPercent(), r.components(), r.getEffectiveFrom(), r.getEffectiveTo(), r.getNotes(),
                    r.getCreatedAt());
        }
    }

    @PostMapping("/{id}/end")
    @Operation(summary = "End a markup rule now", description = "`COMPANY_ADMIN`. It stays in the history; a rule not started yet never applies.")
    public ApiResponse<RuleResponse> end(@org.springframework.web.bind.annotation.PathVariable UUID id) {
        return ApiResponse.success(RuleResponse.of(service.end(id)), "Markup rule ended");
    }

    public record CheckResponse(UUID ruleId, boolean isDefault, boolean enabled, BigDecimal percent, Set<ChargeComponent> appliesTo,
                                BigDecimal gstPercent) {
    }

    @GetMapping("/check")
    @Operation(summary = "Which markup applies to a customer on a courier right now",
            description = "`COMPANY_ADMIN`. `providerCode` and `customerId` are optional (blank = any). `isDefault` when no rule is in force.")
    public ApiResponse<CheckResponse> check(@org.springframework.web.bind.annotation.RequestParam(required = false) String providerCode,
                                            @org.springframework.web.bind.annotation.RequestParam(required = false) UUID customerId) {
        var m = service.check(providerCode, customerId);
        return ApiResponse.success(new CheckResponse(m.ruleId(), m.ruleId() == null, m.enabled(), m.percent(), m.components(), m.gstPercent()));
    }
}
