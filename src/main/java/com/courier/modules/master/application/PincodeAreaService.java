package com.courier.modules.master.application;

import com.courier.modules.master.application.port.PincodePostalLookupProvider;
import com.courier.modules.master.application.port.PincodePostalLookupProvider.PostOffice;
import com.courier.modules.master.domain.Area;
import com.courier.modules.master.domain.AreaRepository;
import com.courier.modules.master.domain.CityRepository;
import com.courier.modules.master.domain.GlobalMasters;
import com.courier.modules.master.domain.MasterDataCriteria;
import com.courier.modules.master.domain.MasterDataSpecifications;
import com.courier.modules.master.domain.Pincode;
import com.courier.modules.master.domain.PincodeArea;
import com.courier.modules.master.domain.PincodeAreaRepository;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.ResourceNotFoundException;
import com.courier.shared.security.Roles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Every Area one pincode's postal record names, and the ODA/amount an operator sets per
 * area. See {@link PincodeArea} for why this exists rather than the single {@code
 * Pincode.areaId} it sits alongside.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PincodeAreaService {

    private static final String WRITE =
            "hasAnyRole('" + Roles.SUPER_ADMIN + "', '" + Roles.COMPANY_ADMIN + "')";
    private static final String READ = "isAuthenticated()";

    private final PincodeAreaRepository pincodeAreas;
    private final AreaRepository areas;
    private final CityRepository cities;
    private final MasterNameResolver names;
    private final PincodePostalLookupProvider postalLookup;
    private final GeographyAutoResolver geographyResolver;

    public record Row(PincodeArea link, String areaName, String cityName) {
    }

    @Transactional(readOnly = true)
    @PreAuthorize(READ)
    public List<Row> list(UUID pincodeId) {
        return CompanyContext.runAs(GlobalMasters.PLATFORM_COMPANY_ID, () -> {
            UUID companyId = GlobalMasters.PLATFORM_COMPANY_ID;
            List<PincodeArea> links =
                    pincodeAreas.findByCompanyIdAndPincodeIdOrderByPrimaryDescCreatedAtAsc(companyId, pincodeId);
            return toRows(companyId, links);
        });
    }

    @Transactional
    @PreAuthorize(WRITE)
    public Row updateOda(UUID pincodeId, UUID linkId, Boolean odaApplicable, BigDecimal odaAmount) {
        return update(pincodeId, linkId, odaApplicable, odaAmount, null);
    }

    /** ODA and/or serviceability of one area of a pincode; each field optional. */
    @Transactional
    @PreAuthorize(WRITE)
    public Row update(UUID pincodeId, UUID linkId, Boolean odaApplicable, BigDecimal odaAmount, Boolean serviceable) {
        return CompanyContext.runAs(GlobalMasters.PLATFORM_COMPANY_ID, () -> {
            UUID companyId = GlobalMasters.PLATFORM_COMPANY_ID;
            PincodeArea link = pincodeAreas.findByIdAndCompanyIdAndPincodeId(linkId, companyId, pincodeId)
                    .orElseThrow(() -> new ResourceNotFoundException("Pincode area", linkId));
            if (odaApplicable != null) {
                link.setOdaApplicable(odaApplicable);
            }
            if (odaAmount != null) {
                link.setOdaAmount(odaAmount);
            }
            if (serviceable != null) {
                link.setServiceable(serviceable);
            }
            link.applyInvariants();
            PincodeArea saved = pincodeAreas.saveAndFlush(link);
            return toRows(companyId, List.of(saved)).get(0);
        });
    }

    private List<Row> toRows(UUID companyId, List<PincodeArea> links) {
        if (links.isEmpty()) {
            return List.of();
        }
        Map<UUID, Area> areaById = loadAreas(companyId, links.stream().map(PincodeArea::getAreaId).toList());
        Set<UUID> cityIds = areaById.values().stream().map(Area::getCityId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, String> cityNames = names.globalNamesById(cities, cityIds);
        return links.stream()
                .map(link -> {
                    Area area = areaById.get(link.getAreaId());
                    String cityName = area == null ? null : cityNames.get(area.getCityId());
                    return new Row(link, area == null ? null : area.getName(), cityName);
                })
                .toList();
    }

    /**
     * Ensures a primary link row exists for the pincode's own {@code areaId}, then
     * best-effort discovers every other Area the postal directory names for this code and
     * links those too. Never throws — a pincode's creation/update must succeed regardless
     * of whether the postal directory is reachable or has anything to say.
     *
     * <p>Called from {@link PincodeServiceImpl#create}/{@code update}, inside the same
     * transaction — the primary row (a plain insert, no network) is as atomic with the
     * pincode write as the rest of that method's own invariants; the alternates lookup is
     * wrapped in its own try/catch so a slow or failed network call degrades to "no
     * alternates yet," never a failed save.
     */
    public void syncAreas(Pincode pincode) {
        CompanyContext.runAs(GlobalMasters.PLATFORM_COMPANY_ID, () -> {
            UUID companyId = GlobalMasters.PLATFORM_COMPANY_ID;
            try {
                upsertLink(companyId, pincode.getId(), pincode.getAreaId(), true);
            } catch (Exception e) {
                log.warn("Pincode {}: failed to record its own primary area link: {}",
                        pincode.getCode(), e.getMessage());
            }

            try {
                List<PostOffice> matches = postalLookup.lookup(pincode.getCode());
                for (PostOffice postOffice : matches) {
                    Area area = geographyResolver.resolveArea(postOffice).area();
                    if (area.getId().equals(pincode.getAreaId())) {
                        continue;
                    }
                    if (pincodeAreas.findByCompanyIdAndPincodeIdAndAreaId(companyId, pincode.getId(), area.getId())
                            .isPresent()) {
                        continue;
                    }
                    upsertLink(companyId, pincode.getId(), area.getId(), false);
                }
            } catch (Exception e) {
                log.warn("Pincode {}: alternate-area discovery failed: {}", pincode.getCode(), e.getMessage());
            }
        });
    }

    private void upsertLink(UUID companyId, UUID pincodeId, UUID areaId, boolean primary) {
        if (areaId == null) {
            return;
        }
        PincodeArea link = pincodeAreas.findByCompanyIdAndPincodeIdAndAreaId(companyId, pincodeId, areaId)
                .orElseGet(PincodeArea::new);
        link.setPincodeId(pincodeId);
        link.setAreaId(areaId);
        if (primary) {
            link.setPrimary(true);
        }
        link.applyInvariants();
        pincodeAreas.saveAndFlush(link);
    }

    private Map<UUID, Area> loadAreas(UUID companyId, List<UUID> areaIds) {
        if (areaIds.isEmpty()) {
            return Map.of();
        }
        MasterDataCriteria criteria = MasterDataCriteria.none()
                .withCompanyId(companyId)
                .withIds(Set.copyOf(areaIds));
        Map<UUID, Area> byId = new LinkedHashMap<>();
        areas.findAll(MasterDataSpecifications.matching(criteria))
                .forEach(area -> byId.put(area.getId(), area));
        return byId;
    }
}
