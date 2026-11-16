package com.courier.modules.customerportal.rates.application;

import com.courier.modules.customerportal.application.ActingCustomer;
import com.courier.modules.customerportal.application.CurrentCustomerResolver;
import com.courier.modules.customerportal.rates.domain.CustomerApiKey;
import com.courier.modules.customerportal.rates.domain.CustomerApiKeyRepository;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.audit.domain.AuditAction;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * API keys a business customer's own system uses for the public order API (13.3). A key is shown once when created; only its SHA-256
 * is stored. Up to {@link #MAX_ACTIVE} active keys per customer; a revoked key stops working at once.
 */
@Service
@RequiredArgsConstructor
public class CustomerApiKeyService {

    static final int MAX_ACTIVE = 5;
    static final String PREFIX = "occ_";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration TOUCH_EVERY = Duration.ofMinutes(5);

    private final CurrentCustomerResolver resolver;
    private final CustomerApiKeyRepository keys;
    private final AuditService auditService;

    /** The customer a key belongs to. */
    public record KeyOwner(UUID companyId, UUID customerId, UUID keyId) {
    }

    public record KeyView(UUID id, String name, String keyPrefix, Instant createdAt, Instant lastUsedAt, Instant revokedAt) {
        static KeyView of(CustomerApiKey k) {
            return new KeyView(k.getId(), k.getName(), k.getKeyPrefix(), k.getCreatedAt(), k.getLastUsedAt(), k.getRevokedAt());
        }
    }

    /** A new key: {@code key} is the only time the full key is seen. */
    public record CreatedKey(KeyView view, String key) {
    }

    @Transactional(readOnly = true)
    public List<KeyView> list() {
        ActingCustomer acting = resolver.require();
        return keys.findForCustomer(acting.companyId(), acting.customerId()).stream().map(KeyView::of).toList();
    }

    @Transactional
    public CreatedKey create(String name) {
        ActingCustomer acting = resolver.require();
        String label = name == null || name.isBlank() ? "API key" : name.trim();
        if (label.length() > 60) {
            throw new BusinessRuleException("Give the key a name of up to 60 characters.");
        }
        long active = keys.findForCustomer(acting.companyId(), acting.customerId()).stream().filter(CustomerApiKey::isActive).count();
        if (active >= MAX_ACTIVE) {
            throw new BusinessRuleException("You already have " + MAX_ACTIVE + " active keys. Revoke one you no longer use first.");
        }
        byte[] random = new byte[30];
        RANDOM.nextBytes(random);
        String raw = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        CustomerApiKey k = keys.save(CustomerApiKey.builder().customerId(acting.customerId()).name(label)
                .keyPrefix(raw.substring(0, 12)).keyHash(hash(raw)).build());
        auditService.record(AuditAction.CUSTOMER_API_KEY_CREATED, "CustomerApiKey", k.getId(),
                Map.of("customerId", acting.customerId().toString(), "name", label, "prefix", k.getKeyPrefix()));
        return new CreatedKey(KeyView.of(k), raw);
    }

    @Transactional
    public KeyView revoke(UUID keyId) {
        ActingCustomer acting = resolver.require();
        CustomerApiKey k = keys.findById(keyId)
                .filter(x -> acting.companyId().equals(x.getCompanyId()) && acting.customerId().equals(x.getCustomerId()))
                .orElseThrow(() -> new ResourceNotFoundException("API key", keyId));
        if (k.isActive()) {
            k.setRevokedAt(Instant.now());
            keys.save(k);
            auditService.record(AuditAction.CUSTOMER_API_KEY_REVOKED, "CustomerApiKey", k.getId(),
                    Map.of("customerId", acting.customerId().toString(), "prefix", k.getKeyPrefix()));
        }
        return KeyView.of(k);
    }

    /** The owner of an active key, or empty (unknown, malformed or revoked). Records when it was last used, at most every few minutes. */
    @Transactional
    public Optional<KeyOwner> authenticate(String raw) {
        if (raw == null || !raw.startsWith(PREFIX) || raw.length() < 20 || raw.length() > 100) {
            return Optional.empty();
        }
        return keys.findByHash(hash(raw.trim())).filter(CustomerApiKey::isActive).map(k -> {
            Instant now = Instant.now();
            if (k.getLastUsedAt() == null || k.getLastUsedAt().isBefore(now.minus(TOUCH_EVERY))) {
                k.setLastUsedAt(now);
                keys.save(k);
            }
            return new KeyOwner(k.getCompanyId(), k.getCustomerId(), k.getId());
        });
    }

    static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
