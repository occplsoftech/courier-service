package com.courier.modules.customer.application.event;

import com.courier.modules.customer.domain.CustomerType;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain events published by Customer Management. Sealed, consumed
 * {@code @TransactionalEventListener(AFTER_COMMIT)} — nothing reacts to a customer whose
 * transaction rolled back. In-process, like the branch events.
 *
 * <p>Published only for a customer someone <em>created on purpose</em>
 * ({@code CustomerService.create}). Not for the customers {@code findOrCreateForBooking}
 * makes on the fly for every sender and receiver of a booking: those are contact records,
 * not clients, and reacting to them (for example by opening a wallet) would fill the system
 * with accounts nobody uses.
 */
public sealed interface CustomerEvent {

    UUID customerId();

    UUID companyId();

    Instant occurredAt();

    record CustomerCreated(UUID customerId, UUID companyId, String customerCode, CustomerType customerType,
                           Instant occurredAt) implements CustomerEvent {
    }
}
