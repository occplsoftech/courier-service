package com.courier.modules.master.api;

import com.courier.modules.master.api.dto.CreateRegionalZoneRequest;
import com.courier.modules.master.api.dto.RegionalZoneResponse;
import com.courier.modules.master.api.dto.UpdateRegionalZoneRequest;
import com.courier.modules.master.application.command.RegionalZoneCommand;
import com.courier.modules.master.domain.RegionalZone;
import com.courier.shared.api.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/** Wire contract to application/domain types for regional zones. */
@Component
public class RegionalZoneMasterMapper {

    public RegionalZoneCommand toCommand(CreateRegionalZoneRequest r) {
        return new RegionalZoneCommand(r.code(), r.name(), r.description(), r.displayOrder(), null);
    }

    public RegionalZoneCommand toCommand(UpdateRegionalZoneRequest r) {
        return new RegionalZoneCommand(null, r.name(), r.description(), r.displayOrder(), r.version());
    }

    public RegionalZoneResponse toResponse(RegionalZone z) {
        return new RegionalZoneResponse(z.getId(), z.getCompanyId(), z.getCode(), z.getName(),
                z.getDescription(), z.getStatus(), z.getDisplayOrder(),
                z.getCreatedBy(), z.getCreatedAt(), z.getUpdatedBy(), z.getUpdatedAt(), z.getVersion());
    }

    public PageResponse<RegionalZoneResponse> toPage(Page<RegionalZone> page) {
        return PageResponse.from(page, this::toResponse);
    }
}
