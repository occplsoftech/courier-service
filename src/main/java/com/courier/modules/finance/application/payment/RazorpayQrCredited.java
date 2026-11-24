package com.courier.modules.finance.application.payment;

/**
 * Razorpay's {@code qr_code.credited} webhook for a QR code: whoever opened it re-checks its payments with Razorpay
 * (the webhook's own figures are not trusted).
 */
public record RazorpayQrCredited(String qrId) {
}
