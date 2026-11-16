package com.courier.modules.customer.api.dto;

import com.courier.modules.customer.domain.BillingFrequency;
import com.courier.modules.customer.domain.CustomerPaymentMode;
import com.courier.modules.customer.domain.CustomerStatus;
import com.courier.modules.customer.domain.CustomerType;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Full representation of a customer, including its addresses. Nulls are serialised. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "CustomerResponse", description = "Customer in full, with its addresses")
public record CustomerResponse(
        UUID id, UUID companyId, String customerCode, CustomerType customerType,
        String companyName, String firstName, String middleName, String lastName,
        @Schema(description = "Company name for a business, otherwise the person's full name")
        String displayName,
        String mobile, String alternateMobile, String email,
        String gstNumber, String panNumber,
        String website,
        @Schema(description = "True when a brand logo is uploaded; fetch it with GET /customers/{id}/logo") boolean hasLogo,
        @Schema(description = "The customer that registered this one, or null") UUID parentCustomerId,
        boolean whatsappEnabled, boolean smsEnabled, boolean emailEnabled,
        String contactPerson, CustomerPaymentMode defaultPaymentMode, BillingFrequency billingFrequency,
        String billingAddressLine1, String billingAddressLine2, String billingCity,
        String billingState, String billingPincode,
        CustomerStatus status,
        UUID createdBy, Instant createdDate, UUID updatedBy, Instant updatedDate, Long version,
        List<CustomerAddressResponse> addresses,
        @Schema(description = "The branch this business customer belongs to; its couriers are the customer's default (13.2)") UUID homeBranchId
) {
}
