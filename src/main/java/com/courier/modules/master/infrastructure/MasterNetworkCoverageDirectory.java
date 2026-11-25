package com.courier.modules.master.infrastructure;

import com.courier.modules.districtfreight.domain.NetworkCoveragePort;
import com.courier.modules.master.domain.GlobalMasters;
import com.courier.modules.master.domain.NetworkCoverage;
import com.courier.modules.master.domain.PackageType;
import com.courier.modules.master.domain.PackageTypeRepository;
import com.courier.modules.master.domain.Pincode;
import com.courier.modules.master.domain.PincodeRepository;
import com.courier.modules.master.domain.ServiceNetwork;
import com.courier.modules.master.domain.ServiceType;
import com.courier.modules.master.domain.ServiceTypeRepository;
import com.courier.shared.company.CompanyContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Picks the booking's network from its package type (documents) and service type (surface or air), then reads the
 * global pincode's coverage for it. Service and package types belong to the caller's company; the pincode is global.
 */
@Component
@RequiredArgsConstructor
public class MasterNetworkCoverageDirectory implements NetworkCoveragePort {

    private final PincodeRepository pincodes;
    private final ServiceTypeRepository serviceTypes;
    private final PackageTypeRepository packageTypes;

    @Override
    @Transactional(readOnly = true)
    public Optional<NetworkCoverageRef> coverage(String pincodeCode, UUID serviceTypeId, UUID packageTypeId) {
        if (pincodeCode == null || pincodeCode.isBlank() || serviceTypeId == null || packageTypeId == null) {
            return Optional.empty();
        }
        UUID companyId = CompanyContext.getCompanyId().orElse(null);
        if (companyId == null) {
            return Optional.empty();
        }
        ServiceType serviceType = serviceTypes.findByIdWithinCompany(serviceTypeId, companyId).orElse(null);
        PackageType packageType = packageTypes.findByIdWithinCompany(packageTypeId, companyId).orElse(null);
        if (serviceType == null || packageType == null) {
            return Optional.empty();
        }
        ServiceNetwork network = ServiceNetwork.of(packageType.isDocumentType(), serviceType.getNetwork());
        String code = pincodeCode.trim();
        Pincode pincode = CompanyContext.runAs(GlobalMasters.PLATFORM_COMPANY_ID,
                () -> pincodes.findByCodeWithinCompany(code, GlobalMasters.PLATFORM_COMPANY_ID).orElse(null));
        if (pincode == null) {
            return Optional.empty();
        }
        NetworkCoverage c = pincode.coverage(network);
        return Optional.of(new NetworkCoverageRef(network.label(),
                pincode.isServiceable() && c.serviceableOr(true), c.oda(), c.tatHours()));
    }
}
