package com.courier.modules.master.domain;

/**
 * The delivery networks a pincode's coverage is recorded for: documents, surface parcels and air parcels (V171), air
 * cargo, surface cargo and premium (V185). A booking is checked against its service type's network; a document goes by
 * {@link #DOCUMENT} unless the service is {@link #PREMIUM}, which has its own coverage for documents and parcels alike.
 */
public enum ServiceNetwork {
    DOCUMENT("Document"),
    SURFACE("Surface"),
    AIR("Air"),
    AIR_CARGO("Air Cargo"),
    SURFACE_CARGO("Surface Cargo"),
    PREMIUM("Premium");

    private final String label;

    ServiceNetwork(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * The network a booking runs on: the service type's (SURFACE when it has none); a document goes by the document
     * network except on a premium service.
     */
    public static ServiceNetwork of(boolean document, ServiceNetwork serviceTypeNetwork) {
        if (serviceTypeNetwork == PREMIUM) {
            return PREMIUM;
        }
        if (document) {
            return DOCUMENT;
        }
        return serviceTypeNetwork == null || serviceTypeNetwork == DOCUMENT ? SURFACE : serviceTypeNetwork;
    }
}
