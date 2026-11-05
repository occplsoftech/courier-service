package com.courier.modules.company.api.dto;

import com.courier.modules.company.domain.BranchStatus;
import com.courier.modules.company.domain.BranchType;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

/** Full representation of a branch. Nulls are serialised. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "BranchResponse", description = "Branch in full")
public record BranchResponse(
        UUID id, UUID companyId, String branchCode, String branchName,
        BranchType branchType, BranchStatus status,
        String email, String mobile, String alternateMobile, UUID managerId,
        String addressLine1, String addressLine2, String country, String state, String city,
        String district, String taluka, String postalCode,
        BigDecimal latitude, BigDecimal longitude,
        LocalTime openingTime, LocalTime closingTime, String workingDays,
        boolean allowBooking, boolean allowDelivery, boolean allowPickup,
        boolean allowManifest, boolean allowCashCollection, boolean allowWallet,
        boolean instantCommission,
        String remarks,
        String gstNumber, String panNumber,
        BigDecimal gstPercentage, BigDecimal commissionOnOtherCharges,
        BigDecimal commissionOnBasicFreight, BigDecimal companyServiceChargePercentage,
        BigDecimal drsChargePerQty,
        BigDecimal deliveryCommissionRatePerKg, BigDecimal deliveryCommissionMinWeightKg,
        UUID createdBy, Instant createdDate, UUID updatedBy, Instant updatedDate, Long version,

        @Schema(description = "Only on the create response: the account made with the branch")
        BranchUserResponse branchUser,

        @Schema(description = "The name this branch's pickup address has at Delhivery / Shiprocket (needed to forward shipments to them)")
        String courierPickupLocation
) {
}
