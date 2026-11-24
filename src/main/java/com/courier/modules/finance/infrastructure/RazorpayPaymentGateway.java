package com.courier.modules.finance.infrastructure;

import com.courier.modules.finance.application.payment.PaymentGatewayPort;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Razorpay adapter. Two calls: open an order, and verify the confirmation.
 *
 * <p>Written against Razorpay's REST API directly rather than pulling in the SDK. The
 * surface used here is two endpoints and one HMAC, the SDK would add a transitive
 * dependency tree to the whole application for that, and the signature check is the part
 * that must be obviously correct rather than delegated.
 *
 * <p>Three details that are load-bearing:
 * <ul>
 *   <li><b>Paise, not rupees.</b> Razorpay's {@code amount} is the smallest currency unit.
 *       The conversion is done once, here, on a {@code BigDecimal} — a float would round a
 *       recharge wrong at the third decimal and nobody would notice until reconciliation.</li>
 *   <li><b>The signature is over {@code orderId|paymentId}</b>, HMAC-SHA256 with the key
 *       secret, hex-encoded. Both ids must come from the confirmation and the order id must
 *       be the one <em>we</em> opened; verifying a client-supplied pair against itself
 *       proves nothing.</li>
 *   <li><b>Constant-time comparison.</b> {@link MessageDigest#isEqual} rather than
 *       {@code String.equals}, so a byte-by-byte early exit cannot leak the expected
 *       signature to a caller who can time the request.</li>
 * </ul>
 */
@Slf4j
public class RazorpayPaymentGateway implements PaymentGatewayPort {

    public static final String GATEWAY = "RAZORPAY";

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final BigDecimal MINOR_UNITS = BigDecimal.valueOf(100);

    private final RazorpayProperties properties;
    private final RestClient restClient;

    public RazorpayPaymentGateway(RazorpayProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.restClient = builder
                .baseUrl(properties.getApiBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, basicAuth(properties))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public String name() {
        return GATEWAY;
    }

    @Override
    public GatewayOrder createOrder(BigDecimal amount, String currency, String receipt,
                                    Map<String, String> notes) {
        long amountMinor = toMinorUnits(amount);

        Map<String, Object> body = new HashMap<>();
        body.put("amount", amountMinor);
        body.put("currency", currency);
        body.put("receipt", receipt);
        // Razorpay rejects a second order with the same receipt only if asked to; we want
        // a fresh order per attempt, because an abandoned checkout must not block a retry.
        body.put("payment_capture", 1);
        if (notes != null && !notes.isEmpty()) {
            body.put("notes", notes);
        }

        Map<?, ?> response;
        try {
            response = restClient.post()
                    .uri("/orders")
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            throw translateGatewayException(e, "order creation", receipt);
        }

        if (response == null || response.get("id") == null) {
            log.error("Razorpay returned no order id for receipt {}", receipt);
            throw new BusinessRuleException(ErrorCode.SERVICE_UNAVAILABLE,
                    "The payment gateway returned an unusable response. No money has moved.");
        }

        return new GatewayOrder(GATEWAY, String.valueOf(response.get("id")), amountMinor,
                currency, properties.getKeyId(), receipt);
    }

    @Override
    public void verifyPayment(PaymentConfirmation confirmation) {
        if (confirmation == null
                || isBlank(confirmation.orderId())
                || isBlank(confirmation.paymentId())
                || isBlank(confirmation.signature())) {
            throw new BusinessRuleException(
                    "The payment confirmation is incomplete. Order id, payment id and "
                            + "signature are all required.");
        }

        String expected = hmacHex(confirmation.orderId() + "|" + confirmation.paymentId());

        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                confirmation.signature().getBytes(StandardCharsets.UTF_8))) {
            // Log the ids, never the signatures — a rejected attempt is worth investigating.
            log.warn("Razorpay signature mismatch for order {} payment {}",
                    confirmation.orderId(), confirmation.paymentId());
            throw new BusinessRuleException(
                    "The payment could not be verified and has not been credited. "
                            + "If money was debited it will be refunded by the gateway.");
        }
    }

    @Override
    public GatewayPayment fetchPayment(String paymentId) {
        if (isBlank(paymentId)) {
            throw new BusinessRuleException("A payment id is required.");
        }

        Map<?, ?> response;
        try {
            response = restClient.get()
                    .uri("/payments/{id}", paymentId)
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            throw translateGatewayException(e, "payment lookup", paymentId);
        }

        if (response == null || response.get("id") == null || response.get("amount") == null) {
            log.error("Razorpay returned an unusable payment record for {}", paymentId);
            throw new BusinessRuleException(ErrorCode.SERVICE_UNAVAILABLE,
                    "The payment gateway returned an unusable response. Nothing has been credited.");
        }

        long amountMinor = Long.parseLong(String.valueOf(response.get("amount")));
        String status = String.valueOf(response.get("status"));

        return new GatewayPayment(GATEWAY,
                String.valueOf(response.get("id")),
                response.get("order_id") == null ? null : String.valueOf(response.get("order_id")),
                toMajorUnits(amountMinor),
                response.get("currency") == null ? null : String.valueOf(response.get("currency")),
                "captured".equalsIgnoreCase(status));
    }

    @Override
    public java.util.List<OrderPayment> fetchOrderPayments(String orderId) {
        if (isBlank(orderId)) {
            throw new BusinessRuleException("An order id is required.");
        }
        Map<?, ?> response;
        try {
            response = restClient.get().uri("/orders/{id}/payments", orderId).retrieve().body(Map.class);
        } catch (Exception e) {
            throw translateGatewayException(e, "order payments lookup", orderId);
        }
        java.util.List<OrderPayment> payments = new java.util.ArrayList<>();
        Object items = response == null ? null : response.get("items");
        if (items instanceof java.util.List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> p && p.get("id") != null) {
                    String status = String.valueOf(p.get("status"));
                    Object error = p.get("error_description");
                    payments.add(new OrderPayment(String.valueOf(p.get("id")), status,
                            "captured".equalsIgnoreCase(status), error == null ? null : String.valueOf(error)));
                }
            }
        }
        return payments;
    }

    /**
     * {@code POST /payments/qr_codes}: a single-use UPI QR for exactly this amount, closing at
     * {@code closeBy}. Razorpay needs the QR Codes feature on the account.
     */
    @Override
    public GatewayQr createQr(BigDecimal amount, String name, String description, java.time.Instant closeBy,
                              Map<String, String> notes) {
        long amountMinor = toMinorUnits(amount);
        Map<String, Object> body = new HashMap<>();
        body.put("type", "upi_qr");
        body.put("name", truncate(name, 40));
        body.put("usage", "single_use");
        body.put("fixed_amount", true);
        body.put("payment_amount", amountMinor);
        body.put("description", truncate(description, 250));
        body.put("close_by", closeBy.getEpochSecond());
        if (notes != null && !notes.isEmpty()) {
            body.put("notes", notes);
        }
        Map<?, ?> response;
        try {
            response = restClient.post().uri("/payments/qr_codes").body(body).retrieve().body(Map.class);
        } catch (Exception e) {
            throw translateGatewayException(e, "QR code creation", name);
        }
        if (response == null || response.get("id") == null || response.get("image_url") == null) {
            log.error("Razorpay returned no QR code for {}", name);
            throw new BusinessRuleException(ErrorCode.SERVICE_UNAVAILABLE,
                    "The payment gateway returned an unusable response. Try again.");
        }
        Object close = response.get("close_by");
        java.time.Instant closes = close == null ? closeBy : java.time.Instant.ofEpochSecond(Long.parseLong(String.valueOf(close)));
        return new GatewayQr(GATEWAY, String.valueOf(response.get("id")), String.valueOf(response.get("image_url")),
                amountMinor, closes);
    }

    /** {@code GET /payments/qr_codes/{id}/payments}: the captured payments made to the QR. */
    @Override
    public java.util.List<GatewayPayment> fetchQrPayments(String qrId) {
        if (isBlank(qrId)) {
            return java.util.List.of();
        }
        Map<?, ?> response;
        try {
            response = restClient.get().uri("/payments/qr_codes/{id}/payments", qrId).retrieve().body(Map.class);
        } catch (Exception e) {
            throw translateGatewayException(e, "QR payments lookup", qrId);
        }
        java.util.List<GatewayPayment> payments = new java.util.ArrayList<>();
        Object items = response == null ? null : response.get("items");
        if (items instanceof java.util.List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> p && p.get("id") != null && p.get("amount") != null) {
                    payments.add(new GatewayPayment(GATEWAY, String.valueOf(p.get("id")),
                            p.get("order_id") == null ? null : String.valueOf(p.get("order_id")),
                            toMajorUnits(Long.parseLong(String.valueOf(p.get("amount")))),
                            p.get("currency") == null ? null : String.valueOf(p.get("currency")),
                            "captured".equalsIgnoreCase(String.valueOf(p.get("status")))));
                }
            }
        }
        return payments;
    }

    @Override
    public void closeQr(String qrId) {
        if (isBlank(qrId)) {
            return;
        }
        try {
            restClient.post().uri("/payments/qr_codes/{id}/close", qrId).retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.info("Razorpay QR {} not closed: {}", qrId, e.getMessage());
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static final java.time.ZoneId IST = java.time.ZoneId.of("Asia/Kolkata");
    private static final int RECON_PAGE = 100;
    /** Hard stop per day so a misbehaving response can never loop forever. */
    private static final int RECON_MAX_PAGES = 50;

    /**
     * Razorpay's settlement reconciliation report, one call per settlement day:
     * {@code GET /settlements/recon/combined?year&month&day&count&skip}. Each {@code payment}
     * row names the payment and the settlement (and its UTR) that paid it out.
     */
    @Override
    public java.util.List<PaymentSettlement> fetchSettlements(java.time.LocalDate from, java.time.LocalDate to) {
        java.util.List<PaymentSettlement> out = new java.util.ArrayList<>();
        for (java.time.LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            for (int page = 0; page < RECON_MAX_PAGES; page++) {
                final java.time.LocalDate d = day;
                final int skip = page * RECON_PAGE;
                Map<?, ?> response;
                try {
                    response = restClient.get()
                            .uri(u -> u.path("/settlements/recon/combined")
                                    .queryParam("year", d.getYear())
                                    .queryParam("month", d.getMonthValue())
                                    .queryParam("day", d.getDayOfMonth())
                                    .queryParam("count", RECON_PAGE)
                                    .queryParam("skip", skip)
                                    .build())
                            .retrieve().body(Map.class);
                } catch (Exception e) {
                    throw translateGatewayException(e, "settlement lookup", d.toString());
                }
                Object items = response == null ? null : response.get("items");
                if (!(items instanceof java.util.List<?> list) || list.isEmpty()) {
                    break;
                }
                for (Object item : list) {
                    if (item instanceof Map<?, ?> row && "payment".equals(row.get("type"))
                            && row.get("entity_id") != null && row.get("settlement_id") != null) {
                        Object at = row.get("settled_at");
                        out.add(new PaymentSettlement(String.valueOf(row.get("entity_id")),
                                String.valueOf(row.get("settlement_id")),
                                row.get("settlement_utr") == null ? null : String.valueOf(row.get("settlement_utr")),
                                at instanceof Number n ? java.time.Instant.ofEpochSecond(n.longValue())
                                        : d.atStartOfDay(IST).toInstant()));
                    }
                }
                if (list.size() < RECON_PAGE) {
                    break;
                }
            }
        }
        return out;
    }

    /**
     * A 401/403 from Razorpay means our own key id/secret is wrong or disabled — that is
     * an operator misconfiguration, not a transient outage, and telling the caller to
     * retry would just repeat the same failure. Every other failure (DNS, timeout,
     * connection refused, 5xx) is genuinely transient and worth a retry.
     *
     * <p>Never surfaces the gateway's own error text either way: it can echo request
     * content.
     */
    private BusinessRuleException translateGatewayException(Exception e, String operation, String context) {
        if (e instanceof HttpClientErrorException httpError
                && (httpError.getStatusCode().value() == 401 || httpError.getStatusCode().value() == 403)) {
            log.error("Razorpay rejected our credentials during {} for {} — check RAZORPAY_KEY_ID/RAZORPAY_KEY_SECRET",
                    operation, context, e);
            return new BusinessRuleException(ErrorCode.INTERNAL_ERROR,
                    "The payment gateway is not configured correctly. No money has moved; "
                            + "please contact support instead of retrying.");
        }

        log.error("Razorpay {} failed for {}", operation, context, e);
        return new BusinessRuleException(ErrorCode.SERVICE_UNAVAILABLE,
                "The payment gateway could not be reached. No money has moved; please retry.");
    }

    // -------------------------------------------------------------------- helpers

    /**
     * Rupees to paise. {@code HALF_UP} at two decimals: the wallet stores four, and a
     * recharge of 10.005 must resolve to a whole number of paise deterministically rather
     * than being truncated in one direction.
     */
    static long toMinorUnits(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessRuleException("Recharge amount must be greater than zero.");
        }
        return amount.setScale(2, RoundingMode.HALF_UP).multiply(MINOR_UNITS).longValueExact();
    }

    /** Paise back to rupees, at the wallet's scale. Exact — no floating point anywhere. */
    static BigDecimal toMajorUnits(long amountMinor) {
        return BigDecimal.valueOf(amountMinor)
                .divide(MINOR_UNITS, com.courier.modules.finance.domain.Wallet.MONEY_SCALE,
                        RoundingMode.HALF_UP);
    }

    private String hmacHex(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(
                    properties.getKeySecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            // A broken MAC must never be read as "verified".
            throw new IllegalStateException("Could not compute the payment signature", e);
        }
    }

    private static String basicAuth(RazorpayProperties properties) {
        String raw = properties.getKeyId() + ":" + properties.getKeySecret();
        return "Basic " + Base64.getEncoder()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
