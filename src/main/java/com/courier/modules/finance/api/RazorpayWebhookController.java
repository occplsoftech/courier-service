package com.courier.modules.finance.api;

import com.courier.modules.finance.application.WalletService;
import com.courier.modules.finance.infrastructure.RazorpayProperties;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/**
 * The other half of a Razorpay recharge — the case the browser cannot cover.
 *
 * <p>{@code POST /branch-wallet/recharge} settles a payment the browser is still there to
 * confirm. If the tab is closed (or the network drops) between the gateway capturing the
 * payment and that confirmation call landing, the money is real and sitting at Razorpay,
 * but nothing in this platform ever credits it — the wallet is short until someone notices
 * and credits it by hand. This endpoint is Razorpay telling us the same thing the browser
 * would have, on its own schedule instead of the browser's.
 *
 * <p>Public by necessity — Razorpay calls it with no bearer token — so it is listed in
 * {@code SecurityConfig.PUBLIC_ENDPOINTS} and authenticity is proven a different way: the
 * whole request body is HMAC-SHA256 signed with a secret only Razorpay and this deployment
 * know ({@code RAZORPAY_WEBHOOK_SECRET}, set on the Razorpay dashboard's Webhooks screen —
 * not the same secret as {@code RAZORPAY_KEY_SECRET}). No signature, wrong signature, or no
 * secret configured all end the same way: refused, nothing settled.
 *
 * <p>The company and branch a payment belongs to come from the order's own {@code notes} —
 * written by {@code WalletServiceImpl#openRecharge} when the order was created — never from
 * anything else the webhook body claims. {@link WalletService#settleFromWebhook} then
 * reuses the exact same gateway-authoritative-amount and idempotency guarantees as the
 * browser path, so a webhook that fires after (or before, or twice alongside) the browser's
 * own confirmation still credits the wallet exactly once.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/branch-wallet/webhook")
@RequiredArgsConstructor
@Hidden
public class RazorpayWebhookController {

    private static final String SIGNATURE_HEADER = "X-Razorpay-Signature";
    private static final String EVENT_PAYMENT_CAPTURED = "payment.captured";
    private static final String EVENT_PAYMENT_FAILED = "payment.failed";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final WalletService walletService;
    private final RazorpayProperties properties;
    private final ObjectMapper objectMapper;

    @PostMapping(value = "/razorpay", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> handle(@RequestBody byte[] rawBody,
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature) {

        if (isBlank(properties.getWebhookSecret())) {
            log.warn("Razorpay webhook called but RAZORPAY_WEBHOOK_SECRET is not set — refusing");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (isBlank(signature)) {
            return ResponseEntity.badRequest().build();
        }
        if (!signatureMatches(rawBody, signature)) {
            log.warn("Razorpay webhook signature did not match — refusing payload");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (IOException e) {
            log.warn("Razorpay webhook body was not valid JSON despite a valid signature");
            return ResponseEntity.badRequest().build();
        }

        // Every event this deployment does not act on is still acknowledged with 200 —
        // Razorpay retries on anything else, and there is nothing to retry towards.
        String event = root.path("event").asText("");
        if (EVENT_PAYMENT_FAILED.equals(event)) {
            return handleFailed(root);
        }
        if (!EVENT_PAYMENT_CAPTURED.equals(event)) {
            return ResponseEntity.ok().build();
        }

        JsonNode entity = root.path("payload").path("payment").path("entity");
        String paymentId = textOrNull(entity, "id");
        String orderId = textOrNull(entity, "order_id");
        JsonNode notes = entity.path("notes");
        UUID companyId = uuidOrNull(notes, "companyId");
        UUID branchId = uuidOrNull(notes, "branchId");
        // A customer's recharge carries customerId instead of branchId (see WalletServiceImpl.startRecharge).
        UUID customerId = uuidOrNull(notes, "customerId");

        if (paymentId == null || orderId == null || companyId == null || (branchId == null && customerId == null)) {
            // Most likely an order this platform never opened (someone else's Razorpay
            // account sharing the same webhook URL by mistake) — nothing to settle.
            log.warn("Razorpay payment.captured missing paymentId/orderId/companyId/branchId-or-customerId; ignoring");
            return ResponseEntity.ok().build();
        }

        try {
            CompanyContext.runAs(companyId, () -> {
                if (branchId != null) {
                    walletService.settleFromWebhook(companyId, branchId, orderId, paymentId);
                } else {
                    walletService.settleFromWebhookForCustomer(companyId, customerId, orderId, paymentId);
                }
            });
        } catch (BusinessRuleException e) {
            // A deterministic refusal (already recorded, wallet inactive, currency
            // mismatch...) — retrying will not change the outcome, so acknowledge it.
            log.warn("Razorpay webhook settlement refused for payment {}: {}", paymentId, e.getMessage());
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Razorpay webhook settlement failed for payment {} (order {})",
                    paymentId, orderId, e);
            // 5xx so Razorpay retries — this branch is for transient failure (DB, etc.),
            // not a business refusal, and a captured payment must not be dropped silently.
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        return ResponseEntity.ok().build();
    }

    /** Marks the recharge attempt FAILED (never downgrades SUCCESS; a later capture still settles it). */
    private ResponseEntity<Void> handleFailed(JsonNode root) {
        JsonNode entity = root.path("payload").path("payment").path("entity");
        String orderId = textOrNull(entity, "order_id");
        UUID companyId = uuidOrNull(entity.path("notes"), "companyId");
        if (orderId == null || companyId == null) {
            return ResponseEntity.ok().build();
        }
        String reason = textOrNull(entity, "error_description");
        try {
            CompanyContext.runAs(companyId, () ->
                    walletService.markRechargeFailed(companyId, orderId, reason == null ? "The payment failed." : reason));
        } catch (Exception e) {
            log.error("Could not record failed Razorpay payment for order {}", orderId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        return ResponseEntity.ok().build();
    }

    /**
     * Constant-time comparison ({@link MessageDigest#isEqual}), mirroring
     * {@code RazorpayPaymentGateway}'s own checkout-signature check — a byte-by-byte early
     * exit here would leak the expected signature to a caller who can time the request.
     */
    private boolean signatureMatches(byte[] rawBody, String signature) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(
                    properties.getWebhookSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            String expected = HexFormat.of().formatHex(mac.doFinal(rawBody));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Could not compute the webhook signature", e);
            return false;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static UUID uuidOrNull(JsonNode node, String field) {
        String raw = textOrNull(node, field);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
