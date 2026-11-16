package com.courier.modules.customer.application;

import com.courier.modules.customer.application.command.CreateCustomerAddressCommand;
import com.courier.modules.customer.application.command.UpdateCustomerAddressCommand;
import com.courier.modules.customer.domain.AddressUsage;
import com.courier.modules.customer.domain.Customer;
import com.courier.modules.customer.domain.CustomerAddress;
import com.courier.modules.customer.domain.CustomerAddressRepository;
import com.courier.modules.customer.domain.CustomerRepository;
import com.courier.modules.customer.domain.CustomerStatus;
import com.courier.modules.master.application.AreaService;
import com.courier.modules.master.application.CityService;
import com.courier.modules.master.application.CountryService;
import com.courier.modules.master.application.DistrictService;
import com.courier.modules.master.application.PincodeService;
import com.courier.modules.master.application.StateService;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.audit.domain.AuditAction;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.ResourceNotFoundException;
import com.courier.shared.security.Roles;
import com.courier.shared.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Address use cases for a customer. Validates each supplied geography id (country,
 * state, district, city, area, pincode) against the global masters — a different
 * module's tables, reached through its own application service interfaces rather than
 * its repositories or entities, per the project's cross-feature rule.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerAddressServiceImpl implements CustomerAddressService {

    private static final String CUSTOMER = "Customer";
    private static final String ENTITY = "CustomerAddress";
    private static final String WRITERS = "hasAnyRole('" + Roles.COMPANY_ADMIN + "', '"
            + Roles.BRANCH_MANAGER + "', '" + Roles.OPERATOR + "')";
    private static final String DELETE_ONLY = "hasRole('" + Roles.COMPANY_ADMIN + "')";
    private static final String READERS = "isAuthenticated()";

    private final CustomerAddressRepository repository;
    private final CustomerRepository customerRepository;
    private final CountryService countryService;
    private final StateService stateService;
    private final DistrictService districtService;
    private final CityService cityService;
    private final AreaService areaService;
    private final PincodeService pincodeService;
    private final AuditService auditService;

    // ------------------------------------------------------------------- create

    @Override
    @Transactional
    @PreAuthorize(WRITERS)
    public CustomerAddress create(UUID customerId, CreateCustomerAddressCommand command) {
        UUID companyId = requireCompany();
        Customer customer = requireCustomer(customerId, companyId);
        requireGeographyValid(command.countryId(), command.stateId(), command.districtId(),
                command.cityId(), command.areaId(), command.pincodeId());

        CustomerAddress address = CustomerAddress.builder()
                .customerId(customer.getId())
                .addressType(command.addressType())
                .addressUsage(command.addressUsage() == null ? AddressUsage.BOTH : command.addressUsage())
                .countryId(command.countryId())
                .stateId(command.stateId())
                .districtId(command.districtId())
                .cityId(command.cityId())
                .areaId(command.areaId())
                .pincodeId(command.pincodeId())
                .addressLine1(command.addressLine1())
                .addressLine2(command.addressLine2())
                .landmark(command.landmark())
                .latitude(command.latitude())
                .longitude(command.longitude())
                .defaultPickup(Boolean.TRUE.equals(command.isDefaultPickup()))
                .defaultDelivery(Boolean.TRUE.equals(command.isDefaultDelivery()))
                .status(CustomerStatus.ACTIVE)
                .build();

        address.applyInvariants();
        requireNotDuplicate(companyId, customer.getId(), address, null);

        if (address.isDefaultPickup()) {
            clearOtherDefaults(companyId, customer.getId(), null, true, false);
        }
        if (address.isDefaultDelivery()) {
            clearOtherDefaults(companyId, customer.getId(), null, false, true);
        }

        CustomerAddress saved = repository.save(address);
        fillHomeBranch(companyId, customer, saved);
        log.info("Address {} created for customer {} in company {} by {}",
                saved.getId(), customer.getCustomerCode(), companyId, currentActor());
        auditService.record(AuditAction.CUSTOMER_ADDRESS_CREATED, ENTITY, saved.getId(),
                Map.of("customerId", customer.getId().toString(), "addressType", saved.getAddressType().name()));
        return saved;
    }

    /** Where a home branch comes from (13.2); absent in tests that build the service by hand. */
    private com.courier.modules.customer.domain.HomeBranchLookupPort homeBranches;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setHomeBranches(com.courier.modules.customer.domain.HomeBranchLookupPort homeBranches) {
        this.homeBranches = homeBranches;
    }

    /** A business customer with no home branch gets the branch serving their default pickup pincode (13.2). Staff may change it later. */
    private void fillHomeBranch(UUID companyId, Customer customer, CustomerAddress address) {
        if (homeBranches == null || customer.getHomeBranchId() != null || !address.isDefaultPickup() || address.getPincodeId() == null
                || customer.getCustomerType() != com.courier.modules.customer.domain.CustomerType.BUSINESS) {
            return;
        }
        homeBranches.branchServingPincode(companyId, address.getPincodeId()).ifPresent(branchId -> {
            customer.setHomeBranchId(branchId);
            customerRepository.save(customer);
        });
    }

    // ------------------------------------------------------------------- update

    @Override
    @Transactional
    @PreAuthorize(WRITERS)
    public CustomerAddress update(UUID customerId, UUID addressId, UpdateCustomerAddressCommand command) {
        UUID companyId = requireCompany();
        Customer customer = requireCustomer(customerId, companyId);
        CustomerAddress address = loadOrThrow(addressId, customer.getId(), companyId);
        requireCurrentVersion(address, command.expectedVersion());
        requireGeographyValid(command.countryId(), command.stateId(), command.districtId(),
                command.cityId(), command.areaId(), command.pincodeId());

        address.setAddressType(command.addressType());
        address.setAddressUsage(command.addressUsage() == null ? AddressUsage.BOTH : command.addressUsage());
        address.setCountryId(command.countryId());
        address.setStateId(command.stateId());
        address.setDistrictId(command.districtId());
        address.setCityId(command.cityId());
        address.setAreaId(command.areaId());
        address.setPincodeId(command.pincodeId());
        address.setAddressLine1(command.addressLine1());
        address.setAddressLine2(command.addressLine2());
        address.setLandmark(command.landmark());
        address.setLatitude(command.latitude());
        address.setLongitude(command.longitude());
        address.setDefaultPickup(Boolean.TRUE.equals(command.isDefaultPickup()));
        address.setDefaultDelivery(Boolean.TRUE.equals(command.isDefaultDelivery()));

        address.applyInvariants();
        requireNotDuplicate(companyId, customer.getId(), address, address.getId());

        if (address.isDefaultPickup()) {
            clearOtherDefaults(companyId, customer.getId(), address.getId(), true, false);
        }
        if (address.isDefaultDelivery()) {
            clearOtherDefaults(companyId, customer.getId(), address.getId(), false, true);
        }

        CustomerAddress saved = repository.save(address);
        fillHomeBranch(companyId, customer, saved);
        log.info("Address {} updated for customer {} in company {} by {}",
                saved.getId(), customer.getCustomerCode(), companyId, currentActor());
        auditService.record(AuditAction.CUSTOMER_ADDRESS_UPDATED, ENTITY, saved.getId(),
                Map.of("customerId", customer.getId().toString()));
        return saved;
    }

    // ------------------------------------------------------------------- delete

    @Override
    @Transactional
    @PreAuthorize(DELETE_ONLY)
    public void delete(UUID customerId, UUID addressId) {
        UUID companyId = requireCompany();
        Customer customer = requireCustomer(customerId, companyId);
        CustomerAddress address = loadOrThrow(addressId, customer.getId(), companyId);

        address.softDelete(SecurityUtils.getCurrentUserId().orElse(null));
        repository.save(address);

        log.warn("Address {} deleted for customer {} in company {} by {}",
                address.getId(), customer.getCustomerCode(), companyId, currentActor());
        auditService.record(AuditAction.CUSTOMER_ADDRESS_DELETED, ENTITY, address.getId(),
                Map.of("customerId", customer.getId().toString()));
    }

    // -------------------------------------------------------------------- reads

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READERS)
    public List<CustomerAddress> listByCustomer(UUID customerId) {
        UUID companyId = requireCompany();
        Customer customer = requireCustomer(customerId, companyId);
        return repository.findAllByCustomerIdWithinCompany(customer.getId(), companyId);
    }

    // -------------------------------------------------------------------- helpers

    private Customer requireCustomer(UUID customerId, UUID companyId) {
        return customerRepository.findByIdWithinCompany(customerId, companyId)
                .orElseThrow(() -> new ResourceNotFoundException(CUSTOMER, customerId));
    }

    private CustomerAddress loadOrThrow(UUID addressId, UUID customerId, UUID companyId) {
        CustomerAddress address = repository.findByIdWithinCompany(addressId, companyId)
                .orElseThrow(() -> new ResourceNotFoundException(ENTITY, addressId));
        if (!customerId.equals(address.getCustomerId())) {
            // Same company, wrong customer: 404 rather than 403, same reasoning as a
            // foreign-company row — the caller reached an address that is not this
            // customer's, and confirming its existence elsewhere leaks nothing useful.
            throw new ResourceNotFoundException(ENTITY, addressId);
        }
        return address;
    }

    /**
     * At most one default pickup and one default delivery address per customer — enforced
     * by clearing the flag on every other address rather than rejecting a second `true`,
     * the same "radio button" behaviour a booking screen expects.
     */
    private void clearOtherDefaults(UUID companyId, UUID customerId, UUID excludeId,
                                    boolean clearPickup, boolean clearDelivery) {
        for (CustomerAddress other : repository.findAllByCustomerIdWithinCompany(customerId, companyId)) {
            if (other.getId().equals(excludeId)) {
                continue;
            }
            boolean changed = false;
            if (clearPickup && other.isDefaultPickup()) {
                other.setDefaultPickup(false);
                changed = true;
            }
            if (clearDelivery && other.isDefaultDelivery()) {
                other.setDefaultDelivery(false);
                changed = true;
            }
            if (changed) {
                repository.save(other);
            }
        }
    }

    private void requireNotDuplicate(UUID companyId, UUID customerId, CustomerAddress candidate, UUID excludeId) {
        String key = candidate.duplicateKey();
        for (CustomerAddress existing : repository.findAllByCustomerIdWithinCompany(customerId, companyId)) {
            if (existing.getId().equals(excludeId)) {
                continue;
            }
            if (existing.duplicateKey().equals(key)) {
                throw new BusinessRuleException(
                        "This customer already has an address with the same lines and pincode.");
            }
        }
    }

    private void requireGeographyValid(UUID countryId, UUID stateId, UUID districtId,
                                       UUID cityId, UUID areaId, UUID pincodeId) {
        requireExists(countryId, countryService::getById, "country");
        requireExists(stateId, stateService::getById, "state");
        requireExists(districtId, districtService::getById, "district");
        requireExists(cityId, cityService::getById, "city");
        requireExists(areaId, areaService::getById, "area");
        requireExists(pincodeId, pincodeService::getById, "pincode");
    }

    private void requireExists(UUID id, java.util.function.Function<UUID, ?> lookup, String label) {
        if (id == null) {
            return;
        }
        try {
            lookup.apply(id);
        } catch (ResourceNotFoundException e) {
            throw new BusinessRuleException("No such " + label + ": " + id);
        }
    }

    private UUID requireCompany() {
        return CompanyContext.getCompanyId().orElseThrow(() -> new BusinessRuleException(
                "No company is bound to this request. Customer addresses belong to a company, so "
                        + "this operation must be performed by a user of that company."));
    }

    private void requireCurrentVersion(CustomerAddress address, Long expectedVersion) {
        if (expectedVersion == null) {
            return;
        }
        if (!Objects.equals(address.getVersion(), expectedVersion)) {
            throw new ObjectOptimisticLockingFailureException(CustomerAddress.class, address.getId());
        }
    }

    private String currentActor() {
        return SecurityUtils.getCurrentUserId().map(UUID::toString).orElse("system");
    }
}
