package com.courier.modules.master.application;

import com.courier.modules.master.application.command.ServiceTypeCommand;
import com.courier.modules.master.domain.MasterDataCriteria;
import com.courier.modules.master.domain.ServiceNetwork;
import com.courier.modules.master.domain.ServiceType;
import com.courier.modules.master.domain.ServiceTypeRepository;
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

import java.util.Map;
import java.util.UUID;

/** Service types. */
@Slf4j
@Service
public class ServiceTypeServiceImpl extends AbstractMasterDataService<ServiceType>
        implements ServiceTypeService {

    private static final String WRITE = "hasRole('" + Roles.COMPANY_ADMIN + "')";
    private static final String READ = "isAuthenticated()";

    public ServiceTypeServiceImpl(ServiceTypeRepository serviceTypes,
                                  MasterUniquenessChecker uniqueness,
                                  AuditService auditService) {
        super(serviceTypes, uniqueness, auditService, "Service type", MasterTable.SERVICE_TYPES);
    }

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public ServiceType create(ServiceTypeCommand command) {
        ServiceType serviceType = new ServiceType();
        applyCommonFields(serviceType, command.code(), command.name(), command.description(),
                command.displayOrder());
        applySpecific(serviceType, command);
        return createEntity(serviceType);
    }

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public ServiceType update(UUID id, ServiceTypeCommand command) {
        return updateEntity(id, command.expectedVersion(), serviceType -> {
            applyCommonFields(serviceType, null, command.name(), command.description(),
                    command.displayOrder());
            applySpecific(serviceType, command);
        });
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READ)
    public ServiceType getById(UUID id) {
        return doGetById(id);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READ)
    public Page<ServiceType> search(MasterDataCriteria criteria, Pageable pageable) {
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
    public ServiceType activate(UUID id) {
        return doActivate(id);
    }

    @Override
    @Transactional
    @PreAuthorize(WRITE)
    public ServiceType deactivate(UUID id) {
        return doDeactivate(id);
    }

    // ---------------------------------------------------------------------------- rules

    @Override
    protected void validateBeforeSave(ServiceType serviceType, UUID companyId, UUID excludeId) {
        requireAvailable(companyId, excludeId, Map.of("name", serviceType.getName()),
                "name", serviceType.getName());
    }

    @Override
    protected Map<String, Object> snapshot(ServiceType serviceType) {
        Map<String, Object> values = super.snapshot(serviceType);
        values.put("deliveryDays", serviceType.getDeliveryDays());
        values.put("express", serviceType.isExpress());
        values.put("cutoffTime", String.valueOf(serviceType.getCutoffTime()));
        values.put("priority", serviceType.getPriority());
        values.put("volumetricDivisor", serviceType.getVolumetricDivisor());
        values.put("network", String.valueOf(serviceType.getNetwork()));
        return values;
    }

    private void applySpecific(ServiceType serviceType, ServiceTypeCommand command) {
        serviceType.setDeliveryDays(command.deliveryDays());
        serviceType.setExpress(Boolean.TRUE.equals(command.express()));
        serviceType.setCutoffTime(command.cutoffTime());
        serviceType.setPriority(command.priority() == null ? 0 : command.priority());
        serviceType.setVolumetricDivisor(command.volumetricDivisor());
        serviceType.setNetwork(command.network() == ServiceNetwork.DOCUMENT ? null : command.network());
    }
}
