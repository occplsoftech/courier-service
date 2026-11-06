package com.courier.modules.customerportal.rates.domain;

import java.time.Instant;

/**
 * Proof of delivery of a delivered booking: who received it and when; the photo and signature as short-lived links once the POD is
 * approved ({@code photoPending} while it is still being checked).
 */
public record ProofOfDelivery(String shipmentNumber, String trackingNumber, String deliveredTo, Instant deliveredAt, String photoUrl,
                              String signatureUrl, boolean photoPending) {
}
