package com.courier.modules.shipment.application.command;

import java.math.BigDecimal;

/** What a courier partner answered when a hub handed it a shipment: its own AWB, order reference, label and what it charges us. */
public record ForwardRecord(String providerCode, String serviceName, String awb, String orderRef, String labelUrl, BigDecimal cost) {
}
