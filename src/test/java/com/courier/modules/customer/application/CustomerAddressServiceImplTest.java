package com.courier.modules.customer.application;

import com.courier.modules.customer.application.command.CreateCustomerAddressCommand;
import com.courier.modules.customer.application.command.UpdateCustomerAddressCommand;
import com.courier.modules.customer.domain.AddressType;
import com.courier.modules.customer.domain.Customer;
import com.courier.modules.customer.domain.CustomerAddress;
import com.courier.modules.customer.domain.CustomerAddressRepository;
import com.courier.modules.customer.domain.CustomerRepository;
import com.courier.modules.customer.domain.CustomerStatus;
import com.courier.modules.customer.domain.CustomerType;
import com.courier.modules.master.application.AreaService;
import com.courier.modules.master.application.CityService;
import com.courier.modules.master.application.CountryService;
import com.courier.modules.master.application.DistrictService;
import com.courier.modules.master.application.PincodeService;
import com.courier.modules.master.application.StateService;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Address rules — geography validation, duplicate detection, single-default exclusivity. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CustomerAddressServiceImplTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID CALLER = UUID.randomUUID();

    @Mock private CustomerAddressRepository repository;
    @Mock private CustomerRepository customerRepository;
    @Mock private CountryService countryService;
    @Mock private StateService stateService;
    @Mock private DistrictService districtService;
    @Mock private CityService cityService;
    @Mock private AreaService areaService;
    @Mock private PincodeService pincodeService;
    @Mock private AuditService auditService;

    private CustomerAddressServiceImpl service;
    private Customer customer;
    private List<CustomerAddress> existingAddresses;

    @BeforeEach
    void setUp() {
        service = new CustomerAddressServiceImpl(repository, customerRepository,
                countryService, stateService, districtService, cityService, areaService, pincodeService,
                auditService);
        CompanyContext.setCompanyId(COMPANY);
        signedIn(Roles.COMPANY_ADMIN);

        customer = Customer.builder()
                .customerCode("CUST1").customerType(CustomerType.INDIVIDUAL)
                .firstName("Asha").lastName("Shah").mobile("9876500000")
                .status(CustomerStatus.ACTIVE).build();
        customer.setCompanyId(COMPANY);
        when(customerRepository.findByIdWithinCompany(customer.getId(), COMPANY))
                .thenReturn(Optional.of(customer));

        existingAddresses = new ArrayList<>();
        when(repository.findAllByCustomerIdWithinCompany(customer.getId(), COMPANY))
                .thenAnswer(i -> List.copyOf(existingAddresses));
        when(repository.findByIdWithinCompany(any(UUID.class), any(UUID.class))).thenAnswer(i -> {
            UUID id = i.getArgument(0);
            return existingAddresses.stream().filter(a -> a.getId().equals(id)).findFirst();
        });
        when(repository.save(any(CustomerAddress.class))).thenAnswer(i -> {
            CustomerAddress a = i.getArgument(0);
            existingAddresses.removeIf(x -> x.getId().equals(a.getId()));
            existingAddresses.add(a);
            return a;
        });
    }

    @AfterEach
    void tearDown() {
        CompanyContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("an address for an unknown customer is reported as not found")
    void unknownCustomerNotFound() {
        UUID foreign = UUID.randomUUID();
        when(customerRepository.findByIdWithinCompany(foreign, COMPANY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(foreign, addressCommand("MG Road", null, false, false)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("a pincode id that does not exist in the global masters is refused")
    void invalidGeographyRejected() {
        UUID badPincode = UUID.randomUUID();
        when(pincodeService.getById(badPincode)).thenThrow(new ResourceNotFoundException("Pincode", badPincode));

        CreateCustomerAddressCommand command = new CreateCustomerAddressCommand(
                AddressType.HOME, null, null, null, null, null, null, badPincode,
                "MG Road", null, null, null, null, false, false);

        assertThatThrownBy(() -> service.create(customer.getId(), command))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("pincode");
    }

    @Test
    @DisplayName("a duplicate address (same lines and pincode) is refused")
    void duplicateAddressRejected() {
        UUID pincode = UUID.randomUUID();
        service.create(customer.getId(), addressCommand("MG Road", pincode, false, false));

        assertThatThrownBy(() -> service.create(customer.getId(), addressCommand("mg road ", pincode, false, false)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already has an address");
    }

    @Test
    @DisplayName("a business customer's first default pickup address sets their home branch from its pincode, once (13.2)")
    void homeBranchFromPickupPincode() {
        var homeBranches = org.mockito.Mockito.mock(com.courier.modules.customer.domain.HomeBranchLookupPort.class);
        service.setHomeBranches(homeBranches);
        UUID pincode = UUID.randomUUID();
        UUID branch = UUID.randomUUID();
        when(homeBranches.branchServingPincode(COMPANY, pincode)).thenReturn(Optional.of(branch));
        customer.setCustomerType(CustomerType.BUSINESS);

        service.create(customer.getId(), addressCommand("Warehouse", null, false, true));   // not a pickup: nothing
        assertThat(customer.getHomeBranchId()).isNull();

        service.create(customer.getId(), addressCommand("Office", pincode, true, false));
        assertThat(customer.getHomeBranchId()).isEqualTo(branch);

        UUID chosen = UUID.randomUUID();
        customer.setHomeBranchId(chosen);                                                     // staff changed it: kept
        service.create(customer.getId(), addressCommand("New office", pincode, true, false));
        assertThat(customer.getHomeBranchId()).isEqualTo(chosen);
    }

    @Test
    @DisplayName("a second default pickup address clears the flag on the first")
    void defaultPickupIsExclusive() {
        CustomerAddress first = service.create(customer.getId(), addressCommand("Home", null, true, false));
        assertThat(first.isDefaultPickup()).isTrue();

        CustomerAddress second = service.create(customer.getId(), addressCommand("Office", null, true, false));

        assertThat(second.isDefaultPickup()).isTrue();
        assertThat(existingAddresses.stream().filter(a -> a.getId().equals(first.getId())).findFirst()
                .orElseThrow().isDefaultPickup()).isFalse();
    }

    @Test
    @DisplayName("default delivery is independent of default pickup")
    void defaultDeliveryIndependentOfPickup() {
        CustomerAddress home = service.create(customer.getId(), addressCommand("Home", null, true, false));
        CustomerAddress office = service.create(customer.getId(), addressCommand("Office", null, false, true));

        assertThat(office.isDefaultDelivery()).isTrue();
        assertThat(existingAddresses.stream().filter(a -> a.getId().equals(home.getId())).findFirst()
                .orElseThrow().isDefaultPickup()).isTrue();
    }

    @Test
    @DisplayName("updating an address to itself is not a duplicate against itself")
    void updateAgainstSelfNotDuplicate() {
        UUID pincode = UUID.randomUUID();
        CustomerAddress created = service.create(customer.getId(), addressCommand("MG Road", pincode, false, false));

        UpdateCustomerAddressCommand update = new UpdateCustomerAddressCommand(
                AddressType.OFFICE, null, null, null, null, null, null, pincode,
                "MG Road", null, null, null, null, false, false, created.getVersion());

        CustomerAddress updated = service.update(customer.getId(), created.getId(), update);

        assertThat(updated.getAddressType()).isEqualTo(AddressType.OFFICE);
    }

    @Test
    @DisplayName("an address of a different customer returns not found even within the same company")
    void addressOfDifferentCustomerNotFound() {
        UUID pincode = UUID.randomUUID();
        CustomerAddress created = service.create(customer.getId(), addressCommand("MG Road", pincode, false, false));
        when(repository.findByIdWithinCompany(created.getId(), COMPANY)).thenReturn(Optional.of(created));

        Customer other = Customer.builder()
                .customerCode("CUST2").customerType(CustomerType.INDIVIDUAL)
                .firstName("Neha").lastName("Patil").mobile("9876500001")
                .status(CustomerStatus.ACTIVE).build();
        other.setCompanyId(COMPANY);
        when(customerRepository.findByIdWithinCompany(other.getId(), COMPANY)).thenReturn(Optional.of(other));

        UpdateCustomerAddressCommand update = new UpdateCustomerAddressCommand(
                AddressType.OFFICE, null, null, null, null, null, null, pincode,
                "MG Road", null, null, null, null, false, false, created.getVersion());

        assertThatThrownBy(() -> service.update(other.getId(), created.getId(), update))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------- helpers

    private void signedIn(String role) {
        AuthenticatedUser principal = new AuthenticatedUser(
                CALLER, COMPANY, "admin@legacy.test", Set.of(role), "jti");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.authorities()));
    }

    private static CreateCustomerAddressCommand addressCommand(String line1, UUID pincodeId,
                                                                boolean defaultPickup, boolean defaultDelivery) {
        return new CreateCustomerAddressCommand(
                AddressType.HOME, null, null, null, null, null, null, pincodeId,
                line1, null, null, null, null, defaultPickup, defaultDelivery);
    }
}
