package com.courier.modules.master.infrastructure;

import com.courier.modules.districtfreight.domain.PincodeCoverageLookupPort;
import com.courier.modules.master.domain.Area;
import com.courier.modules.master.domain.AreaRepository;
import com.courier.modules.master.domain.City;
import com.courier.modules.master.domain.CityRepository;
import com.courier.modules.master.domain.District;
import com.courier.modules.master.domain.DistrictRepository;
import com.courier.modules.master.domain.GlobalMasters;
import com.courier.modules.master.domain.Pincode;
import com.courier.modules.master.domain.PincodeArea;
import com.courier.modules.master.domain.PincodeAreaRepository;
import com.courier.modules.master.domain.PincodeRepository;
import com.courier.shared.company.CompanyContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Answers District Level Freight's "which District does this destination pincode belong
 * to" question by walking the existing global geography chain — {@code Pincode.areaId ->
 * Area.cityId -> City.districtId -> District} — the same masters {@code GeographyAutoResolver}
 * and the Pincode Areas card already maintain, crossed into via {@link CompanyContext#runAs}
 * exactly like {@code MasterDistrictFreightDistrictDirectory} does for a District looked up
 * by id alone.
 */
@Component
@RequiredArgsConstructor
public class MasterDistrictFreightCoverageDirectory implements PincodeCoverageLookupPort {

    private final PincodeRepository pincodeRepository;
    private final AreaRepository areaRepository;
    private final CityRepository cityRepository;
    private final DistrictRepository districtRepository;
    private final PincodeAreaRepository pincodeAreaRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<CoverageRef> findByPincode(String pincodeCode) {
        if (pincodeCode == null || pincodeCode.isBlank()) {
            return Optional.empty();
        }
        String trimmed = pincodeCode.trim();
        return CompanyContext.runAs(GlobalMasters.PLATFORM_COMPANY_ID, () -> {
            Pincode pincode = pincodeRepository
                    .findByCodeWithinCompany(trimmed, GlobalMasters.PLATFORM_COMPANY_ID)
                    .orElse(null);
            if (pincode == null) {
                return Optional.empty();
            }
            Area area = areaRepository
                    .findByIdWithinCompany(pincode.getAreaId(), GlobalMasters.PLATFORM_COMPANY_ID)
                    .orElse(null);
            if (area == null) {
                return Optional.empty();
            }
            City city = cityRepository
                    .findByIdWithinCompany(area.getCityId(), GlobalMasters.PLATFORM_COMPANY_ID)
                    .orElse(null);
            if (city == null) {
                return Optional.empty();
            }
            District district = districtRepository
                    .findByIdWithinCompany(city.getDistrictId(), GlobalMasters.PLATFORM_COMPANY_ID)
                    .orElse(null);
            if (district == null) {
                return Optional.empty();
            }
            return Optional.of(new CoverageRef(pincode.getId(), pincode.getCode(), pincode.isServiceable(),
                    pincode.isOdaApplicable(), district.getId(), district.getCode(), district.getName(),
                    district.isActive(), pincode.getName(), city.getId()));
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CoverageRef> findByPincodeAndArea(String pincodeCode, UUID areaId) {
        if (pincodeCode == null || pincodeCode.isBlank() || areaId == null) {
            return Optional.empty();
        }
        String trimmed = pincodeCode.trim();
        return CompanyContext.runAs(GlobalMasters.PLATFORM_COMPANY_ID, () -> {
            Pincode pincode = pincodeRepository
                    .findByCodeWithinCompany(trimmed, GlobalMasters.PLATFORM_COMPANY_ID)
                    .orElse(null);
            if (pincode == null) {
                return Optional.empty();
            }
            PincodeArea link = pincodeAreaRepository
                    .findByCompanyIdAndPincodeIdAndAreaId(GlobalMasters.PLATFORM_COMPANY_ID, pincode.getId(), areaId)
                    .orElse(null);
            if (link == null) {
                return Optional.empty();
            }
            Area area = areaRepository
                    .findByIdWithinCompany(areaId, GlobalMasters.PLATFORM_COMPANY_ID)
                    .orElse(null);
            if (area == null) {
                return Optional.empty();
            }
            City city = cityRepository
                    .findByIdWithinCompany(area.getCityId(), GlobalMasters.PLATFORM_COMPANY_ID)
                    .orElse(null);
            if (city == null) {
                return Optional.empty();
            }
            District district = districtRepository
                    .findByIdWithinCompany(city.getDistrictId(), GlobalMasters.PLATFORM_COMPANY_ID)
                    .orElse(null);
            if (district == null) {
                return Optional.empty();
            }
            return Optional.of(new CoverageRef(pincode.getId(), pincode.getCode(), pincode.isServiceable() && link.isServiceable(),
                    link.isOdaApplicable(), district.getId(), district.getCode(), district.getName(),
                    district.isActive(), area.getName() + ", " + city.getName(), city.getId()));
        });
    }
}
