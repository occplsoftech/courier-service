package com.courier.modules.master.application;

import com.courier.modules.master.application.command.RegionalZoneCommand;
import com.courier.modules.master.domain.MasterDataCriteria;
import com.courier.modules.master.domain.RegionalZone;
import com.courier.modules.master.domain.RegionalZoneRepository;
import com.courier.modules.master.infrastructure.MasterTable;
import com.courier.modules.master.infrastructure.MasterUniquenessChecker;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.security.Roles;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Regional zones. Global, like the pincodes they group, and written by the same people who may
 * edit a pincode's serviceability: a super admin or a company admin.
 */
@Slf4j
@Service
public class RegionalZoneServiceImpl extends AbstractMasterDataService<RegionalZone>
        implements RegionalZoneService {

    private static final String WRITE =
            "hasAnyRole('" + Roles.SUPER_ADMIN + "', '" + Roles.COMPANY_ADMIN + "')";
    private static final String READ = "isAuthenticated()";

    public RegionalZoneServiceImpl(RegionalZoneRepository regionalZones,
                                   MasterUniquenessChecker uniqueness,
                                   AuditService auditService) {
        super(regionalZones, uniqueness, auditService, "Regional zone", MasterTable.REGIONAL_ZONES);
    }

    @Override
    protected boolean global() {
        return true;
    }

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public RegionalZone create(RegionalZoneCommand command) {
        RegionalZone zone = new RegionalZone();
        applyCommonFields(zone, command.code(), command.name(), command.description(), command.displayOrder());
        return createEntity(zone);
    }

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public RegionalZone update(UUID id, RegionalZoneCommand command) {
        return updateEntity(id, command.expectedVersion(),
                zone -> applyCommonFields(zone, null, command.name(), command.description(), command.displayOrder()));
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READ)
    public RegionalZone getById(UUID id) {
        return doGetById(id);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READ)
    public Page<RegionalZone> search(MasterDataCriteria criteria, Pageable pageable) {
        return doSearch(criteria, pageable);
    }

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public void delete(UUID id) {
        doDelete(id);
    }

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public RegionalZone activate(UUID id) {
        return doActivate(id);
    }

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public RegionalZone deactivate(UUID id) {
        return doDeactivate(id);
    }
}
