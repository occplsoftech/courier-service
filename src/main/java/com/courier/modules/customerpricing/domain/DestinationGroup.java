package com.courier.modules.customerpricing.domain;

/**
 * Where a shipment goes, for a rate structure (V177). Cargo uses WITHIN_STATE, REST_OF_INDIA and NORTH_EAST; air
 * courier uses WEST, METROS, REST_OF_INDIA and EAST_NORTH_EAST.
 */
public enum DestinationGroup {
    WITHIN_STATE("Within State"), REST_OF_INDIA("Rest of India"), NORTH_EAST("North East"),
    WEST("West"), METROS("Metros"), EAST_NORTH_EAST("East-North East");

    private final String label;

    DestinationGroup(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
