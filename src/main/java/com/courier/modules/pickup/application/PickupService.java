package com.courier.modules.pickup.application;

import com.courier.modules.pickup.domain.PickupRequest;
import com.courier.modules.pickup.domain.PickupStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Pickup requests worked by Pickup-Delivery Partners. The company admin
 * create, edit, assign and cancel; the assigned partner (or an admin) accepts, rejects,
 * starts, completes or fails; a booked shipment is linked back with {@link #linkShipment}.
 * A partner-bound caller only ever sees requests assigned to their own branch.
 */
public interface PickupService {

    PickupRequest create(SaveCommand command);

    /** Only while REQUESTED or ASSIGNED. */
    PickupRequest update(UUID id, SaveCommand command);

    PickupRequest get(UUID id);

    Page<PickupRequest> search(PickupStatus status, UUID pdpBranchId, String search, Pageable pageable);

    /** Count per status for the caller's scope (all of the company's, or their own partner's). */
    Map<PickupStatus, Long> stats();

    /** Assigns (or reassigns) to a Pickup-Delivery Partner. */
    PickupRequest assign(UUID id, UUID pdpBranchId);

    /** The active Pickup-Delivery Partner mapped to this pickup's pincode (Pincode Branch Mapping), if any. */
    java.util.Optional<UUID> suggestPartner(UUID id);

    PickupRequest accept(UUID id);

    /** Back to the pool (REQUESTED); the reason is kept for staff. */
    PickupRequest reject(UUID id, String reason);

    PickupRequest start(UUID id);

    /** Goods collected: records what was actually picked up. */
    PickupRequest complete(UUID id, int actualParcels, BigDecimal actualWeightKg);

    /** The visit failed (customer unavailable, wrong address...). Terminal. */
    PickupRequest fail(UUID id, String reason);

    /** Links the shipment booked from a PICKED_UP request; the request becomes BOOKED. */
    PickupRequest linkShipment(UUID id, UUID shipmentId, String shipmentNo);

    PickupRequest cancel(UUID id, String reason);

    /**
     * Ties the shipment issued when a customer booked in the portal to its pickup request, without moving the request: it
     * is collected as usual and becomes BOOKED by itself when the parcel is picked up. No {@code @PreAuthorize}: the portal
     * is the only caller.
     */
    PickupRequest attachPortalShipment(UUID id, UUID shipmentId, String shipmentNo, UUID bookingBranchId);

    /**
     * A pickup booked by a business customer in the customer portal (OCCPL network service). No
     * {@code @PreAuthorize}: the portal has resolved the customer from the signed-in account and is the only
     * caller. Created REQUESTED like a staff-entered one; operations assign and book it as usual.
     */
    PickupRequest createFromCustomerPortal(SaveCommand command);

    /** A pickup by its number, for its own customer only (empty for anyone else's). Portal tracking. */
    java.util.Optional<PickupRequest> findByNumberForCustomer(String pickupNo, UUID customerId);

    record SaveCommand(UUID customerId, String contactName, String contactMobile, String addressLine,
                       String city, UUID districtId, String pincode, Integer expectedParcels,
                       BigDecimal expectedWeightKg, LocalDate preferredDate, String preferredSlot,
                       String remarks, UUID pdpBranchId, Long expectedVersion) {
    }
}
