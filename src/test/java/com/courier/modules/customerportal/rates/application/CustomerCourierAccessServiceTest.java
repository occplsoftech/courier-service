package com.courier.modules.customerportal.rates.application;

import com.courier.modules.customer.application.CustomerPortalLookup;
import com.courier.modules.customerportal.rates.domain.CourierProviderAdapter;
import com.courier.modules.customerportal.rates.domain.CustomerCourierAccess;
import com.courier.modules.customerportal.rates.domain.CustomerCourierAccessRepository;
import com.courier.modules.customerportal.rates.domain.ProviderResult;
import com.courier.modules.customerportal.rates.domain.RateRequest;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.exception.BusinessRuleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CustomerCourierAccessServiceTest {

    private final UUID company = UUID.randomUUID();
    private final UUID customer = UUID.randomUUID();
    private final CustomerCourierAccessRepository repository = mock(CustomerCourierAccessRepository.class);
    private final CustomerCourierAccessService service = new CustomerCourierAccessService(repository,
            List.of(adapter("OCCPL_NETWORK"), adapter("DELHIVERY"), adapter("SHIPROCKET")), mock(CustomerPortalLookup.class),
            mock(AuditService.class));

    private static CourierProviderAdapter adapter(String code) {
        return new CourierProviderAdapter() {
            @Override public String code() { return code; }
            @Override public String displayName() { return code; }
            @Override public boolean external() { return !code.equals("OCCPL_NETWORK"); }
            @Override public boolean configured() { return true; }
            @Override public ProviderResult getRates(RateRequest r) { return null; }
        };
    }

    @Test
    @DisplayName("nothing assigned: the customer sees OCCPL Network only")
    void defaultIsOccpl() {
        when(repository.findCodes(company, customer)).thenReturn(List.of());

        assertThat(service.allowedCodes(company, customer)).isEqualTo(Set.of("OCCPL_NETWORK"));
    }

    @Test
    @DisplayName("assigned couriers are exactly what the customer sees; OCCPL Network only if it is one of them")
    void assignedOnly() {
        when(repository.findCodes(company, customer)).thenReturn(List.of("DELHIVERY"));

        assertThat(service.allowedCodes(company, customer)).isEqualTo(Set.of("DELHIVERY"));
    }

    @Test
    @DisplayName("an unknown courier code is refused before anything is changed")
    void unknownCode() {
        // the caller check (COMPANY_ADMIN) is on the method; with no security context the code check is reached through the lookup
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> service.save(customer, List.of("NOPE"))).isInstanceOf(RuntimeException.class);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(any(CustomerCourierAccess.class));
    }
}
