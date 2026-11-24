package com.courier.modules.shipment.application.command;

import com.courier.modules.shipment.domain.ShipmentPayment;


/**
 * How the customer paid a Paid booking at the counter.
 *
 * @param method    RAZORPAY, CASH or UPI_QR; null means CASH
 * @param reference UPI reference typed by staff (UPI_QR), optional
 */
public record CounterPayment(ShipmentPayment.Method method, String reference) {

    public ShipmentPayment.Method methodOrCash() {
        return method == null ? ShipmentPayment.Method.CASH : method;
    }
}
