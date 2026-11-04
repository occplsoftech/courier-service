package com.courier.modules.shipment.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The full lifecycle a shipment moves through. Transitions live here, in one place, rather
 * than as scattered {@code if} statements in a service — the same discipline the project's
 * own planning note for this module called for.
 *
 * <p><b>Shipment Booking itself only ever writes {@link #BOOKED} (on create) and
 * {@link #CANCELLED} (on cancel).</b> Shipment Movement (V19) writes every step from
 * {@link #MANIFEST_CREATED} through {@link #DELIVERED} — {@link #READY_FOR_MANIFEST} and
 * {@link #RETURNED} stay declared but unwritten by either module, the same "declared now,
 * used later" treatment the rest of this graph originally got: a pre-manifest "ready"
 * queue and a return flow are plausible future modules, not this one.
 *
 * <p>Renamed in V19 to match the Shipment Movement brief's own vocabulary exactly:
 * {@code MANIFESTED} -> {@link #MANIFEST_CREATED}, {@code RECEIVED} -> {@link #IN_SCAN},
 * {@code RETURN_INITIATED} folded into a direct edge to {@link #RETURNED}. A bare rename
 * was safe because nothing had ever written the old values (Shipment Booking's own
 * verification: "nothing yet transitions a shipment past BOOKED") — see V19's migration
 * comment.
 *
 * <p><b>V20, on direct request:</b> the standalone {@code OUT_SCAN} state (and the
 * separate "scan each shipment onto the manifest" action that wrote it) is gone —
 * {@link #MANIFEST_CREATED} now doubles as "loading sheet created" (see {@code ShipmentStatusBadge}'s
 * display label), and Dispatch's precondition moved from "at least one OUT_SCAN
 * shipment" to "at least one MANIFEST_CREATED shipment". The user's own words: "manifest
 * created as outscan created" — one milestone, not two. V20's migration folds any
 * `OUT_SCAN` rows (real ones exist from live verification of the two-step version) back
 * into {@code MANIFEST_CREATED}, the same fold-back-on-rename pattern V19 set.
 *
 * <p>{@link #MANIFEST_CREATED} -&gt; {@link #BOOKED} was originally the one backward edge in
 * this graph: removing a shipment from a still-{@code CREATED} manifest (see
 * {@code ManifestService.removeShipment}) reverts it to {@code BOOKED} so a future
 * manifest can pick it up, rather than leaving it stranded off every worklist.
 * {@link #MANIFEST_CREATED} -&gt; {@link #READY_FOR_MANIFEST} is the crossing-aware sibling
 * of that same edge: removing a shipment that has already advanced past its first crossing
 * hop reverts it to {@code READY_FOR_MANIFEST} instead, since {@code BOOKED} would wrongly
 * make it eligible for a fresh manifest starting from its original booking branch again.
 *
 * <p><b>Crossing (V37/V38):</b> {@link #DISPATCHED} -&gt; {@link #READY_FOR_MANIFEST} is a
 * second exit from {@code DISPATCHED}, alongside the existing {@link #IN_SCAN} one — taken
 * only when the branch performing the in-scan is an intermediate crossing hub, not the
 * shipment's own {@code deliveryBranchId} (see {@code ShipmentServiceImpl.scanOneIn}).
 * That is what finally gives {@link #READY_FOR_MANIFEST} a writer: a shipment received at
 * a hub is ready for its next leg's manifest, exactly what the status name already said
 * before anything wrote it.
 *
 * <p>{@link #READY_FOR_MANIFEST} -&gt; {@link #OUT_FOR_DELIVERY} is the hub's own DRS: a
 * shipment received at a hub with no delivery branch assigned may be delivered straight
 * from there instead of going on another leg (see {@code assignOneOutForDelivery}).
 */
public enum ShipmentStatus {

    BOOKED,
    READY_FOR_MANIFEST,
    MANIFEST_CREATED,
    DISPATCHED,
    IN_SCAN,
    OUT_FOR_DELIVERY,
    DELIVERED,
    RETURNED,
    CANCELLED,
    /** Handed to a courier partner from the hub; the courier delivers it and its own tracking decides what follows. */
    FORWARDED;

    private static final Map<ShipmentStatus, Set<ShipmentStatus>> TRANSITIONS = Map.ofEntries(
            Map.entry(BOOKED, EnumSet.of(READY_FOR_MANIFEST, MANIFEST_CREATED, CANCELLED, FORWARDED)),
            Map.entry(READY_FOR_MANIFEST, EnumSet.of(MANIFEST_CREATED, OUT_FOR_DELIVERY, CANCELLED, FORWARDED)),
            Map.entry(MANIFEST_CREATED, EnumSet.of(DISPATCHED, CANCELLED, BOOKED, READY_FOR_MANIFEST)),
            Map.entry(DISPATCHED, EnumSet.of(IN_SCAN, READY_FOR_MANIFEST)),
            Map.entry(IN_SCAN, EnumSet.of(OUT_FOR_DELIVERY, RETURNED, FORWARDED)),
            Map.entry(OUT_FOR_DELIVERY, EnumSet.of(DELIVERED, RETURNED)),
            Map.entry(DELIVERED, EnumSet.noneOf(ShipmentStatus.class)),
            Map.entry(RETURNED, EnumSet.noneOf(ShipmentStatus.class)),
            Map.entry(CANCELLED, EnumSet.noneOf(ShipmentStatus.class)),
            // The courier delivers it (or returns it); our own manifest, scan and delivery steps no longer apply.
            Map.entry(FORWARDED, EnumSet.of(DELIVERED, RETURNED)));

    /**
     * The states a booking may still be cancelled from — before it has left the branch.
     * {@link #DISPATCHED} onward is not — "has left the branch" is a physical fact, not
     * a status label.
     */
    private static final Set<ShipmentStatus> CANCELLABLE =
            EnumSet.of(BOOKED, READY_FOR_MANIFEST, MANIFEST_CREATED);

    public boolean canTransitionTo(ShipmentStatus next) {
        return next != null && TRANSITIONS.get(this).contains(next);
    }

    public boolean isTerminal() {
        return TRANSITIONS.get(this).isEmpty();
    }

    /**
     * Whether {@link com.courier.shared.exception.BusinessRuleException} should refuse a
     * cancel from this state — once a shipment has left the branch (DISPATCHED onward),
     * cancelling it is a return, not an undo.
     */
    public boolean isCancellable() {
        return CANCELLABLE.contains(this);
    }
}
