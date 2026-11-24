package com.courier.modules.finance.application.payment;

import java.time.Instant;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The seam between wallet recharge and whichever payment gateway a deployment uses.
 *
 * <p>Two operations, and the split matters. {@link #createOrder} is what makes the flow
 * securable at all: the amount is fixed <em>server side</em> before the browser ever sees
 * it, so a client cannot open checkout for ₹1 and claim a ₹10,000 recharge.
 * {@link #verifyPayment} then proves the confirmation the browser hands back really came
 * from the gateway, by re-deriving its signature from the shared secret.
 *
 * <p>Nothing in the wallet module knows the gateway's name, wire format or SDK. Swapping
 * Razorpay for another provider is a new implementation of this interface and a config
 * change.
 */
public interface PaymentGatewayPort {

    /** Gateway identifier stored on the ledger entry, e.g. {@code RAZORPAY}. */
    String name();

    /**
     * Opens an order at the gateway for a fixed amount.
     *
     * @param amount   in major units (rupees), as the wallet holds it
     * @param currency ISO-4217, e.g. {@code INR}
     * @param receipt  our own reference — the pending transaction number
     * @param notes    free-form metadata echoed back by the gateway; never secrets
     */
    GatewayOrder createOrder(BigDecimal amount, String currency, String receipt,
                             Map<String, String> notes);

    /**
     * Proves a payment confirmation is genuine.
     *
     * @throws com.courier.shared.exception.BusinessRuleException if the signature does not
     *         match — the caller must treat that as "no money arrived" and credit nothing
     */
    void verifyPayment(PaymentConfirmation confirmation);

    /**
     * Asks the gateway what actually happened, by payment id.
     *
     * <p>This is the second half of making recharge safe, and it is not optional. A valid
     * signature proves the confirmation came from the gateway; it does not prove the amount
     * the <em>client</em> claims alongside it. The wallet is credited with the figure the
     * gateway reports here, so a browser cannot pay ₹1 and be credited ₹10,000.
     */
    GatewayPayment fetchPayment(String paymentId);

    /**
     * Every payment attempt the gateway holds for an order — what the reconciliation job asks
     * when no confirmation ever reached us (browser closed, webhook missed).
     */
    java.util.List<OrderPayment> fetchOrderPayments(String orderId);

    /**
     * Payments the gateway paid out to the merchant's bank between two dates (inclusive, IST).
     * Default empty: a gateway with no payout reporting simply never marks anything settled.
     */
    default java.util.List<PaymentSettlement> fetchSettlements(java.time.LocalDate from, java.time.LocalDate to) {
        return java.util.List.of();
    }

    /**
     * A payment that has been paid out.
     *
     * @param settlementId the payout batch
     * @param utr          the bank reference of that payout, when the gateway reports one
     * @param settledAt    when the payout was made
     */
    record PaymentSettlement(String paymentId, String settlementId, String utr, java.time.Instant settledAt) {
    }

    /**
     * One payment attempt on an order.
     *
     * @param status   the gateway's own status word, e.g. {@code captured}, {@code failed}
     * @param captured true once the money is actually with the merchant
     * @param error    the gateway's failure description, when it gave one
     */
    record OrderPayment(String paymentId, String status, boolean captured, String error) {
    }

    /**
     * An order opened at the gateway.
     *
     * @param gateway    which gateway produced it
     * @param orderId    the gateway's order id, handed to the browser checkout
     * @param amountMinor amount in the smallest currency unit (paise) — the gateway's contract
     * @param currency   ISO-4217
     * @param publicKey  the publishable key the browser needs; never the secret
     * @param receipt    our reference, echoed back
     */
    record GatewayOrder(String gateway, String orderId, long amountMinor, String currency,
                        String publicKey, String receipt) {
    }

    /**
     * What the browser hands back after a successful checkout.
     *
     * @param orderId   the gateway order the payment settled
     * @param paymentId the gateway's payment id — also our idempotency key
     * @param signature the gateway's HMAC over the pair
     */
    record PaymentConfirmation(String orderId, String paymentId, String signature) {
    }

    /**
     * The gateway's own account of a payment — the authoritative figures.
     *
     * @param amount   in major units (rupees), converted by the adapter so no caller has to
     *                 remember which side of the boundary speaks paise
     * @param captured true once the money is actually with the merchant; an authorised but
     *                 uncaptured payment must not credit a wallet
     */
    record GatewayPayment(String gateway, String paymentId, String orderId, BigDecimal amount,
                          String currency, boolean captured) {
    }

    /**
     * A single-use UPI QR code for a fixed amount, which the customer scans at the counter
     * (Razorpay "QR Codes"). Default: this gateway cannot make one.
     *
     * @param amount  in major units (rupees)
     * @param closeBy when the QR stops accepting payment
     */
    default GatewayQr createQr(BigDecimal amount, String name, String description, Instant closeBy,
                               Map<String, String> notes) {
        throw new com.courier.shared.exception.BusinessRuleException(
                "Razorpay is not set up for this company. Ask the company admin to add the Razorpay keys in Settings.");
    }

    /** Captured payments made to a QR code, the authoritative figures. Default: none. */
    default java.util.List<GatewayPayment> fetchQrPayments(String qrId) {
        return java.util.List.of();
    }

    /** Closes a QR code so it can no longer be paid; ignore failures (it may be closed already). */
    default void closeQr(String qrId) {
    }

    /**
     * A QR code opened at the gateway.
     *
     * @param imageUrl the gateway-hosted image of the QR, shown on screen
     */
    record GatewayQr(String gateway, String qrId, String imageUrl, long amountMinor, Instant closeBy) {
    }
}
