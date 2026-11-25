package com.courier.modules.customerpricing.domain;

/** Where a shipment starts, for a cargo rate structure (V177): the rows of the printed cargo rate card. */
public enum OriginRegion {
    NORTH("North"), SOUTH("South"), EAST("East"), WEST("West"), NORTH_EAST("North East");

    private final String label;

    OriginRegion(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
