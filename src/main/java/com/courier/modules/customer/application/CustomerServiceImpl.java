package com.courier.modules.customer.application;

import com.courier.modules.customer.application.command.CreateCustomerCommand;
import com.courier.modules.customer.application.command.UpdateCustomerCommand;
import com.courier.modules.customer.domain.Customer;
import com.courier.modules.customer.domain.CustomerCodeGenerator;
import com.courier.modules.customer.domain.CustomerCriteria;
import com.courier.modules.customer.domain.CustomerRepository;
import com.courier.modules.customer.domain.CustomerSpecifications;
import com.courier.modules.customer.domain.CustomerStatus;
import com.courier.modules.customer.domain.CustomerType;
import com.courier.shared.audit.application.AuditService;
import com.courier.shared.audit.domain.AuditAction;
import com.courier.shared.company.CompanyContext;
import com.courier.shared.exception.BusinessRuleException;
import com.courier.shared.exception.DuplicateResourceException;
import com.courier.shared.exception.ResourceNotFoundException;
import com.courier.shared.security.Roles;
import com.courier.shared.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Customer use cases. Per-method {@code @PreAuthorize} gates by JWT role tier — see
 * {@link CustomerService} for the audiences. Company isolation is the project's two
 * layers: the Hibernate filter, plus {@code findByIdWithinCompany} on every single-row
 * load, so a foreign customer id returns 404.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerServiceImpl implements CustomerService {

    private static final String ENTITY = "Customer";
    private static final String COMPANY_ADMIN_ONLY = "hasRole('" + Roles.COMPANY_ADMIN + "')";
    private static final String WRITERS = "hasAnyRole('" + Roles.COMPANY_ADMIN + "', '"
            + Roles.BRANCH_MANAGER + "', '" + Roles.OPERATOR + "')";
    private static final String LIFECYCLE = "hasAnyRole('" + Roles.COMPANY_ADMIN + "', '"
            + Roles.BRANCH_MANAGER + "')";
    private static final String READERS = "isAuthenticated()";

    private static final int CODE_ATTEMPTS = 5;

    private final CustomerRepository repository;
    private final AuditService auditService;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    // ------------------------------------------------------------------- create

    @Override
    @Transactional
    @PreAuthorize(WRITERS)
    public Customer create(CreateCustomerCommand command) {
        UUID companyId = requireCompany();

        String code = Customer.normaliseCode(command.customerCode());
        if (code == null) {
            code = nextCustomerCode(companyId);
        } else {
            requireCodeAvailable(companyId, code, null);
        }
        requireMobileAvailable(companyId, trimOrNull(command.mobile()), null);

        Customer customer = Customer.builder()
                .customerCode(code)
                .customerType(command.customerType())
                .companyName(command.companyName())
                .firstName(command.firstName())
                .middleName(command.middleName())
                .lastName(command.lastName())
                .mobile(command.mobile())
                .alternateMobile(command.alternateMobile())
                .email(command.email())
                .gstNumber(command.gstNumber())
                .panNumber(command.panNumber())
                .website(command.website())
                .whatsappEnabled(command.whatsappEnabled() == null || command.whatsappEnabled())
                .smsEnabled(command.smsEnabled() == null || command.smsEnabled())
                .emailEnabled(command.emailEnabled() == null || command.emailEnabled())
                .contactPerson(command.contactPerson())
                .defaultPaymentMode(command.defaultPaymentMode())
                .billingFrequency(command.billingFrequency())
                .billingAddressLine1(command.billingAddressLine1())
                .billingAddressLine2(command.billingAddressLine2())
                .billingCity(command.billingCity())
                .billingState(command.billingState())
                .billingPincode(command.billingPincode())
                .status(CustomerStatus.ACTIVE)
                .build();

        customer.applyInvariants();
        Customer saved = repository.save(customer);

        log.info("Customer {} ({}) created in company {} by {}",
                saved.getCustomerCode(), saved.getId(), companyId, currentActor());
        auditService.record(AuditAction.CUSTOMER_CREATED, ENTITY, saved.getId(),
                Map.of("customerCode", saved.getCustomerCode(), "customerType", saved.getCustomerType().name()));
        eventPublisher.publishEvent(new com.courier.modules.customer.application.event.CustomerEvent.CustomerCreated(
                saved.getId(), companyId, saved.getCustomerCode(), saved.getCustomerType(), java.time.Instant.now()));

        return saved;
    }

    @Override
    @Transactional
    @PreAuthorize(WRITERS)
    public Customer findOrCreateForBooking(String fullName, String mobile) {
        String trimmedMobile = trimOrNull(mobile);
        if (trimmedMobile == null || trimmedMobile.isBlank()) {
            return null;
        }
        UUID companyId = requireCompany();
        return repository.findByCompanyIdAndMobile(companyId, trimmedMobile)
                .orElseGet(() -> create(quickCreateCommand(fullName, trimmedMobile)));
    }

    /** Splits a single free-text booking name into first/last on the first space — the
     *  best a counter-typed "Ramesh Kadam" string offers; {@code lastName} stays blank
     *  (the column is {@code NOT NULL}, not "required non-empty") rather than guessed. */
    private CreateCustomerCommand quickCreateCommand(String fullName, String mobile) {
        String name = trimOrNull(fullName);
        String firstName = name == null ? mobile : name;
        String lastName = "";
        if (name != null) {
            int space = name.indexOf(' ');
            if (space > 0) {
                firstName = name.substring(0, space);
                lastName = name.substring(space + 1).trim();
            }
        }
        return new CreateCustomerCommand(null, CustomerType.INDIVIDUAL, null,
                firstName, null, lastName, mobile, null, null, null, null, null,
                null, null, null,
                null, null, null, null, null, null, null, null);
    }

    /** Retries on collision, same shape as {@code WalletServiceImpl.nextWalletNumber()}. */
    private String nextCustomerCode(UUID companyId) {
        for (int attempt = 0; attempt < CODE_ATTEMPTS; attempt++) {
            String candidate = CustomerCodeGenerator.generate();
            if (!repository.isCodeTaken(companyId, candidate, null)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Could not generate a unique customer code in " + CODE_ATTEMPTS + " attempts");
    }

    // ------------------------------------------------------------------- update

    @Override
    @Transactional
    @PreAuthorize(WRITERS)
    public Customer update(UUID id, UpdateCustomerCommand command) {
        UUID companyId = requireCompany();
        Customer customer = loadOrThrow(id, companyId);
        requireCurrentVersion(customer, command.expectedVersion());

        String mobile = trimOrNull(command.mobile());
        requireMobileAvailable(companyId, mobile, id);

        Map<String, Object> before = snapshot(customer);

        customer.setCustomerType(command.customerType());
        customer.setCompanyName(command.companyName());
        customer.setFirstName(command.firstName());
        customer.setMiddleName(command.middleName());
        customer.setLastName(command.lastName());
        customer.setMobile(command.mobile());
        customer.setAlternateMobile(command.alternateMobile());
        customer.setEmail(command.email());
        customer.setGstNumber(command.gstNumber());
        customer.setPanNumber(command.panNumber());
        customer.setWebsite(command.website());
        customer.setWhatsappEnabled(command.whatsappEnabled());
        customer.setSmsEnabled(command.smsEnabled());
        customer.setEmailEnabled(command.emailEnabled());
        customer.setContactPerson(command.contactPerson());
        customer.setDefaultPaymentMode(command.defaultPaymentMode());
        customer.setBillingFrequency(command.billingFrequency());
        customer.setBillingAddressLine1(command.billingAddressLine1());
        customer.setBillingAddressLine2(command.billingAddressLine2());
        customer.setBillingCity(command.billingCity());
        customer.setBillingState(command.billingState());
        customer.setBillingPincode(command.billingPincode());

        customer.applyInvariants();
        Customer saved = repository.save(customer);

        Map<String, Object> changes = changeDetails(before, snapshot(saved));
        log.info("Customer {} updated in company {} by {} ({} field(s))",
                saved.getCustomerCode(), companyId, currentActor(), changes.size());
        auditService.record(AuditAction.CUSTOMER_UPDATED, ENTITY, saved.getId(), changes);

        return saved;
    }

    // -------------------------------------------------------------------- reads

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READERS)
    public Customer getById(UUID id) {
        return loadOrThrow(id, requireCompany());
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize(READERS)
    public Page<Customer> search(CustomerCriteria criteria, Pageable pageable) {
        CustomerCriteria safe = criteria == null ? CustomerCriteria.none() : criteria;
        return repository.findAll(CustomerSpecifications.matching(safe), pageable);
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    @Transactional
    @PreAuthorize(LIFECYCLE)
    public Customer activate(UUID id) {
        UUID companyId = requireCompany();
        Customer customer = loadOrThrow(id, companyId);
        if (customer.isActive()) {
            return customer;
        }
        customer.activate();
        Customer saved = repository.save(customer);
        auditService.record(AuditAction.CUSTOMER_ACTIVATED, ENTITY, saved.getId(),
                Map.of("customerCode", saved.getCustomerCode()));
        return saved;
    }

    @Override
    @Transactional
    @PreAuthorize(LIFECYCLE)
    public Customer deactivate(UUID id) {
        UUID companyId = requireCompany();
        Customer customer = loadOrThrow(id, companyId);
        if (!customer.isActive()) {
            return customer;
        }
        customer.deactivate();
        Customer saved = repository.save(customer);
        auditService.record(AuditAction.CUSTOMER_DEACTIVATED, ENTITY, saved.getId(),
                Map.of("customerCode", saved.getCustomerCode()));
        return saved;
    }

    // -------------------------------------------------------------------- helpers

    Customer loadOrThrow(UUID id, UUID companyId) {
        return repository.findByIdWithinCompany(id, companyId)
                .orElseThrow(() -> new ResourceNotFoundException(ENTITY, id));
    }

    private void requireCodeAvailable(UUID companyId, String code, UUID excludeId) {
        if (repository.isCodeTaken(companyId, code, excludeId)) {
            throw new DuplicateResourceException(ENTITY, "customerCode", code);
        }
    }

    private void requireMobileAvailable(UUID companyId, String mobile, UUID excludeId) {
        if (mobile != null && repository.isMobileTaken(companyId, mobile, excludeId)) {
            throw new DuplicateResourceException(ENTITY, "mobile", mobile);
        }
    }

    private UUID requireCompany() {
        return CompanyContext.getCompanyId().orElseThrow(() -> new BusinessRuleException(
                "No company is bound to this request. Customers belong to a company, so this "
                        + "operation must be performed by a user of that company."));
    }

    private void requireCurrentVersion(Customer customer, Long expectedVersion) {
        if (expectedVersion == null) {
            return;
        }
        if (!Objects.equals(customer.getVersion(), expectedVersion)) {
            throw new ObjectOptimisticLockingFailureException(Customer.class, customer.getId());
        }
    }

    private static String trimOrNull(String value) {
        return value == null ? null : value.trim();
    }

    private String currentActor() {
        return SecurityUtils.getCurrentUserId().map(UUID::toString).orElse("system");
    }

    private Map<String, Object> snapshot(Customer c) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("customerType", c.getCustomerType() == null ? null : c.getCustomerType().name());
        v.put("companyName", c.getCompanyName());
        v.put("firstName", c.getFirstName());
        v.put("middleName", c.getMiddleName());
        v.put("lastName", c.getLastName());
        v.put("mobile", c.getMobile());
        v.put("alternateMobile", c.getAlternateMobile());
        v.put("email", c.getEmail());
        v.put("gstNumber", c.getGstNumber());
        v.put("panNumber", c.getPanNumber());
        v.put("website", c.getWebsite());
        v.put("contactPerson", c.getContactPerson());
        v.put("defaultPaymentMode", c.getDefaultPaymentMode() == null ? null : c.getDefaultPaymentMode().name());
        v.put("billingFrequency", c.getBillingFrequency() == null ? null : c.getBillingFrequency().name());
        v.put("billingAddress", String.join(", ",
                java.util.stream.Stream.of(c.getBillingAddressLine1(), c.getBillingAddressLine2(), c.getBillingCity(),
                        c.getBillingState(), c.getBillingPincode()).filter(java.util.Objects::nonNull).toList()));
        return v;
    }

    private Map<String, Object> changeDetails(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> changes = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : after.entrySet()) {
            Object previous = before.get(entry.getKey());
            if (!Objects.equals(previous, entry.getValue())) {
                changes.put(entry.getKey(), entry.getValue());
            }
        }
        return changes;
    }
}
