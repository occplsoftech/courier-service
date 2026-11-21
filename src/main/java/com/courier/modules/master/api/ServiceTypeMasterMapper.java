package com.courier.modules.master.api;

import com.courier.modules.master.api.dto.CreateServiceTypeRequest;
import com.courier.modules.master.api.dto.ServiceTypeResponse;
import com.courier.modules.master.api.dto.UpdateServiceTypeRequest;
import com.courier.modules.master.application.command.ServiceTypeCommand;
import com.courier.modules.master.domain.ServiceType;
import com.courier.shared.api.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/** Wire contract to application/domain types for service types. */
@Component
public class ServiceTypeMasterMapper {

    public ServiceTypeCommand toCommand(CreateServiceTypeRequest r) {
        return new ServiceTypeCommand(r.code(), r.name(), r.description(), r.displayOrder(),
                r.deliveryDays(), r.express(), r.cutoffTime(), r.priority(), r.volumetricDivisor(), null);
    }

    public ServiceTypeCommand toCommand(UpdateServiceTypeRequest r) {
        return new ServiceTypeCommand(null, r.name(), r.description(), r.displayOrder(),
                r.deliveryDays(), r.express(), r.cutoffTime(), r.priority(), r.volumetricDivisor(), r.version());
    }

    public ServiceTypeResponse toResponse(ServiceType s) {
        return new ServiceTypeResponse(s.getId(), s.getCompanyId(), s.getCode(), s.getName(),
                s.getDescription(), s.getStatus(), s.getDisplayOrder(),
                s.getDeliveryDays(), s.isExpress(), s.getCutoffTime(), s.getPriority(), s.getVolumetricDivisor(),
                s.getCreatedBy(), s.getCreatedAt(), s.getUpdatedBy(), s.getUpdatedAt(), s.getVersion());
    }

    public PageResponse<ServiceTypeResponse> toPage(Page<ServiceType> page) {
        return PageResponse.from(page, this::toResponse);
    }
}
