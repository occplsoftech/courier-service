package com.courier.modules.manifest.application;

import com.courier.modules.manifest.domain.CoLoader;
import com.courier.modules.manifest.domain.CoLoaderRepository;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Masters → Co-Loaders: the company's outside transporters / agents used at outscan and local delivery. */
@Service
@RequiredArgsConstructor
public class CoLoaderService {

    private static final String WRITERS = "hasAnyRole('COMPANY_ADMIN','BRANCH_MANAGER')";

    private final CoLoaderRepository repository;

    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public List<CoLoader> list(boolean includeInactive) {
        return repository.list(CompanyContext.requireCompanyId(), includeInactive);
    }

    /** An active co-loader of this company, or a refusal naming why. */
    @Transactional(readOnly = true)
    public CoLoader requireActive(UUID id) {
        CoLoader c = repository.findWithinCompany(id, CompanyContext.requireCompanyId())
                .orElseThrow(() -> new ResourceNotFoundException("CoLoader", id));
        if (!c.isActive()) {
            throw new BusinessRuleException("Co-loader %s is switched off.".formatted(c.getName()));
        }
        return c;
    }

    @Transactional
    @PreAuthorize(WRITERS)
    public CoLoader create(String name, String contactPerson, String phone, String notes) {
        UUID companyId = CompanyContext.requireCompanyId();
        String n = clean(name, 150);
        if (n == null) throw new BusinessRuleException("Enter the co-loader's name.");
        if (repository.existsByCompanyIdAndNameIgnoreCase(companyId, n)) {
            throw new BusinessRuleException("Co-loader %s already exists.".formatted(n));
        }
        CoLoader c = new CoLoader();
        c.setName(n);
        c.setContactPerson(clean(contactPerson, 150));
        c.setPhone(clean(phone, 20));
        c.setNotes(clean(notes, 500));
        c.setActive(true);
        return repository.save(c);
    }

    @Transactional
    @PreAuthorize(WRITERS)
    public CoLoader update(UUID id, String name, String contactPerson, String phone, String notes, Boolean active) {
        UUID companyId = CompanyContext.requireCompanyId();
        CoLoader c = repository.findWithinCompany(id, companyId).orElseThrow(() -> new ResourceNotFoundException("CoLoader", id));
        String n = clean(name, 150);
        if (n != null && !n.equalsIgnoreCase(c.getName())) {
            if (repository.existsByCompanyIdAndNameIgnoreCase(companyId, n)) {
                throw new BusinessRuleException("Co-loader %s already exists.".formatted(n));
            }
            c.setName(n);
        } else if (n != null) {
            c.setName(n);
        }
        if (contactPerson != null) c.setContactPerson(clean(contactPerson, 150));
        if (phone != null) c.setPhone(clean(phone, 20));
        if (notes != null) c.setNotes(clean(notes, 500));
        if (active != null) c.setActive(active);
        return repository.save(c);
    }

    private static String clean(String v, int max) {
        if (v == null) return null;
        String t = v.trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }

}
