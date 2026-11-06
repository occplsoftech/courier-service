package com.courier.modules.customerportal.rates.application;

import com.courier.modules.customerportal.rates.application.MarkupCalculator.Markup;
import com.courier.modules.customerportal.rates.domain.ChargeComponent;
import com.courier.modules.customerportal.rates.domain.PricingMarkupRule;
import com.courier.modules.customerportal.rates.domain.PricingMarkupRuleRepository;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.audit.domain.AuditAction;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.security.Roles;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Which markup applies to an external rate, and the administration of markup rules.
 * Resolution: the most specific rule in force (customer + provider, customer, provider, all); none in
 * force means {@code app.customer-portal.default-markup-percent} on FREIGHT.
 */
@Service
@RequiredArgsConstructor
public class MarkupRuleService {

    private static final String COMPANY_ADMIN = "hasRole('" + Roles.COMPANY_ADMIN + "')";

    private final PricingMarkupRuleRepository repository;
    private final CustomerPortalProperties properties;
    private final AuditService auditService;

    /** No authorization check: called while pricing a quote for an already-resolved customer. */
    @Transactional(readOnly = true)
    public Markup resolve(UUID companyId, String providerCode, UUID customerId, Instant at) {
        return repository.findCandidates(companyId, providerCode, customerId).stream()
                .filter(r -> r.coversInstant(at))
                .max(Comparator.comparingInt(PricingMarkupRule::specificity)
                        .thenComparing(PricingMarkupRule::getEffectiveFrom))
                .map(r -> new Markup(r.getId(), r.isMarkupEnabled(), r.getMarkupPercent(), r.components(),
                        properties.getMarkupGstPercent()))
                .orElseGet(() -> new Markup(null, true, properties.getDefaultMarkupPercent(),
                        EnumSet.of(ChargeComponent.FREIGHT), properties.getMarkupGstPercent()));
    }

    @Transactional(readOnly = true)
    @PreAuthorize(COMPANY_ADMIN)
    public List<PricingMarkupRule> list() {
        return repository.findAllWithinCompany(CompanyContext.requireCompanyId());
    }

    /**
     * Adds a rule. A rule of exactly the same scope still open at the new start is closed at that instant,
     * so two rules of one scope never overlap and history stays intact.
     */
    @Transactional
    @PreAuthorize(COMPANY_ADMIN)
    public PricingMarkupRule create(CreateCommand c) {
        UUID companyId = CompanyContext.requireCompanyId();
        validate(c);
        Instant from = c.effectiveFrom() == null ? Instant.now() : c.effectiveFrom();
        if (c.effectiveTo() != null && !c.effectiveTo().isAfter(from)) {
            throw new BusinessRuleException("The end must be after the start.");
        }
        String provider = blankToNull(c.providerCode());
        for (PricingMarkupRule existing : repository.findAllWithinCompany(companyId)) {
            boolean sameScope = Objects.equals(existing.getProviderCode(), provider)
                    && Objects.equals(existing.getCustomerId(), c.customerId());
            boolean openAtStart = existing.getEffectiveTo() == null || existing.getEffectiveTo().isAfter(from);
            if (sameScope && openAtStart) {
                if (!existing.getEffectiveFrom().isBefore(from)) {
                    throw new BusinessRuleException("A rule for this scope already starts at or after that time.");
                }
                existing.setEffectiveTo(from);
                repository.save(existing);
                auditService.record(AuditAction.PRICING_MARKUP_RULE_CLOSED, "PricingMarkupRule", existing.getId(),
                        Map.of("effectiveTo", from.toString()));
            }
        }
        PricingMarkupRule rule = repository.save(PricingMarkupRule.builder()
                .providerCode(provider)
                .customerId(c.customerId())
                .markupEnabled(c.enabled())
                .markupPercent(c.percent())
                .appliesTo(c.components().stream().map(Enum::name).sorted().collect(Collectors.joining(",")))
                .effectiveFrom(from)
                .effectiveTo(c.effectiveTo())
                .notes(blankToNull(c.notes()))
                .build());
        Map<String, Object> details = new HashMap<>();
        details.put("providerCode", provider == null ? "ALL" : provider);
        details.put("customerId", c.customerId() == null ? "ALL" : c.customerId().toString());
        details.put("enabled", c.enabled());
        details.put("percent", c.percent().toPlainString());
        details.put("appliesTo", rule.getAppliesTo());
        details.put("effectiveFrom", from.toString());
        auditService.record(AuditAction.PRICING_MARKUP_RULE_CREATED, "PricingMarkupRule", rule.getId(), details);
        return rule;
    }

    /** Stops a rule now (kept in the history). Refused for a rule that has already ended. */
    @Transactional
    @PreAuthorize(COMPANY_ADMIN)
    public PricingMarkupRule end(UUID ruleId) {
        UUID companyId = CompanyContext.requireCompanyId();
        PricingMarkupRule rule = repository.findAllWithinCompany(companyId).stream().filter(r -> r.getId().equals(ruleId)).findFirst()
                .orElseThrow(() -> new com.courier.shared.exception.ResourceNotFoundException("PricingMarkupRule", ruleId));
        Instant now = Instant.now();
        if (rule.getEffectiveTo() != null && !rule.getEffectiveTo().isAfter(now)) {
            throw new BusinessRuleException("This rule has already ended.");
        }
        // A rule that has not started yet ends where it starts, so it never applies.
        rule.setEffectiveTo(rule.getEffectiveFrom().isAfter(now) ? rule.getEffectiveFrom() : now);
        PricingMarkupRule saved = repository.save(rule);
        auditService.record(AuditAction.PRICING_MARKUP_RULE_CLOSED, "PricingMarkupRule", saved.getId(),
                Map.of("effectiveTo", saved.getEffectiveTo().toString(), "by", "end now"));
        return saved;
    }

    /** Which markup a customer gets on a courier right now (the winning rule, or the default), for staff to check. */
    @Transactional(readOnly = true)
    @PreAuthorize(COMPANY_ADMIN)
    public Markup check(String providerCode, UUID customerId) {
        return resolve(CompanyContext.requireCompanyId(), blankToNull(providerCode), customerId, Instant.now());
    }

    private static void validate(CreateCommand c) {
        if (c.percent() == null || c.percent().signum() < 0 || c.percent().compareTo(new BigDecimal("100")) > 0) {
            throw new BusinessRuleException("The markup must be between 0 and 100 percent.");
        }
        if (c.percent().scale() > 4) {
            throw new BusinessRuleException("The markup may have at most 4 decimal places.");
        }
        if (c.components() == null || c.components().isEmpty()) {
            throw new BusinessRuleException("Name at least one charge the markup applies to.");
        }
        if (c.components().contains(ChargeComponent.TAX)) {
            throw new BusinessRuleException("Tax is never marked up.");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase(java.util.Locale.ROOT);
    }

    public record CreateCommand(String providerCode, UUID customerId, boolean enabled, BigDecimal percent,
                                Set<ChargeComponent> components, Instant effectiveFrom, Instant effectiveTo,
                                String notes) {
    }
}
