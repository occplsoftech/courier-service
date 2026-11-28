package com.courier.modules.charge.domain;

/**
 * The charges a booking clerk can add from the Charges Master (V192). Freight, ODA, fuel surcharge and FOV are not here:
 * the rate card and the pincode decide those.
 */
public enum BookingChargeCode {
    HANDLING("Handling Charges"),
    PICKUP("Pick up Charges"),
    DELIVERY("Delivery Charges"),
    AWB("AWB Charges"),
    GREEN_TAX("Green Tax"),
    REVERSE_PICKUP("Reverse Pick Up Charges"),
    /** Cash on delivery: % of the amount collected or the minimum, whichever is higher. */
    COD("COD"),
    OTHER("Other Charges");

    private final String label;

    BookingChargeCode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
