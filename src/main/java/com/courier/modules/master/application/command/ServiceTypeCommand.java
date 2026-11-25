package com.courier.modules.master.application.command;

import com.courier.modules.master.domain.ServiceNetwork;

import java.time.LocalTime;

/** Input to create or update a service type. See {@link CountryCommand} for the code/version rule. */
public record ServiceTypeCommand(
        String code,
        String name,
        String description,
        Integer displayOrder,
        Integer deliveryDays,
        Boolean express,
        LocalTime cutoffTime,
        Integer priority,
        Integer volumetricDivisor,
        ServiceNetwork network,
        Long expectedVersion
) {
    /** Without a network: the service runs on surface. */
    public ServiceTypeCommand(String code, String name, String description, Integer displayOrder, Integer deliveryDays,
                              Boolean express, LocalTime cutoffTime, Integer priority, Integer volumetricDivisor,
                              Long expectedVersion) {
        this(code, name, description, displayOrder, deliveryDays, express, cutoffTime, priority, volumetricDivisor,
                null, expectedVersion);
    }
}
