package com.courier.modules.customerpricing.application;

import com.courier.modules.customer.domain.Customer;
import com.courier.modules.customer.domain.CustomerRepository;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.audit.domain.AuditAction;
import com.courier.shared.exception.ResourceNotFoundException;
import com.courier.shared.security.Roles;
import com.courier.shared.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Whether a business customer is priced from their own rate card ("business rates") or from the company's rates. With
 * business rates on, the customer's zone rate card applies where it covers the destination and weight, and the company
 * rates cover the rest; with it off the rate card is ignored. Changed by the company admin.
 */
@Service
@RequiredArgsConstructor
public class CustomerRateSourceService {

    private static final String COMPANY_ADMIN = "hasRole('" + Roles.COMPANY_ADMIN + "')";

    private final CustomerRepository customers;
    private final AuditService auditService;

    public record SourceView(UUID customerId, boolean useBusinessRates) {
    }

    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public SourceView get(UUID customerId) {
        UUID companyId = SecurityUtils.requireCurrentUser().companyId();
        return new SourceView(customerId, require(companyId, customerId).isUseBusinessRates());
    }

    @Transactional
    @PreAuthorize(COMPANY_ADMIN)
    public SourceView set(UUID customerId, boolean useBusinessRates) {
        UUID companyId = SecurityUtils.requireCurrentUser().companyId();
        Customer c = require(companyId, customerId);
        if (c.isUseBusinessRates() != useBusinessRates) {
            c.setUseBusinessRates(useBusinessRates);
            customers.save(c);
            auditService.record(AuditAction.CUSTOMER_RATE_SOURCE_CHANGED, "Customer", customerId,
                    Map.of("rates", useBusinessRates ? "BUSINESS" : "COMPANY"));
        }
        return new SourceView(customerId, useBusinessRates);
    }

    private Customer require(UUID companyId, UUID customerId) {
        return customers.findByIdWithinCompany(customerId, companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", customerId));
    }
}
