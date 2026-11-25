package com.courier.modules.master.domain;

/**
 * The three delivery networks a pincode's coverage is recorded for (V171): documents, surface
 * parcels and air parcels. A document booking is checked against {@link #DOCUMENT}; a parcel
 * against {@link #SURFACE} or {@link #AIR} by its service type's network.
 */
public enum ServiceNetwork {
    DOCUMENT("Document"),
    SURFACE("Surface"),
    AIR("Air");

    private final String label;

    ServiceNetwork(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** The network a booking runs on: documents always go by the document network. */
    public static ServiceNetwork of(boolean document, ServiceNetwork serviceTypeNetwork) {
        if (document) {
            return DOCUMENT;
        }
        return serviceTypeNetwork == AIR ? AIR : SURFACE;
    }
}
