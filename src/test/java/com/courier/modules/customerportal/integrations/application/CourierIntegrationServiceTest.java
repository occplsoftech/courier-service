package com.courier.modules.customerportal.integrations.application;

import com.courier.modules.customerportal.integrations.application.CourierIntegrationService.IntegrationView;
import com.courier.modules.customerportal.integrations.domain.CourierIntegration;
import com.courier.modules.customerportal.rates.application.CustomerPortalProperties;
import com.courier.modules.customerportal.rates.application.ProviderCallLimiter;
import com.courier.modules.customerportal.rates.domain.ConnectionTest;
import com.courier.modules.customerportal.rates.domain.CourierProviderAdapter;
import com.courier.modules.customerportal.rates.domain.ProviderResult;
import com.courier.modules.customerportal.rates.domain.RateRequest;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import com.courier.shared.security.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class CourierIntegrationServiceTest {

    private static final UUID COMPANY = UUID.randomUUID();

    private final CourierIntegrationFixtures fx = new CourierIntegrationFixtures();
    private final CustomerPortalProperties env = new CustomerPortalProperties();
    private final AtomicReference<ConnectionTest> answer = new AtomicReference<>(ConnectionTest.ok("fine"));
    private CourierIntegrationService service;

    private CourierProviderAdapter adapter(String code) {
        return new CourierProviderAdapter() {
            @Override public String code() { return code; }
            @Override public String displayName() { return code; }
            @Override public boolean external() { return true; }
            @Override public boolean configured() { return true; }
            @Override public ProviderResult getRates(RateRequest r) { return null; }
            @Override public ConnectionTest testConnection(UUID companyId) { return answer.get(); }
        };
    }

    @BeforeEach
    void setUp() {
        service = new CourierIntegrationService(fx.repository, fx.codec, env,
                List.of(adapter("SHIPROCKET"), adapter("DELHIVERY")), new ProviderCallLimiter(), mock(AuditService.class));
        CompanyContext.setCompanyId(COMPANY);
        AuthenticatedUser admin = new AuthenticatedUser(UUID.randomUUID(), COMPANY, "admin@shop.test", Set.of("COMPANY_ADMIN"), "jti");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(admin, null, admin.authorities()));
    }

    @AfterEach
    void clear() {
        CompanyContext.clear();
        SecurityContextHolder.clearContext();
    }

    private IntegrationView find(List<IntegrationView> all, String code) {
        return all.stream().filter(v -> v.code().equals(code)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("lists every courier; Blue Dart and Ekart are shown but cannot be connected")
    void lists() {
        List<IntegrationView> all = service.list();

        assertThat(all).extracting(IntegrationView::code).containsExactly("SHIPROCKET", "DELHIVERY", "SHIPYAARI", "SKART", "BLUEDART", "EKART");
        assertThat(find(all, "SHIPROCKET").source()).isEqualTo("NONE");
        assertThat(find(all, "SHIPYAARI").available()).isTrue();
        assertThat(find(all, "SHIPYAARI").source()).isEqualTo("NONE");
        assertThat(find(all, "BLUEDART").available()).isFalse();
        assertThatThrownBy(() -> service.save("EKART", true, Map.of())).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("cannot be connected yet");
    }

    @Test
    @DisplayName("saving stores the secret encrypted and never returns it, only that it is set")
    void secretsAreWriteOnly() {
        IntegrationView v = service.save("SHIPROCKET", true,
                Map.of("email", " api@shop.test ", "password", "s3cret", "pickupLocation", "PUNE-1"));

        assertThat(v.connected()).isTrue();
        assertThat(v.source()).isEqualTo("COMPANY");
        assertThat(v.fields()).filteredOn(f -> f.key().equals("password")).singleElement()
                .satisfies(f -> { assertThat(f.value()).isNull(); assertThat(f.set()).isTrue(); });
        assertThat(v.fields()).filteredOn(f -> f.key().equals("email")).singleElement()
                .satisfies(f -> assertThat(f.value()).isEqualTo("api@shop.test"));
        CourierIntegration row = fx.rows.get(0);
        assertThat(row.getSecretsEncrypted()).doesNotContain("s3cret");
        assertThat(row.getSettingsJson()).doesNotContain("s3cret");
        assertThat(fx.codec.decryptSecrets(row.getSecretsEncrypted())).containsEntry("password", "s3cret");
        assertThat(v.toString()).doesNotContain("s3cret");
    }

    @Test
    @DisplayName("a saved courier gets its own secret webhook address, kept across saves; unsaved ones have none")
    void webhookAddress() {
        assertThat(find(service.list(), "SHIPROCKET").webhookUrl()).isNull();

        IntegrationView v = service.save("SHIPROCKET", false, Map.of("email", "api@shop.test"));

        assertThat(v.webhookUrl()).matches("http://localhost:3000/api/v1/webhooks/courier-tracking/[0-9a-f]{48}")
                .doesNotContainIgnoringCase("shiprocket");
        assertThat(service.save("SHIPROCKET", false, Map.of("email", "x@shop.test")).webhookUrl()).isEqualTo(v.webhookUrl());
        assertThat(find(service.list(), "SHIPROCKET").webhookUrl()).isEqualTo(v.webhookUrl());
        assertThat(service.save("DELHIVERY", false, Map.of()).webhookUrl()).isNotEqualTo(v.webhookUrl());
        IntegrationView skart = service.save("SKART", false, Map.of("userName", "franchisee"));
        assertThat(skart.statusPush()).isFalse();
        assertThat(skart.webhookUrl()).isNull();
        assertThat(v.statusPush()).isTrue();
    }

    @Test
    @DisplayName("a blank secret keeps the stored one; a typed one replaces it")
    void blankKeepsSecret() {
        service.save("DELHIVERY", true, Map.of("token", "tok-1", "pickupLocation", "PUNE-1"));

        service.save("DELHIVERY", true, Map.of("token", "  ", "pickupLocation", "PUNE-2"));
        assertThat(fx.codec.decryptSecrets(fx.rows.get(0).getSecretsEncrypted())).containsEntry("token", "tok-1");
        assertThat(fx.codec.readSettings(fx.rows.get(0).getSettingsJson())).containsEntry("pickupLocation", "PUNE-2");

        service.save("DELHIVERY", true, Map.of("token", "tok-2", "pickupLocation", "PUNE-2"));
        assertThat(fx.codec.decryptSecrets(fx.rows.get(0).getSecretsEncrypted())).containsEntry("token", "tok-2");
        assertThat(fx.rows).hasSize(1);
    }

    @Test
    @DisplayName("enabling needs every required field; saving disabled does not")
    void requiredWhenEnabling() {
        assertThatThrownBy(() -> service.save("SHIPROCKET", true, Map.of("email", "api@shop.test")))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("API user password is required");

        IntegrationView draft = service.save("SHIPROCKET", false, Map.of("email", "api@shop.test"));

        assertThat(draft.enabled()).isFalse();
        assertThat(draft.connected()).isFalse();
    }

    @Test
    @DisplayName("unknown fields, over-long values and unknown couriers are refused")
    void validation() {
        assertThatThrownBy(() -> service.save("DELHIVERY", false, Map.of("baseUrl", "https://evil.test")))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Unknown field 'baseUrl'");
        assertThatThrownBy(() -> service.save("DELHIVERY", false, Map.of("pickupLocation", "x".repeat(201))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("too long");
        assertThatThrownBy(() -> service.save("FEDEX", false, Map.of())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("test: needs a saved, enabled account; records the courier's answer; a new secret clears it")
    void testing() {
        assertThatThrownBy(() -> service.test("DELHIVERY")).hasMessageContaining("Save the Delhivery details");
        service.save("DELHIVERY", false, Map.of("token", "tok", "pickupLocation", "PUNE-1"));
        assertThatThrownBy(() -> service.test("DELHIVERY")).hasMessageContaining("Enable Delhivery");
        service.save("DELHIVERY", true, Map.of("pickupLocation", "PUNE-1"));

        answer.set(ConnectionTest.failed("token rejected"));
        IntegrationView failed = service.test("DELHIVERY");
        assertThat(failed.lastTestOk()).isFalse();
        assertThat(failed.lastTestMessage()).isEqualTo("token rejected");
        assertThat(failed.lastTestedAt()).isNotNull();

        answer.set(ConnectionTest.ok("Connected"));
        assertThat(service.test("DELHIVERY").lastTestOk()).isTrue();

        IntegrationView changed = service.save("DELHIVERY", true, Map.of("token", "new-token", "pickupLocation", "PUNE-1"));
        assertThat(changed.lastTestOk()).isNull();
    }

    @Test
    @DisplayName("disconnect removes the credentials and switches the courier off, even when the server has its own")
    void disconnect() {
        env.getDelhivery().setEnabled(true);
        env.getDelhivery().setToken("server-token");
        assertThat(find(service.list(), "DELHIVERY").source()).isEqualTo("ENVIRONMENT");
        service.save("DELHIVERY", true, Map.of("token", "tok", "pickupLocation", "PUNE-1"));

        IntegrationView v = service.disconnect("DELHIVERY");

        assertThat(v.connected()).isFalse();
        assertThat(v.enabled()).isFalse();
        assertThat(v.source()).isEqualTo("COMPANY");
        assertThat(fx.rows.get(0).getSecretsEncrypted()).isNull();
        assertThat(fx.rows.get(0).getSettingsJson()).isNull();
    }

    @Test
    @DisplayName("a secret that no longer decrypts is reported, and can be replaced by typing it again")
    void unreadable() {
        fx.saved(COMPANY, "SHIPROCKET", true, Map.of("email", "a@b.test", "pickupLocation", "P"), Map.of("password", "x"));
        CourierIntegrationService rotatedKey = new CourierIntegrationService(fx.repository, new CourierIntegrationFixtures().codec,
                env, List.of(adapter("SHIPROCKET")), new ProviderCallLimiter(), mock(AuditService.class));

        IntegrationView v = find(rotatedKey.list(), "SHIPROCKET");
        assertThat(v.credentialsUnreadable()).isTrue();
        assertThat(v.connected()).isFalse();

        IntegrationView fixed = rotatedKey.save("SHIPROCKET", true, Map.of("email", "a@b.test", "password", "new", "pickupLocation", "P"));
        assertThat(fixed.credentialsUnreadable()).isFalse();
        assertThat(fixed.connected()).isTrue();
    }
}
