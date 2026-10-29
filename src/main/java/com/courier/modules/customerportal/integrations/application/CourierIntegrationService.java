package com.courier.modules.customerportal.integrations.application;

import com.courier.modules.customerportal.integrations.application.CourierCatalog.Field;
import com.courier.modules.customerportal.integrations.application.CourierCatalog.Provider;
import com.courier.modules.customerportal.integrations.domain.CourierIntegration;
import com.courier.modules.customerportal.integrations.domain.CourierIntegrationRepository;
import com.courier.modules.customerportal.rates.application.CustomerPortalProperties;
import com.courier.modules.customerportal.rates.application.ProviderCallLimiter;
import com.courier.modules.customerportal.rates.domain.ConnectionTest;
import com.courier.modules.customerportal.rates.domain.CourierProviderAdapter;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.audit.domain.AuditAction;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import com.courier.shared.security.Roles;
import com.courier.shared.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A company administrator's view and control of the company's courier accounts. Secrets are write-only: they are stored
 * encrypted, never returned (only "is set"), and a blank value on save keeps the stored one. Every call is for the caller's
 * own company; no id of another company is ever accepted.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CourierIntegrationService {

    private static final String COMPANY_ADMIN = "hasRole('" + Roles.COMPANY_ADMIN + "')";
    private static final String ENTITY = "CourierIntegration";
    private static final int MAX_VALUE = 200;

    private final CourierIntegrationRepository repository;
    private final CourierSecretsCodec codec;
    private final CustomerPortalProperties properties;
    private final List<CourierProviderAdapter> adapters;
    private final ProviderCallLimiter limiter;
    private final AuditService auditService;

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    @org.springframework.beans.factory.annotation.Value("${app.auth.app-base-url:http://localhost:3000}")
    private String appBaseUrl = "http://localhost:3000";

    @Transactional
    @PreAuthorize(COMPANY_ADMIN)
    public List<IntegrationView> list() {
        UUID companyId = CompanyContext.requireCompanyId();
        return CourierCatalog.all().stream().map(p -> view(p, find(companyId, p.code()).map(this::withWebhookToken))).toList();
    }

    /**
     * Saves the company's account with one courier. {@code values} holds each field the administrator typed; a secret field
     * left out or blank keeps the stored secret, a plain field left out is cleared. Enabling needs every required field.
     */
    @Transactional
    @PreAuthorize(COMPANY_ADMIN)
    public IntegrationView save(String code, boolean enabled, Map<String, String> values) {
        UUID companyId = CompanyContext.requireCompanyId();
        Provider provider = connectable(code);
        Map<String, String> input = values == null ? Map.of() : values;
        for (String key : input.keySet()) {
            if (provider.field(key).isEmpty()) {
                throw new BusinessRuleException("Unknown field '" + key + "' for " + provider.name() + ".");
            }
        }
        CourierIntegration row = find(companyId, provider.code()).orElseGet(() -> {
            CourierIntegration created = new CourierIntegration();
            created.setProviderCode(provider.code());
            return created;
        });
        Map<String, String> settings = new LinkedHashMap<>();
        Map<String, String> secrets = storedSecrets(row);
        List<String> rotated = new java.util.ArrayList<>();
        for (Field f : provider.fields()) {
            String typed = clean(input.get(f.key()));
            if (typed != null && typed.length() > MAX_VALUE) {
                throw new BusinessRuleException(f.label() + " is too long.");
            }
            if (f.secret()) {
                if (typed != null) {
                    secrets.put(f.key(), typed);
                    rotated.add(f.key());
                }
            } else if (typed != null) {
                settings.put(f.key(), typed);
            }
        }
        if (enabled) {
            for (Field f : provider.fields()) {
                String have = f.secret() ? secrets.get(f.key()) : settings.get(f.key());
                if (f.required() && (have == null || have.isBlank())) {
                    throw new BusinessRuleException(f.label() + " is required to connect " + provider.name() + ".");
                }
            }
        }
        row.setSettingsJson(codec.writeSettings(settings));
        row.setSecretsEncrypted(codec.encryptSecrets(secrets));
        row.setEnabled(enabled);
        if (!rotated.isEmpty() || row.getId() == null) {
            // New credentials: the last test no longer describes them.
            row.setLastTestedAt(null);
            row.setLastTestOk(null);
            row.setLastTestMessage(null);
        }
        CourierIntegration saved = repository.save(withWebhookToken(row));
        auditService.record(AuditAction.COURIER_INTEGRATION_SAVED, ENTITY, saved.getId(),
                Map.of("provider", provider.code(), "enabled", enabled, "secretsChanged", rotated));
        return view(provider, Optional.of(saved));
    }

    /**
     * Calls the courier with the saved, enabled credentials and records the outcome. Deliberately not one transaction:
     * the call to the courier must not hold a database connection while it waits.
     */
    @PreAuthorize(COMPANY_ADMIN)
    public IntegrationView test(String code) {
        UUID companyId = CompanyContext.requireCompanyId();
        Provider provider = connectable(code);
        CourierIntegration row = find(companyId, provider.code())
                .orElseThrow(() -> new BusinessRuleException("Save the " + provider.name() + " details before testing them."));
        if (!row.isEnabled()) {
            throw new BusinessRuleException("Enable " + provider.name() + " before testing it.");
        }
        limiter.check(SecurityUtils.requireCurrentUser().userId());
        CourierProviderAdapter adapter = adapters.stream().filter(a -> a.code().equals(provider.code())).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Courier", provider.code()));
        ConnectionTest outcome;
        try {
            outcome = adapter.testConnection(companyId);
        } catch (RuntimeException e) {
            log.warn("Testing {} for company {} failed unexpectedly: {}", provider.code(), companyId, e.getClass().getSimpleName());
            outcome = ConnectionTest.failed(provider.name() + " could not be reached. Please try again.");
        }
        row.setLastTestedAt(Instant.now());
        row.setLastTestOk(outcome.ok());
        row.setLastTestMessage(truncate(outcome.message(), 255));
        repository.save(row);
        auditService.record(AuditAction.COURIER_INTEGRATION_TESTED, ENTITY, row.getId(),
                Map.of("provider", provider.code(), "ok", outcome.ok()));
        return view(provider, Optional.of(row));
    }

    /** Removes the company's credentials and switches the courier off for it (the server's built-in settings are NOT used afterwards). */
    @Transactional
    @PreAuthorize(COMPANY_ADMIN)
    public IntegrationView disconnect(String code) {
        UUID companyId = CompanyContext.requireCompanyId();
        Provider provider = connectable(code);
        CourierIntegration row = find(companyId, provider.code()).orElseGet(() -> {
            CourierIntegration created = new CourierIntegration();
            created.setProviderCode(provider.code());
            return created;
        });
        row.setEnabled(false);
        row.setSettingsJson(null);
        row.setSecretsEncrypted(null);
        row.setLastTestedAt(null);
        row.setLastTestOk(null);
        row.setLastTestMessage(null);
        CourierIntegration saved = repository.save(row);
        auditService.record(AuditAction.COURIER_INTEGRATION_DISCONNECTED, ENTITY, saved.getId(), Map.of("provider", provider.code()));
        return view(provider, Optional.of(saved));
    }

    // ------------------------------------------------------------------ internals

    private Provider connectable(String code) {
        Provider p = CourierCatalog.find(code).orElseThrow(() -> new ResourceNotFoundException("Courier", code));
        if (!p.available()) {
            throw new BusinessRuleException(p.name() + " cannot be connected yet: " + p.note());
        }
        return p;
    }

    private Optional<CourierIntegration> find(UUID companyId, String code) {
        try {
            return repository.findByProvider(companyId, code);
        } catch (DataAccessException e) {
            log.warn("Courier integration {} of company {} could not be loaded: {}", code, companyId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Gives a saved integration its webhook secret once (rows saved before V135 get it the next time they are listed). */
    private CourierIntegration withWebhookToken(CourierIntegration row) {
        if (row.getWebhookToken() == null && CourierCatalog.find(row.getProviderCode()).map(Provider::available).orElse(false)) {
            byte[] bytes = new byte[24];
            RANDOM.nextBytes(bytes);
            row.setWebhookToken(java.util.HexFormat.of().formatHex(bytes));
            if (row.getId() != null) {
                return repository.save(row);
            }
        }
        return row;
    }

    /** Couriers that can push status updates to a webhook. sKart has no webhooks: its statuses come from the hourly sync. */
    private static boolean statusPush(String code) {
        return !CourierCatalog.SKART.equals(code);
    }

    /** The address the courier calls with status updates; null until the integration has been saved. */
    private String webhookUrl(CourierIntegration row) {
        if (row == null || row.getWebhookToken() == null || !statusPush(row.getProviderCode())) {
            return null;
        }
        String base = properties.getTrackingSync().getPublicBaseUrl();
        if (base == null || base.isBlank()) {
            base = appBaseUrl;
        }
        return base.replaceAll("/+$", "") + "/api/v1/webhooks/courier-tracking/" + row.getWebhookToken();
    }

    /** The stored secrets, or none when they cannot be decrypted any more (the administrator must enter them again). */
    private Map<String, String> storedSecrets(CourierIntegration row) {
        if (row.getSecretsEncrypted() == null) {
            return new LinkedHashMap<>();
        }
        try {
            return codec.decryptSecrets(row.getSecretsEncrypted());
        } catch (IllegalStateException e) {
            log.warn("Stored secrets of courier integration {} cannot be decrypted; they must be entered again", row.getProviderCode());
            return new LinkedHashMap<>();
        }
    }

    private IntegrationView view(Provider p, Optional<CourierIntegration> saved) {
        Map<String, String> settings = new LinkedHashMap<>();
        Map<String, String> secrets = new LinkedHashMap<>();
        boolean unreadable = false;
        if (saved.isPresent()) {
            try {
                settings = codec.readSettings(saved.get().getSettingsJson());
                if (saved.get().getSecretsEncrypted() != null) {
                    secrets = codec.decryptSecrets(saved.get().getSecretsEncrypted());
                }
            } catch (IllegalStateException e) {
                unreadable = true;
            }
        }
        List<FieldView> fields = new java.util.ArrayList<>();
        for (Field f : p.fields()) {
            fields.add(f.secret()
                    ? new FieldView(f.key(), f.label(), true, f.required(), f.help(), null, secrets.containsKey(f.key()))
                    : new FieldView(f.key(), f.label(), false, f.required(), f.help(), settings.get(f.key()), settings.containsKey(f.key())));
        }
        boolean complete = p.fields().stream().allMatch(f -> !f.required() || fields.stream()
                .anyMatch(v -> v.key().equals(f.key()) && v.set()));
        boolean enabled = saved.map(CourierIntegration::isEnabled).orElse(false);
        String source;
        boolean connected;
        if (saved.isPresent()) {
            source = "COMPANY";
            connected = enabled && complete && !unreadable;
        } else if (environmentConfigured(p.code())) {
            source = "ENVIRONMENT";
            connected = true;
        } else {
            source = "NONE";
            connected = false;
        }
        CourierIntegration row = saved.orElse(null);
        return new IntegrationView(p.code(), p.name(), p.available(), p.note(), source, enabled, connected, unreadable, fields,
                row == null ? null : row.getLastTestedAt(), row == null ? null : row.getLastTestOk(),
                row == null ? null : row.getLastTestMessage(), row == null ? null : row.getLogoDataUrl(), webhookUrl(row),
                statusPush(p.code()));
    }

    private boolean environmentConfigured(String code) {
        return switch (code) {
            case CourierCatalog.SHIPROCKET -> properties.getShiprocket().configured();
            case CourierCatalog.DELHIVERY -> properties.getDelhivery().configured();
            case CourierCatalog.SHIPYAARI -> properties.getShipyaari().configured();
            case CourierCatalog.SKART -> properties.getSkart().configured();
            default -> false;
        };
    }

    private static String clean(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String truncate(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max));
    }

    /** {@code source}: COMPANY (saved by the administrator), ENVIRONMENT (the server's built-in settings) or NONE. */
    public record IntegrationView(String code, String name, boolean available, String note, String source, boolean enabled,
                                  boolean connected, boolean credentialsUnreadable, List<FieldView> fields,
                                  Instant lastTestedAt, Boolean lastTestOk, String lastTestMessage, String logoDataUrl,
                                  String webhookUrl, boolean statusPush) {
    }

    /** {@code value} is null for a secret; {@code set} says whether it has a value. */
    public record FieldView(String key, String label, boolean secret, boolean required, String help, String value, boolean set) {
    }
}
