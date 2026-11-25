package com.courier.modules.districtfreight.api;

import com.courier.modules.districtfreight.api.dto.CreateDistrictLevelFreightRequest;
import com.courier.modules.districtfreight.api.dto.DistrictLevelFreightResponse;
import com.courier.modules.districtfreight.api.dto.DistrictLevelFreightSearchRequest;
import com.courier.modules.districtfreight.api.dto.FreightCalculationResponse;
import com.courier.modules.districtfreight.api.dto.UpdateDistrictLevelFreightRequest;
import com.courier.modules.districtfreight.application.FreightCalculationResult;
import com.courier.modules.districtfreight.application.command.CreateDistrictLevelFreightCommand;
import com.courier.modules.districtfreight.application.command.UpdateDistrictLevelFreightCommand;
import com.courier.modules.districtfreight.domain.BranchLookupPort;
import com.courier.modules.districtfreight.domain.DistrictLevelFreight;
import com.courier.modules.districtfreight.domain.DistrictLevelFreightCriteria;
import com.courier.modules.districtfreight.domain.DistrictLookupPort;
import com.courier.shared.api.PageResponse;
import com.courier.shared.company.CompanyContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Wire contract &lt;-&gt; application/domain types for District Level Freight. Also resolves
 * {@code branchCode}/{@code branchName}/{@code districtCode}/{@code districtName} onto the
 * response — batched for a page (one call to each lookup port, not one per row), single-item
 * for create/update/get.
 */
@Component
@RequiredArgsConstructor
public class DistrictLevelFreightMapper {

    private final BranchLookupPort branchLookup;
    private final DistrictLookupPort districtLookup;

    public CreateDistrictLevelFreightCommand toCommand(CreateDistrictLevelFreightRequest r) {
        return new CreateDistrictLevelFreightCommand(
                r.branchId(), r.districtId(), r.rate1To15(), r.rate16To50(), r.rate51To100(),
                r.rate101To1000(), r.rate1001To1500(), r.rate1501To2000(),
                r.odaApplicable(), r.odaCharge());
    }

    public UpdateDistrictLevelFreightCommand toCommand(UpdateDistrictLevelFreightRequest r) {
        return new UpdateDistrictLevelFreightCommand(
                r.branchId(), r.districtId(), r.rate1To15(), r.rate16To50(), r.rate51To100(),
                r.rate101To1000(), r.rate1001To1500(), r.rate1501To2000(),
                r.odaApplicable(), r.odaCharge(), r.version());
    }

    public DistrictLevelFreightCriteria toCriteria(DistrictLevelFreightSearchRequest r) {
        DistrictLevelFreightSearchRequest safe = r == null ? DistrictLevelFreightSearchRequest.empty() : r;
        return new DistrictLevelFreightCriteria(safe.branchId(), safe.districtId(), safe.status());
    }

    public DistrictLevelFreightResponse toResponse(DistrictLevelFreight f) {
        UUID companyId = CompanyContext.getCompanyId().orElse(f.getCompanyId());
        BranchLookupPort.BranchRef branch = branchLookup.findBranch(f.getBranchId(), companyId).orElse(null);
        DistrictLookupPort.DistrictRef district = districtLookup.findDistrict(f.getDistrictId()).orElse(null);
        return toResponse(f, branch, district);
    }

    public PageResponse<DistrictLevelFreightResponse> toPage(Page<DistrictLevelFreight> page) {
        UUID companyId = CompanyContext.requireCompanyId();
        Set<UUID> branchIds = page.getContent().stream().map(DistrictLevelFreight::getBranchId)
                .collect(Collectors.toSet());
        Set<UUID> districtIds = page.getContent().stream().map(DistrictLevelFreight::getDistrictId)
                .collect(Collectors.toSet());
        Map<UUID, BranchLookupPort.BranchRef> branches = branchLookup.findBranches(branchIds, companyId);
        Map<UUID, DistrictLookupPort.DistrictRef> districts = districtLookup.findDistricts(districtIds);
        return PageResponse.from(page,
                f -> toResponse(f, branches.get(f.getBranchId()), districts.get(f.getDistrictId())));
    }

    private DistrictLevelFreightResponse toResponse(DistrictLevelFreight f,
                                                      BranchLookupPort.BranchRef branch,
                                                      DistrictLookupPort.DistrictRef district) {
        return new DistrictLevelFreightResponse(
                f.getId(), f.getCompanyId(),
                f.getBranchId(), branch == null ? null : branch.branchCode(), branch == null ? null : branch.branchName(),
                f.getDistrictId(), district == null ? null : district.code(), district == null ? null : district.name(),
                f.getRate1To15(), f.getRate16To50(), f.getRate51To100(),
                f.getRate101To1000(), f.getRate1001To1500(), f.getRate1501To2000(),
                f.isOdaApplicable(), f.getOdaCharge(),
                f.getStatus(),
                f.getCreatedBy(), f.getCreatedAt(), f.getUpdatedBy(), f.getUpdatedAt(), f.getVersion());
    }

    public FreightCalculationResponse toResponse(FreightCalculationResult r) {
        return new FreightCalculationResponse(
                r.matchedFreightId(), r.bookingBranchId(), r.bookingBranchCode(), r.bookingBranchName(),
                r.districtId(), r.districtCode(), r.districtName(), r.destinationPincode(),
                r.destinationCityName(),
                r.chargeableWeight(), r.weightSlabLabel(), r.ratePerKg(), r.baseFreight(),
                r.odaApplicable(), r.odaCharge(), r.totalFreight(),
                r.customerZoneName(), r.customerExtraCharges(), r.tatHours());
    }
}
