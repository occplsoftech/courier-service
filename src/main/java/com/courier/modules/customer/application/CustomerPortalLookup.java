package com.courier.modules.customer.application;

import com.courier.modules.customer.domain.Customer;
import com.courier.modules.customer.domain.CustomerAddress;
import com.courier.modules.customer.domain.CustomerAddressRepository;
import com.courier.modules.customer.domain.CustomerRepository;
import com.courier.modules.customer.domain.CustomerType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read access to one business customer for the customer portal ({@code customerportal}).
 *
 * <p>No {@code @PreAuthorize} here on purpose: the portal has already resolved the customer
 * from the signed-in account ({@code users.customer_id}) and is the only caller. Never expose
 * this through a controller of its own — {@link CustomerService}'s gates are for staff, and a
 * {@code CUSTOMER} token is kept away from them by the scope gate.
 */
@Service
@RequiredArgsConstructor
public class CustomerPortalLookup {

    private final CustomerRepository customerRepository;
    private final CustomerAddressRepository addressRepository;

    /** The customer when it is an ACTIVE business customer of that company. */
    @Transactional(readOnly = true)
    public Optional<Customer> findActiveBusinessCustomer(UUID companyId, UUID customerId) {
        return customerRepository.findByIdWithinCompany(customerId, companyId)
                .filter(c -> c.getCustomerType() == CustomerType.BUSINESS)
                .filter(Customer::isActive);
    }

    /** Whether the customer is priced from their own rate card (true) or the company's rates (false, also when unknown). */
    @Transactional(readOnly = true)
    public boolean usesBusinessRates(UUID companyId, UUID customerId) {
        return customerRepository.findByIdWithinCompany(customerId, companyId)
                .map(Customer::isUseBusinessRates).orElse(false);
    }

    /** The customer's ACTIVE addresses, oldest first. */
    @Transactional(readOnly = true)
    public List<CustomerAddress> activeAddresses(UUID companyId, UUID customerId) {
        return addressRepository.findAllByCustomerIdWithinCompany(customerId, companyId).stream()
                .filter(CustomerAddress::isActive)
                .toList();
    }
}
