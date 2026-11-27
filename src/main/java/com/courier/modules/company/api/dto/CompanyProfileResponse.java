package com.courier.modules.company.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The caller's own company, trimmed to what a printed document (consignment note,
 * invoice) puts in its letterhead. Deliberately not {@link CompanyResponse} — that DTO
 * is SUPER_ADMIN-only wire shape and carries subscription/billing fields no branch user
 * should see; this one is safe for any authenticated company user to read.
 */
@Schema(name = "CompanyProfileResponse", description = "Own-company letterhead details")
public record CompanyProfileResponse(

        String companyName,
        String logo,
        String addressLine1,
        String addressLine2,
        String city,
        String state,
        String postalCode,
        String gstNumber,
        String mobile,
        String website,
        /** Support email (Settings), else the company's own email — the DRS / manifest letterhead. */
        String email
) {
}
