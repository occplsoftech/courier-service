package com.courier.modules.master.application.command;

/** Input to create or update a regional zone. See {@link CountryCommand} for the code/version rule. */
public record RegionalZoneCommand(
        String code,
        String name,
        String description,
        Integer displayOrder,
        Long expectedVersion
) {
}
