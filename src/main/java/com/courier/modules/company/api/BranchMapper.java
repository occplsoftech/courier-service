package com.courier.modules.company.api;

import com.courier.modules.company.api.dto.BranchResponse;
import com.courier.modules.company.api.dto.BranchSearchRequest;
import com.courier.modules.company.api.dto.BranchSummaryResponse;
import com.courier.modules.company.api.dto.BranchUserRequest;
import com.courier.modules.company.api.dto.BranchUserResponse;
import com.courier.modules.company.api.dto.CreateBranchRequest;
import com.courier.modules.company.api.dto.UpdateBranchRequest;
import com.courier.modules.company.application.BranchService;
import com.courier.modules.company.application.command.CreateBranchCommand;
import com.courier.modules.company.application.command.UpdateBranchCommand;
import com.courier.modules.company.domain.Branch;
import com.courier.modules.company.domain.BranchCriteria;
import org.springframework.stereotype.Component;

/** Wire contract ↔ application/domain types for branches. */
@Component
public class BranchMapper {

    public CreateBranchCommand toCommand(CreateBranchRequest r) {
        return new CreateBranchCommand(
                r.branchCode(), r.branchName(), r.branchType(),
                r.email(), r.mobile(), r.alternateMobile(), r.managerId(),
                r.addressLine1(), r.addressLine2(), r.country(), r.state(), r.city(),
                r.district(), r.taluka(), r.postalCode(), r.latitude(), r.longitude(),
                r.openingTime(), r.closingTime(), r.workingDays(),
                r.allowBooking(), r.allowDelivery(), r.allowPickup(),
                r.allowManifest(), r.allowCashCollection(), r.allowWallet(), r.instantCommission(),
                r.remarks(), r.gstNumber(), r.panNumber(),
                r.gstPercentage(), r.commissionOnOtherCharges(),
                r.commissionOnBasicFreight(), r.companyServiceChargePercentage(),
                r.drsChargePerQty(),
                r.deliveryCommissionRatePerKg(), r.deliveryCommissionMinWeightKg(),
                toCommand(r.branchUser()));
    }

    private CreateBranchCommand.NewBranchUser toCommand(BranchUserRequest r) {
        return r == null ? null : new CreateBranchCommand.NewBranchUser(
                r.email(), r.firstName(), r.lastName(), r.mobile(), r.password());
    }

    public UpdateBranchCommand toCommand(UpdateBranchRequest r) {
        return new UpdateBranchCommand(
                r.branchCode(), r.branchName(), r.branchType(),
                r.email(), r.mobile(), r.alternateMobile(),
                r.addressLine1(), r.addressLine2(), r.country(), r.state(), r.city(),
                r.district(), r.taluka(), r.postalCode(), r.latitude(), r.longitude(),
                r.openingTime(), r.closingTime(), r.workingDays(),
                r.allowBooking(), r.allowDelivery(), r.allowPickup(),
                r.allowManifest(), r.allowCashCollection(), r.allowWallet(), r.instantCommission(),
                r.remarks(), r.gstNumber(), r.panNumber(),
                r.gstPercentage(), r.commissionOnOtherCharges(),
                r.commissionOnBasicFreight(), r.companyServiceChargePercentage(),
                r.drsChargePerQty(),
                r.deliveryCommissionRatePerKg(), r.deliveryCommissionMinWeightKg(),
                r.version());
    }

    public BranchCriteria toCriteria(BranchSearchRequest r) {
        BranchSearchRequest safe = r == null ? BranchSearchRequest.empty() : r;
        return new BranchCriteria(
                safe.companyId(), safe.branchType(), safe.status(), safe.managerId(),
                safe.city(), safe.state(), safe.postalCode(),
                safe.allowBooking(), safe.allowDelivery(), safe.allowPickup(),
                null, safe.search());
    }

    /**
     * The create response, alone in carrying the branch's new login account — and, when the
     * server generated it, the only place that password is ever readable.
     */
    public BranchResponse toResponse(BranchService.BranchCreation created) {
        BranchResponse branch = toResponse(created.branch());
        return new BranchResponse(
                branch.id(), branch.companyId(), branch.branchCode(), branch.branchName(),
                branch.branchType(), branch.status(),
                branch.email(), branch.mobile(), branch.alternateMobile(), branch.managerId(),
                branch.addressLine1(), branch.addressLine2(), branch.country(), branch.state(),
                branch.city(), branch.district(), branch.taluka(), branch.postalCode(),
                branch.latitude(), branch.longitude(),
                branch.openingTime(), branch.closingTime(), branch.workingDays(),
                branch.allowBooking(), branch.allowDelivery(), branch.allowPickup(),
                branch.allowManifest(), branch.allowCashCollection(), branch.allowWallet(),
                branch.instantCommission(),
                branch.remarks(), branch.gstNumber(), branch.panNumber(),
                branch.gstPercentage(), branch.commissionOnOtherCharges(),
                branch.commissionOnBasicFreight(), branch.companyServiceChargePercentage(),
                branch.drsChargePerQty(),
                branch.deliveryCommissionRatePerKg(), branch.deliveryCommissionMinWeightKg(),
                branch.createdBy(), branch.createdDate(), branch.updatedBy(),
                branch.updatedDate(), branch.version(),
                new BranchUserResponse(created.userId(), created.userEmail(),
                        created.temporaryPassword(), created.assignedAsManager(),
                        created.branchManagerRoleId(), created.branchManagerRoleCode()),
                branch.courierPickupLocation());
    }

    public BranchResponse toResponse(Branch b) {
        return new BranchResponse(
                b.getId(), b.getCompanyId(), b.getBranchCode(), b.getBranchName(),
                b.getBranchType(), b.getStatus(),
                b.getEmail(), b.getMobile(), b.getAlternateMobile(), b.getManagerId(),
                b.getAddressLine1(), b.getAddressLine2(), b.getCountry(), b.getState(), b.getCity(),
                b.getDistrict(), b.getTaluka(), b.getPostalCode(), b.getLatitude(), b.getLongitude(),
                b.getOpeningTime(), b.getClosingTime(), b.getWorkingDays(),
                b.isAllowBooking(), b.isAllowDelivery(), b.isAllowPickup(),
                b.isAllowManifest(), b.isAllowCashCollection(), b.isAllowWallet(),
                b.isInstantCommission(),
                b.getRemarks(), b.getGstNumber(), b.getPanNumber(),
                b.getGstPercentage(), b.getCommissionOnOtherCharges(),
                b.getCommissionOnBasicFreight(), b.getCompanyServiceChargePercentage(),
                b.getDrsChargePerQty(),
                b.getDeliveryCommissionRatePerKg(), b.getDeliveryCommissionMinWeightKg(),
                b.getCreatedBy(), b.getCreatedAt(), b.getUpdatedBy(), b.getUpdatedAt(), b.getVersion(),
                null, b.getCourierPickupLocation());
    }

    public BranchSummaryResponse toSummary(Branch b) {
        return new BranchSummaryResponse(
                b.getId(), b.getCompanyId(), b.getBranchCode(), b.getBranchName(),
                b.getBranchType(), b.getStatus(),
                b.getCity(), b.getState(), b.getPostalCode(), b.getManagerId(),
                b.isAllowBooking(), b.isAllowDelivery(),
                b.getCreatedAt(), b.getVersion(), b.getGstPercentage(), b.getAssignedHubId());
    }
}
