package com.courier.modules.tariff.domain;

/**
 * How much of the map one zone location covers. When a place matches several locations, the most specific wins:
 * PINCODE, then CITY, then DISTRICT, then STATE. That is what lets "Mumbai city: Metro" beat "Maharashtra: Maharashtra".
 */
public enum LocationScope {
    PINCODE(1),
    CITY(2),
    DISTRICT(3),
    STATE(4);

    /** Lower is more specific. */
    private final int rank;

    LocationScope(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }
}
