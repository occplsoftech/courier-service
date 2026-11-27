package com.courier.modules.manifest.application;

import com.courier.modules.manifest.application.command.CreateManifestCommand;
import com.courier.modules.manifest.domain.Manifest;
import com.courier.modules.manifest.domain.ManifestCriteria;
import com.courier.modules.manifest.domain.ManifestSummaryStats;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The minimal Manifest module Shipment Movement needs underneath it — see
 * {@code Manifest}'s own class-level note and V19's migration comment for why this
 * exists at all. {@code COMPANY_ADMIN}/{@code BRANCH_MANAGER}/{@code OPERATOR} write,
 * any authenticated company user reads, matching Shipment Booking's own tiers exactly
 * (the same desks that book also manifest).
 */
public interface ManifestService {

    /**
     * Every id in {@code command.shipmentIds()} must exist, be {@code BOOKED}, and carry
     * exactly {@code command.bookingBranchId()}/{@code command.deliveryBranchId()} — a
     * manifest can never group shipments travelling different lanes. Transitions each to
     * {@code MANIFEST_CREATED} and stamps its {@code manifestId}.
     */
    Manifest create(CreateManifestCommand command);

    Manifest getById(UUID id);

    Page<Manifest> search(ManifestCriteria criteria, Pageable pageable);

    /**
     * Unpaged shipment-count/weight/package totals over every manifest matching
     * {@code criteria} — the THC Report summary row, same filters as {@link #search}
     * minus paging.
     */
    ManifestSummaryStats summaryStats(ManifestCriteria criteria);

    /**
     * Assigns the vehicle and driver and moves the manifest to {@code DISPATCHED}, after
     * checking (via {@code ShipmentService.findManifestCreatedShipments}) that it has at
     * least one shipment, then moves every one of them to {@code DISPATCHED} too (via
     * {@code ShipmentService.transitionToDispatched}) in the same transaction. Refuses a
     * manifest already dispatched, an inactive/foreign vehicle, or a driver id that is
     * not a real user of this company. {@code departureTime} is operator-entered and
     * optional — null falls back to the dispatch moment itself. {@code fuelCost}/
     * {@code driverAdvance}/{@code tollAmount}/{@code otherAmount} are all optional trip
     * expenses, stored as entered with no validation beyond non-negative.
     */
    Manifest dispatch(UUID id, UUID vehicleId, UUID driverUserId, Instant departureTime,
            BigDecimal fuelCost, BigDecimal driverAdvance, BigDecimal tollAmount, BigDecimal otherAmount);

    /** Dispatch with the outscan details: co-loader, forwarding number, bags. Vehicle and driver are optional then. */
    Manifest dispatch(UUID id, UUID vehicleId, UUID driverUserId, Instant departureTime, BigDecimal fuelCost, BigDecimal driverAdvance,
                      BigDecimal tollAmount, BigDecimal otherAmount, UUID coLoaderId, String forwardingNumber, Integer bagsCount);

    /** As above, plus "With Forwarder No.": shipment id → the co-loader's own number for that AWB, saved on each shipment.
     *  Every key must be a shipment on this manifest. */
    Manifest dispatch(UUID id, UUID vehicleId, UUID driverUserId, Instant departureTime, BigDecimal fuelCost, BigDecimal driverAdvance,
                      BigDecimal tollAmount, BigDecimal otherAmount, UUID coLoaderId, String forwardingNumber, Integer bagsCount,
                      java.util.Map<UUID, String> forwarderNumbers);

    /**
     * Removes one shipment from a still-{@code CREATED} manifest, reverting it to
     * {@code BOOKED} (via {@code ShipmentService.detachFromManifest}) so it can be picked
     * up by a future manifest. Refuses a manifest already dispatched, or a shipment that
     * isn't actually on this manifest.
     */
    void removeShipment(UUID manifestId, UUID shipmentId);

    /** Result of a successful OTP request — never the raw code itself, only enough for the
     *  UI to tell the operator where it went and how long it lasts. */
    record DispatchOtpIssued(String maskedMobile, int expiresInMinutes) {
    }

    /**
     * Generates a 4-digit OTP, stores its hash against the manifest for {@code
     * driverUserId}, and sends it to that driver's mobile over the company's configured
     * SMS channel (Communication Center) — falling back to the same log-only sender every
     * other unwired notification in this project uses when no real gateway is configured.
     * Refuses a manifest already dispatched, an unknown driver, or a driver with no mobile
     * number on file.
     */
    DispatchOtpIssued requestDispatchOtp(UUID manifestId, UUID driverUserId);

    /**
     * Verifies {@code otp} against the manifest's currently-issued code for {@code
     * driverUserId} — a wrong code counts against a small attempt limit before the code is
     * invalidated outright and a fresh one must be requested. Verification is currently
     * optional: {@link #dispatch} does not require it to have succeeded first.
     */
    void verifyDispatchOtp(UUID manifestId, UUID driverUserId, String otp);
}
