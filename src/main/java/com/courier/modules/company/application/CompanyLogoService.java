package com.courier.modules.company.application;

import com.courier.modules.company.domain.CompanyRepository;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.audit.domain.AuditAction;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import com.courier.shared.image.LogoImages;
import com.courier.shared.security.Roles;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * The company's own logo, uploaded by its company admin (Settings → Company Logo). Printed on every letterhead
 * (DRS, manifest, consignment note …) through {@code GET /company-profile}; wins over the link a super admin typed.
 */
@Service
@RequiredArgsConstructor
public class CompanyLogoService {

    private static final String ENTITY = "Company";

    private final CompanyRepository repository;
    private final AuditService auditService;

    @Transactional
    @PreAuthorize("hasRole('" + Roles.COMPANY_ADMIN + "')")
    public void upload(byte[] bytes) {
        write(LogoImages.toDataUrl(bytes), "uploaded");
    }

    @Transactional
    @PreAuthorize("hasRole('" + Roles.COMPANY_ADMIN + "')")
    public void remove() {
        write(null, "removed");
    }

    private void write(String dataUrl, String what) {
        UUID companyId = CompanyContext.getCompanyId().orElseThrow(() -> new BusinessRuleException(
                "No company is bound to this request. The logo belongs to a company."));
        if (repository.setLogoDataUrl(companyId, dataUrl) == 0) {
            throw new ResourceNotFoundException(ENTITY, companyId);
        }
        auditService.record(AuditAction.COMPANY_UPDATED, ENTITY, companyId, Map.of("logo", what));
    }
}
