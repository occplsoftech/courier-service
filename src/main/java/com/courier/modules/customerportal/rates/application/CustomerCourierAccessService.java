package com.courier.modules.customerportal.rates.application;

import com.courier.modules.customer.application.CustomerPortalLookup;
import com.courier.modules.customerportal.rates.domain.CourierProviderAdapter;
import com.courier.modules.customerportal.rates.domain.CustomerCourierAccess;
import com.courier.modules.customerportal.rates.domain.CustomerCourierAccessRepository;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.audit.domain.AuditAction;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import com.courier.shared.security.Roles;
import com.courier.shared.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Which couriers a business customer sees in the customer portal. A customer with none assigned sees OCCPL Network only; with
 * some assigned, exactly those (OCCPL Network included only when it is one of them). Managed by the company admin.
 */
@Service
@RequiredArgsConstructor
public class CustomerCourierAccessService {

    public static final String DEFAULT_CODE = "OCCPL_NETWORK";
    private static final String COMPANY_ADMIN = "hasRole('" + Roles.COMPANY_ADMIN + "')";

    private final CustomerCourierAccessRepository repository;
    private final List<CourierProviderAdapter> adapters;
    private final CustomerPortalLookup customers;
    private final AuditService auditService;

    public record CourierOption(String code, String name, boolean connected, boolean enabled) {
    }

    public record AccessView(UUID customerId, boolean customised, List<CourierOption> couriers) {
    }

    /** No authorization check: called while pricing a quote for an already-resolved customer. */
    @Transactional(readOnly = true)
    public Set<String> allowedCodes(UUID companyId, UUID customerId) {
        List<String> codes = repository.findCodes(companyId, customerId);
        return codes.isEmpty() ? Set.of(DEFAULT_CODE) : Set.copyOf(codes);
    }

    @Transactional(readOnly = true)
    @PreAuthorize(COMPANY_ADMIN)
    public AccessView view(UUID customerId) {
        UUID companyId = SecurityUtils.requireCurrentUser().companyId();
        requireCustomer(companyId, customerId);
        return build(companyId, customerId);
    }

    /** Replaces the customer's couriers. An empty list puts the customer back on the default (OCCPL Network only). */
    @Transactional
    @PreAuthorize(COMPANY_ADMIN)
    public AccessView save(UUID customerId, List<String> providerCodes) {
        UUID companyId = SecurityUtils.requireCurrentUser().companyId();
        requireCustomer(companyId, customerId);
        Set<String> known = new LinkedHashSet<>();
        adapters.forEach(a -> known.add(a.code()));
        Set<String> wanted = new LinkedHashSet<>();
        for (String raw : providerCodes == null ? List.<String>of() : providerCodes) {
            String code = raw == null ? "" : raw.trim().toUpperCase();
            if (!known.contains(code)) {
                throw new BusinessRuleException("Unknown courier " + raw + ".");
            }
            wanted.add(code);
        }
        repository.deleteForCustomer(companyId, customerId);
        repository.flush();
        wanted.forEach(code -> repository.save(new CustomerCourierAccess(customerId, code)));
        auditService.record(AuditAction.CUSTOMER_COURIER_ACCESS_CHANGED, "Customer", customerId,
                Map.of("couriers", wanted.isEmpty() ? "(default: OCCPL_NETWORK)" : String.join(",", wanted)));
        return build(companyId, customerId);
    }

    private AccessView build(UUID companyId, UUID customerId) {
        List<String> stored = repository.findCodes(companyId, customerId);
        Set<String> enabled = stored.isEmpty() ? Set.of(DEFAULT_CODE) : Set.copyOf(stored);
        List<CourierOption> options = adapters.stream()
                .map(a -> new CourierOption(a.code(), a.displayName(), a.configured(companyId), enabled.contains(a.code())))
                .toList();
        return new AccessView(customerId, !stored.isEmpty(), options);
    }

    private void requireCustomer(UUID companyId, UUID customerId) {
        customers.findActiveBusinessCustomer(companyId, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Business customer", customerId));
    }
}
