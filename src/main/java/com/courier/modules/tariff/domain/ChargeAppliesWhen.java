package com.courier.modules.tariff.domain;

/** When a charge rule is added to a quote. */
public enum ChargeAppliesWhen {
    ALWAYS,
    /** Only when the shipment is cash on delivery. */
    COD_ONLY,
    /** Only when the delivery pincode is out of the delivery area (ODA in the pincode master). */
    ODA_ONLY
}
