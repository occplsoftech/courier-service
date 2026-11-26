package com.courier.modules.shipment.domain;

/** How a delivery was proved (V178), as Delivery Updation offers it. */
public enum DeliveryProof {
    DROP("Drop"),
    SIGNATURE("Signature"),
    STAMP_AND_SIGN("Stamp and Sign");

    private final String label;

    DeliveryProof(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
