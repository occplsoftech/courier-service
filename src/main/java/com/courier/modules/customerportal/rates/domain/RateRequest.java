package com.courier.modules.customerportal.rates.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What every provider is asked to price. Dimensions are per package, already in centimetres;
 * weight is the total actual weight in kilograms.
 */
public record RateRequest(UUID companyId,
                          UUID customerId,
                          String pickupPincode,
                          String deliveryPincode,
                          BigDecimal actualWeightKg,
                          BigDecimal lengthCm,
                          BigDecimal breadthCm,
                          BigDecimal heightCm,
                          int packageCount,
                          BigDecimal declaredValue,
                          PaymentMode paymentMode,
                          /** COD only: the amount to collect from the receiver; null means the declared value. */
                          BigDecimal codAmount) {

    public enum PaymentMode { PREPAID, COD, TOPAY }

    /** Without a separate COD amount (the declared value is collected). */
    public RateRequest(UUID companyId, UUID customerId, String pickupPincode, String deliveryPincode, BigDecimal actualWeightKg,
                       BigDecimal lengthCm, BigDecimal breadthCm, BigDecimal heightCm, int packageCount, BigDecimal declaredValue,
                       PaymentMode paymentMode) {
        this(companyId, customerId, pickupPincode, deliveryPincode, actualWeightKg, lengthCm, breadthCm, heightCm, packageCount,
                declaredValue, paymentMode, null);
    }

    /** What the courier collects from the receiver: the COD amount (or the declared value) for COD, zero otherwise. */
    public BigDecimal collectAmount() {
        if (!cod()) {
            return BigDecimal.ZERO;
        }
        return codAmount != null && codAmount.signum() > 0 ? codAmount : declaredValue;
    }

    /** TO PAY: the receiver pays the freight on delivery, so nothing is taken from the sender at booking. */
    public boolean topay() {
        return paymentMode == PaymentMode.TOPAY;
    }

    public boolean cod() {
        return paymentMode == PaymentMode.COD;
    }
}
