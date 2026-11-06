package com.courier.modules.shipment.domain;

/** Why a delivery attempt failed, as the delivery boy or branch records it. {@link #OTHER} needs a note. */
public enum UndeliveredReason {
    RECEIVER_NOT_AVAILABLE("Receiver not available"),
    ADDRESS_INCORRECT("Address wrong or incomplete"),
    REFUSED("Receiver refused"),
    PREMISES_CLOSED("Premises closed"),
    RESCHEDULE_REQUESTED("Receiver asked to deliver later"),
    COD_NOT_READY("COD amount not ready"),
    OTHER("Other");

    private final String label;

    UndeliveredReason(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
