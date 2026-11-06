package com.courier.modules.shipment.domain;

/** What the customer wants after a failed delivery attempt. A changed address is written onto the shipment straight away. */
public enum DeliveryChoice {
    REATTEMPT("Deliver again"),
    CHANGE_ADDRESS("Deliver to a new address"),
    RETURN("Return to sender");

    private final String label;

    DeliveryChoice(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
