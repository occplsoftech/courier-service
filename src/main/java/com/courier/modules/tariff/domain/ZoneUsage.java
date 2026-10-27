package com.courier.modules.tariff.domain;

/**
 * Which end of a shipment a zone describes. A FROM zone groups the places shipments start from (one rate card per
 * FROM zone), a TO zone the places they are delivered to. Pune is usually BOTH: shipments start there and are
 * delivered there.
 */
public enum ZoneUsage {
    FROM,
    TO,
    BOTH;

    public boolean coversFrom() {
        return this == FROM || this == BOTH;
    }

    public boolean coversTo() {
        return this == TO || this == BOTH;
    }

    /** Two zones compete for the same place only when they can both be asked the same question (from, or to). */
    public boolean overlaps(ZoneUsage other) {
        return (coversFrom() && other.coversFrom()) || (coversTo() && other.coversTo());
    }
}
