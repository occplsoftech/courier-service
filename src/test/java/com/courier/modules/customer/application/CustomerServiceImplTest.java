package com.courier.modules.customer.application;

import com.courier.modules.customer.application.command.CreateCustomerCommand;
import com.courier.modules.customer.application.command.UpdateCustomerCommand;
import com.courier.modules.customer.domain.Customer;
import com.courier.modules.customer.domain.CustomerRepository;
import com.courier.modules.customer.domain.CustomerStatus;
import com.courier.modules.customer.domain.CustomerType;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.DuplicateResourceException;
import com.courier.shared.exception.ResourceNotFoundException;
import com.courier.shared.security.AuthenticatedUser;
import com.courier.shared.security.Roles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Customer rules, with the repository and audit trail mocked. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CustomerServiceImplTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID CALLER = UUID.randomUUID();

    @Mock private CustomerRepository repository;
    @Mock private AuditService auditService;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;

    private CustomerServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CustomerServiceImpl(repository, auditService, eventPublisher);
        CompanyContext.setCompanyId(COMPANY);
        signedIn(Roles.COMPANY_ADMIN);

        when(repository.save(any(Customer.class))).thenAnswer(i -> i.getArgument(0));
        when(repository.isCodeTaken(any(), anyString(), any())).thenReturn(false);
        when(repository.isMobileTaken(any(), anyString(), any())).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        CompanyContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a blank customer code is generated")
    void generatesCodeWhenBlank() {
        Customer created = service.create(command(null, CustomerType.INDIVIDUAL, "9876543210", null));

        assertThat(created.getCustomerCode()).isNotBlank().startsWith("CUST");
    }

    @Test
    @DisplayName("creating a customer publishes CustomerCreated, with the type the wallet rule reads")
    void publishesCustomerCreated() {
        service.create(command(null, CustomerType.BUSINESS, "9876543210", "27ABCDE1234F1Z5"));

        org.mockito.ArgumentCaptor<Object> event = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOfSatisfying(
                com.courier.modules.customer.application.event.CustomerEvent.CustomerCreated.class, e -> {
                    assertThat(e.companyId()).isEqualTo(COMPANY);
                    assertThat(e.customerType()).isEqualTo(CustomerType.BUSINESS);
                    assertThat(e.customerCode()).startsWith("CUST");
                });
    }

    @Test
    @DisplayName("a refused create publishes nothing")
    void refusedCreatePublishesNothing() {
        when(repository.isCodeTaken(COMPANY, "DUP", null)).thenReturn(true);

        assertThatThrownBy(() -> service.create(command("DUP", CustomerType.INDIVIDUAL, "9876543210", null)))
                .isInstanceOf(DuplicateResourceException.class);

        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("a supplied customer code is normalised and checked for availability")
    void suppliedCodeNormalised() {
        Customer created = service.create(command("cust one", CustomerType.INDIVIDUAL, "9876543210", null));

        assertThat(created.getCustomerCode()).isEqualTo("CUST_ONE");
        verify(repository).isCodeTaken(COMPANY, "CUST_ONE", null);
    }

    @Test
    @DisplayName("a taken customer code is refused")
    void duplicateCodeRejected() {
        when(repository.isCodeTaken(COMPANY, "DUP", null)).thenReturn(true);

        assertThatThrownBy(() -> service.create(command("DUP", CustomerType.INDIVIDUAL, "9876543210", null)))
                .isInstanceOf(DuplicateResourceException.class);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a mobile already used in the company is refused")
    void duplicateMobileRejected() {
        when(repository.isMobileTaken(COMPANY, "9876543210", null)).thenReturn(true);

        assertThatThrownBy(() -> service.create(command(null, CustomerType.INDIVIDUAL, "9876543210", null)))
                .isInstanceOf(DuplicateResourceException.class);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a business customer without GST is refused")
    void businessWithoutGstRejected() {
        assertThatThrownBy(() -> service.create(command(null, CustomerType.BUSINESS, "9876543210", null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("GST");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a business customer with GST is accepted")
    void businessWithGstAccepted() {
        Customer created = service.create(command(null, CustomerType.BUSINESS, "9876543210", "27ABCDE1234F1Z5"));

        assertThat(created.getGstNumber()).isEqualTo("27ABCDE1234F1Z5");
    }

    @Test
    @DisplayName("an individual customer needs no GST")
    void individualWithoutGstAccepted() {
        Customer created = service.create(command(null, CustomerType.INDIVIDUAL, "9876543210", null));

        assertThat(created.getGstNumber()).isNull();
    }

    @Test
    @DisplayName("a foreign customer id is reported as not found")
    void foreignCustomerNotFound() {
        UUID foreign = UUID.randomUUID();
        when(repository.findByIdWithinCompany(foreign, COMPANY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(foreign))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("a stale version on update is refused")
    void staleVersionRejected() {
        Customer existing = existing("CUST1", "9876500000");
        existing.setVersion(3L);
        when(repository.findByIdWithinCompany(existing.getId(), COMPANY)).thenReturn(Optional.of(existing));

        UpdateCustomerCommand update = new UpdateCustomerCommand(
                CustomerType.INDIVIDUAL, null, "Asha", null, "Shah",
                "9876500000", null, null, null, null, null, true, true, true,
                null, null, null, null, null, null, null, null, 1L);

        assertThatThrownBy(() -> service.update(existing.getId(), update))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    @DisplayName("updating a customer's own mobile to itself is not treated as a duplicate")
    void updateExcludesSelfFromMobileCheck() {
        Customer existing = existing("CUST1", "9876500000");
        existing.setVersion(2L);
        when(repository.findByIdWithinCompany(existing.getId(), COMPANY)).thenReturn(Optional.of(existing));

        UpdateCustomerCommand update = new UpdateCustomerCommand(
                CustomerType.INDIVIDUAL, null, "Asha", null, "Shah",
                "9876500000", null, null, null, null, null, true, true, true,
                null, null, null, null, null, null, null, null, 2L);

        Customer updated = service.update(existing.getId(), update);

        assertThat(updated.getMobile()).isEqualTo("9876500000");
        verify(repository).isMobileTaken(COMPANY, "9876500000", existing.getId());
    }

    @Test
    @DisplayName("activating an already-active customer is idempotent")
    void activateIdempotent() {
        Customer existing = existing("CUST1", "9876500000");
        when(repository.findByIdWithinCompany(existing.getId(), COMPANY)).thenReturn(Optional.of(existing));

        Customer activated = service.activate(existing.getId());

        assertThat(activated.isActive()).isTrue();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("deactivate then reactivate flips status")
    void deactivateThenActivate() {
        Customer existing = existing("CUST1", "9876500000");
        when(repository.findByIdWithinCompany(existing.getId(), COMPANY)).thenReturn(Optional.of(existing));

        Customer deactivated = service.deactivate(existing.getId());
        assertThat(deactivated.getStatus()).isEqualTo(CustomerStatus.INACTIVE);

        when(repository.findByIdWithinCompany(existing.getId(), COMPANY)).thenReturn(Optional.of(deactivated));
        Customer reactivated = service.activate(existing.getId());
        assertThat(reactivated.getStatus()).isEqualTo(CustomerStatus.ACTIVE);
    }

    // ---------------------------------------------------------------- findOrCreateForBooking

    @Test
    @DisplayName("findOrCreateForBooking reuses an existing customer matched by exact mobile")
    void findOrCreateReusesExisting() {
        Customer existing = existing("CUST1", "9876500000");
        when(repository.findByCompanyIdAndMobile(COMPANY, "9876500000")).thenReturn(Optional.of(existing));

        Customer result = service.findOrCreateForBooking("Ramesh Kadam", "9876500000");

        assertThat(result).isSameAs(existing);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("findOrCreateForBooking creates an INDIVIDUAL customer, splitting the name on the first space")
    void findOrCreateCreatesWhenAbsent() {
        when(repository.findByCompanyIdAndMobile(COMPANY, "9876500001")).thenReturn(Optional.empty());

        Customer result = service.findOrCreateForBooking("Ramesh Kadam Patil", "9876500001");

        assertThat(result.getCustomerType()).isEqualTo(CustomerType.INDIVIDUAL);
        assertThat(result.getFirstName()).isEqualTo("Ramesh");
        assertThat(result.getLastName()).isEqualTo("Kadam Patil");
        assertThat(result.getMobile()).isEqualTo("9876500001");
        verify(repository).save(any(Customer.class));
    }

    @Test
    @DisplayName("findOrCreateForBooking with a single-word name leaves the surname blank, not guessed")
    void findOrCreateSingleWordName() {
        when(repository.findByCompanyIdAndMobile(COMPANY, "9876500002")).thenReturn(Optional.empty());

        Customer result = service.findOrCreateForBooking("Ramesh", "9876500002");

        assertThat(result.getFirstName()).isEqualTo("Ramesh");
        assertThat(result.getLastName()).isEmpty();
    }

    @Test
    @DisplayName("findOrCreateForBooking writes nothing for a blank mobile")
    void findOrCreateBlankMobileNoOp() {
        Customer result = service.findOrCreateForBooking("Ramesh Kadam", "  ");

        assertThat(result).isNull();
        verify(repository, never()).findByCompanyIdAndMobile(any(), any());
        verify(repository, never()).save(any());
    }

    // ---------------------------------------------------------------- helpers

    private void signedIn(String role) {
        AuthenticatedUser principal = new AuthenticatedUser(
                CALLER, COMPANY, "admin@legacy.test", Set.of(role), "jti");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.authorities()));
    }

    private static CreateCustomerCommand command(String code, CustomerType type, String mobile, String gst) {
        return new CreateCustomerCommand(code, type, null, "Asha", null, "Shah",
                mobile, null, null, gst, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    private static Customer existing(String code, String mobile) {
        Customer customer = Customer.builder()
                .customerCode(code)
                .customerType(CustomerType.INDIVIDUAL)
                .firstName("Asha")
                .lastName("Shah")
                .mobile(mobile)
                .status(CustomerStatus.ACTIVE)
                .build();
        customer.setCompanyId(COMPANY);
        customer.setVersion(0L);
        return customer;
    }
}
