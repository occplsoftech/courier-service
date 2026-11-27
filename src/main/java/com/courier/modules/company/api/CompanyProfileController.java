package com.courier.modules.company.api;

import com.courier.modules.company.api.dto.CompanyProfileResponse;
import com.courier.modules.company.domain.Company;
import com.courier.modules.company.domain.CompanyRepository;
import com.courier.modules.company.domain.CompanySettingsRepository;
import com.courier.shared.api.ApiResponse;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The caller's own company letterhead — name, address, GST, contact, website. Distinct
 * from {@link CompanyController}, which is the SUPER_ADMIN-only lifecycle/billing surface
 * gated at the URL level ({@code /api/v1/companies/**}); this is the self-service read
 * every branch/company user needs to print a consignment note, same shape of split as
 * {@link CompanySettingsController}.
 */
@RestController
@RequestMapping("/api/v1/company-profile")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Company Profile", description = "Own-company letterhead details (any authenticated company user)")
public class CompanyProfileController {

    private final CompanyRepository companyRepository;
    private final CompanySettingsRepository settingsRepository;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get the caller's own company letterhead details",
            description = "Name, logo, address, GST number, contact and website — the "
                    + "fields a printed consignment note puts on its letterhead. Resolved "
                    + "from the company bound to the request, not a path id.")
    public ApiResponse<CompanyProfileResponse> get() {
        UUID companyId = CompanyContext.getCompanyId().orElseThrow(() -> new BusinessRuleException(
                "No company is bound to this request. The company profile belongs to a "
                        + "company, so this must be performed by a user of that company."));
        Company company = companyRepository.findByCompanyId(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Company", companyId));

        // Settings page edits supportMobile/website; they override the onboarding values.
        var settings = settingsRepository.findByCompanyId(companyId).orElse(null);
        // Letterhead contact is always the Settings support mobile, never the onboarding company mobile.
        String mobile = settings != null && settings.getSupportMobile() != null
                && !settings.getSupportMobile().isBlank() ? settings.getSupportMobile() : null;
        String website = settings != null && settings.getWebsite() != null
                && !settings.getWebsite().isBlank() ? settings.getWebsite() : company.getWebsite();

        return ApiResponse.success(new CompanyProfileResponse(
                company.getCompanyName(),
                company.getLogo(),
                company.getAddressLine1(),
                company.getAddressLine2(),
                company.getCity(),
                company.getState(),
                company.getPostalCode(),
                company.getGstNumber(),
                mobile,
                website,
                settings != null && settings.getSupportEmail() != null && !settings.getSupportEmail().isBlank()
                        ? settings.getSupportEmail() : company.getEmail()));
    }
}
