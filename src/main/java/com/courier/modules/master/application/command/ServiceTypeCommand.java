package com.courier.modules.master.application.command;

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
        Long expectedVersion
) {
}
