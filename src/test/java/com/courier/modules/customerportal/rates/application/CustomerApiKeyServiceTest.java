package com.courier.modules.customerportal.rates.application;

import com.courier.modules.customerportal.application.ActingCustomer;
import com.courier.modules.customerportal.application.CurrentCustomerResolver;
import com.courier.modules.customerportal.rates.domain.CustomerApiKey;
import com.courier.modules.customerportal.rates.domain.CustomerApiKeyRepository;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CustomerApiKeyServiceTest {

    private static final ActingCustomer ACTING = new ActingCustomer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private final CurrentCustomerResolver resolver = mock(CurrentCustomerResolver.class);
    private final CustomerApiKeyRepository keys = mock(CustomerApiKeyRepository.class);
    private final List<CustomerApiKey> stored = new ArrayList<>();
    private CustomerApiKeyService service;

    @BeforeEach
    void setUp() {
        when(resolver.require()).thenReturn(ACTING);
        when(keys.findForCustomer(ACTING.companyId(), ACTING.customerId())).thenAnswer(i -> List.copyOf(stored));
        when(keys.save(any(CustomerApiKey.class))).thenAnswer(i -> {
            CustomerApiKey k = i.getArgument(0);
            if (k.getId() == null) {
                k.setId(UUID.randomUUID());
            }
            if (k.getCompanyId() == null) {
                k.setCompanyId(ACTING.companyId());
            }
            if (!stored.contains(k)) {
                stored.add(k);
            }
            return k;
        });
        service = new CustomerApiKeyService(resolver, keys, mock(AuditService.class));
    }

    @Test
    @DisplayName("a new key is shown once; only its SHA-256 and first characters are kept, and it signs the customer in")
    void createAndAuthenticate() {
        var created = service.create("Website");
        assertThat(created.key()).startsWith("occ_").hasSize(44);
        CustomerApiKey k = stored.get(0);
        assertThat(k.getKeyHash()).isEqualTo(CustomerApiKeyService.hash(created.key())).hasSize(64).doesNotContain(created.key());
        assertThat(k.getKeyPrefix()).isEqualTo(created.key().substring(0, 12));
        assertThat(created.view().keyPrefix()).isEqualTo(k.getKeyPrefix());

        when(keys.findByHash(k.getKeyHash())).thenReturn(Optional.of(k));
        var owner = service.authenticate(created.key()).orElseThrow();
        assertThat(owner.customerId()).isEqualTo(ACTING.customerId());
        assertThat(owner.companyId()).isEqualTo(ACTING.companyId());
        assertThat(k.getLastUsedAt()).isNotNull();

        assertThat(service.authenticate("wrong")).isEmpty();
        assertThat(service.authenticate(null)).isEmpty();
    }

    @Test
    @DisplayName("a revoked key stops working; another customer's key cannot be revoked")
    void revoke() {
        var created = service.create(null);
        CustomerApiKey k = stored.get(0);
        assertThat(k.getName()).isEqualTo("API key");
        when(keys.findById(k.getId())).thenReturn(Optional.of(k));
        service.revoke(k.getId());
        assertThat(k.getRevokedAt()).isNotNull();
        when(keys.findByHash(k.getKeyHash())).thenReturn(Optional.of(k));
        assertThat(service.authenticate(created.key())).isEmpty();

        CustomerApiKey other = CustomerApiKey.builder().customerId(UUID.randomUUID()).name("x").keyPrefix("occ_x").keyHash("h").build();
        other.setId(UUID.randomUUID());
        other.setCompanyId(ACTING.companyId());
        when(keys.findById(other.getId())).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> service.revoke(other.getId())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("at most five active keys")
    void maxFive() {
        for (int i = 0; i < 5; i++) {
            service.create("k" + i);
        }
        assertThatThrownBy(() -> service.create("six")).isInstanceOf(BusinessRuleException.class).hasMessageContaining("5 active keys");
        stored.get(0).setRevokedAt(Instant.now());
        assertThat(service.create("six").key()).startsWith("occ_");
        ArgumentCaptor<CustomerApiKey> c = ArgumentCaptor.forClass(CustomerApiKey.class);
        org.mockito.Mockito.verify(keys, org.mockito.Mockito.atLeast(6)).save(c.capture());
    }
}
