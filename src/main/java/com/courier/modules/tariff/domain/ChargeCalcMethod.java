package com.courier.modules.tariff.domain;

/** How a charge rule's value becomes an amount. Min and max on the rule are applied afterwards. */
public enum ChargeCalcMethod {
    /** The value, once per shipment (docket charge). */
    FLAT,
    /** Value % of the freight (fuel surcharge). */
    PERCENT_OF_FREIGHT,
    /** Value per chargeable kg, rounded up to whole kg (hamali). */
    PER_KG,
    /** Value per piece. */
    PER_PIECE,
    /** Value % of the declared value (FOV / insurance, COD fee). */
    PERCENT_OF_VALUE
}
