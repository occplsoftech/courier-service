package com.courier.modules.finance.api;

import com.courier.modules.finance.application.WalletService;
import com.courier.modules.finance.infrastructure.RazorpayProperties;
import com.courier.shared.company.CompanyContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The Razorpay webhook routes a captured payment to the branch or the customer named in the order's notes. */
class RazorpayWebhookControllerTest {

    private static final String SECRET = "whsec_test";
    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID BRANCH = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();

    private final WalletService walletService = mock(WalletService.class);
    private RazorpayWebhookController controller;

    @BeforeEach
    void setUp() {
        RazorpayProperties props = new RazorpayProperties();
        props.setWebhookSecret(SECRET);
        controller = new RazorpayWebhookController(walletService, props, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        CompanyContext.clear();
    }

    private static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private ResponseEntity<Void> post(String notes) throws Exception {
        String body = """
                {"event":"payment.captured","payload":{"payment":{"entity":
                {"id":"pay_1","order_id":"order_1","notes":%s}}}}""".formatted(notes);
        return controller.handle(body.getBytes(StandardCharsets.UTF_8), sign(body));
    }

    @Test
    @DisplayName("a captured payment for a branch settles that branch's wallet")
    void branchPayment() throws Exception {
        ResponseEntity<Void> response = post("{\"companyId\":\"%s\",\"branchId\":\"%s\"}".formatted(COMPANY, BRANCH));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(walletService).settleFromWebhook(COMPANY, BRANCH, "order_1", "pay_1");
        verify(walletService, never()).settleFromWebhookForCustomer(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a captured payment for a customer settles that customer's wallet")
    void customerPayment() throws Exception {
        ResponseEntity<Void> response = post("{\"companyId\":\"%s\",\"customerId\":\"%s\"}".formatted(COMPANY, CUSTOMER));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(walletService).settleFromWebhookForCustomer(COMPANY, CUSTOMER, "order_1", "pay_1");
        verify(walletService, never()).settleFromWebhook(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a payment that names neither a branch nor a customer is acknowledged and ignored")
    void unknownOwner() throws Exception {
        ResponseEntity<Void> response = post("{\"companyId\":\"%s\"}".formatted(COMPANY));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(walletService, never()).settleFromWebhook(any(), any(), any(), any());
        verify(walletService, never()).settleFromWebhookForCustomer(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a wrong signature is refused before anything is settled")
    void badSignature() throws Exception {
        String body = "{\"event\":\"payment.captured\"}";

        ResponseEntity<Void> response = controller.handle(body.getBytes(StandardCharsets.UTF_8), "deadbeef");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(walletService, never()).settleFromWebhookForCustomer(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a business refusal is acknowledged so the gateway does not retry; a system error is retried")
    void refusalVersusError() throws Exception {
        when(walletService.settleFromWebhookForCustomer(eq(COMPANY), eq(CUSTOMER), any(), any()))
                .thenThrow(new com.courier.shared.exception.BusinessRuleException("already recorded"));
        assertThat(post("{\"companyId\":\"%s\",\"customerId\":\"%s\"}".formatted(COMPANY, CUSTOMER)).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        when(walletService.settleFromWebhookForCustomer(eq(COMPANY), eq(CUSTOMER), any(), any()))
                .thenThrow(new IllegalStateException("db down"));
        assertThat(post("{\"companyId\":\"%s\",\"customerId\":\"%s\"}".formatted(COMPANY, CUSTOMER)).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
