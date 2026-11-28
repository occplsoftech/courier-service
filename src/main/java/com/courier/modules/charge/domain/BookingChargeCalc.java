package com.courier.modules.charge.domain;

/** How a Charges Master value becomes an amount; the minimum is applied afterwards. */
public enum BookingChargeCalc {
    /** The value, once per shipment. */
    FIXED,
    /** Value per chargeable kg, rounded up to whole kg. */
    PER_KG,
    /** Value per piece. */
    PER_PIECE,
    /** Value % of the freight. */
    PERCENT_OF_FREIGHT,
    /** Value % of the COD amount (COD only). */
    PERCENT_OF_COD
}
