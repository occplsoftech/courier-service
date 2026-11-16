package com.courier.modules.customer.application;

import com.courier.modules.customer.application.command.CreateCustomerCommand;
import com.courier.modules.customer.application.command.UpdateCustomerCommand;
import com.courier.modules.customer.domain.Customer;
import com.courier.modules.customer.domain.CustomerCriteria;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Use cases for customers — reusable master data, independent of Shipment Order.
 *
 * <p><b>Audiences:</b>
 * <ul>
 *   <li>{@code COMPANY_ADMIN} — full management.</li>
 *   <li>{@code BRANCH_MANAGER} / {@code OPERATOR} — create and update while booking a
 *       shipment at the counter; neither deletes, activates or deactivates a customer.</li>
 *   <li>Any other authenticated user of the company — read only.</li>
 * </ul>
 * A {@code SUPER_ADMIN} never reaches a customer at all — it is a company's own
 * operational record (see the invariant in {@code MEMORY/AI_CONTEXT.md}).
 */
public interface CustomerService {

    Customer create(CreateCustomerCommand command);

    /**
     * Reuses an existing customer matched by exact mobile number, or creates a bare-bones
     * {@code INDIVIDUAL} one from a booking-time name + mobile — so Shipment Booking's
     * sender/receiver are captured as reusable master data without the counter re-typing a
     * customer that already exists. Returns {@code null} without writing anything if
     * {@code mobile} is blank (booking still allows a contact-less party in some flows).
     */
    Customer findOrCreateForBooking(String fullName, String mobile);

    Customer update(UUID id, UpdateCustomerCommand command);

    /**
     * Sets a business customer's home branch (13.2); null fills it again from the branch serving the default pickup pincode (or clears it
     * when there is none). Company admin or branch manager.
     */
    Customer setHomeBranch(UUID id, UUID branchId);

    Customer getById(UUID id);

    Page<Customer> search(CustomerCriteria criteria, Pageable pageable);

    Customer activate(UUID id);

    Customer deactivate(UUID id);
}
