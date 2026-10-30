package com.courier.modules.shipment.application;

import com.courier.modules.ewaybill.application.EwayBillService;
import com.courier.modules.shipment.application.command.CreateShipmentCommand;
import com.courier.modules.shipment.application.command.UpdateShipmentCommand;
import com.courier.modules.shipment.domain.BranchCommissionSummary;
import com.courier.modules.shipment.domain.BranchPerformanceSummary;
import com.courier.modules.shipment.domain.VendorAuditRow;
import com.courier.modules.shipment.domain.DeliveryAssignment;
import com.courier.modules.shipment.domain.FlowType;
import com.courier.modules.shipment.domain.Shipment;
import com.courier.modules.shipment.domain.ShipmentAsset;
import com.courier.modules.shipment.domain.ShipmentCriteria;
import com.courier.modules.shipment.domain.ShipmentDocument;
import com.courier.modules.shipment.domain.ShipmentItem;
import com.courier.modules.shipment.domain.ShipmentStatus;
import com.courier.modules.shipment.domain.ShipmentStatusHistory;
import com.courier.modules.shipment.domain.ShipmentSummaryStats;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shipment Booking use cases — the core transaction of the platform.
 *
 * <p>This module does not re-decide anything Customer, Master, the Pricing Engine or
 * Branch Wallet already own: it validates the sender/receiver/addresses exist (Customer),
 * lets the Pricing Engine validate the route/serviceability/rate and price the booking in
 * one call, and — for a PREPAID booking — debits the branch wallet through the seam
 * {@code WalletService.debitForBooking} adds. See {@code MEMORY/modules/shipment-booking.md}.
 *
 * <p><b>Audiences:</b>
 * <ul>
 *   <li>{@code COMPANY_ADMIN}, {@code BRANCH_MANAGER}, {@code OPERATOR} — book, update
 *       (while still {@code BOOKED}), cancel and upload documents.</li>
 *   <li>Any other authenticated user of the company — read only.</li>
 * </ul>
 * A {@code SUPER_ADMIN} never reaches a shipment at all — it is a company's own
 * operational record, the same invariant every other business module in this project
 * asserts.
 */
public interface ShipmentService {

    /**
     * Books a shipment: validates sender/receiver/addresses, computes the weight from the
     * item grid, prices it through the Pricing Engine, generates the AWB and shipment
     * number, persists everything, writes the initial {@code BOOKED} status history entry
     * — and, for a PREPAID payment mode, checks the branch wallet can afford it before
     * committing, then debits it once the booking has committed.
     *
     * @throws com.courier.shared.exception.BusinessRuleException any validation failure —
     *         see the module doc for the full list, most of which are the Pricing
     *         Engine's own refusals surfacing unchanged
     */
    /**
     * The shipment a customer's portal booking turns into, issued at booking so the AWB and shipment number exist straight
     * away. No {@code @PreAuthorize}: the portal has resolved the customer and is the only caller. Never priced again.
     */
    Shipment createFromPortalBooking(com.courier.modules.shipment.application.command.PortalShipmentCommand command);

    Shipment create(CreateShipmentCommand command);

    /**
     * Full replacement of the editable fields, re-pricing the shipment. Only while the
     * shipment is still {@code BOOKED} — once it has moved past that, the record is no
     * longer a draft. Does not touch the wallet: see the module doc's honesty note on
     * re-pricing an already-debited PREPAID booking.
     */
    Shipment update(UUID id, UpdateShipmentCommand command);

    /**
     * {@code COMPANY_ADMIN} only: sets the Rate/KG of a still-{@code BOOKED} shipment and
     * recomputes freight, GST, commissions and net amount from it — nothing else about the
     * booking changes. May go below the slab rate (unlike booking, where it can only be raised).
     */
    Shipment updateRatePerKg(UUID id, Long expectedVersion, java.math.BigDecimal ratePerKg);

    /**
     * {@code COMPANY_ADMIN} only: switches a still-{@code BOOKED} shipment between a
     * collect-at-booking mode (PAID) and a collect-at-delivery one (TO_PAY). To PAID debits the
     * booking branch's wallet for the recalculated net amount (refused if the balance is short);
     * to TO_PAY credits back what was debited. Charges are recalculated and the shipment is
     * flagged so Track highlights it.
     */
    Shipment changePaymentMode(UUID id, Long expectedVersion, UUID paymentModeId);

    /** {@code COMPANY_ADMIN} only: the switch targets, their new amounts and the wallet balance. */
    com.courier.modules.shipment.api.dto.PaymentModeChangeResponse paymentModeChangeOptions(UUID id);

    /** Same recalculation as {@link #updateRatePerKg}, nothing saved — the "Calculate" button. */
    ShipmentCharges previewRatePerKg(UUID id, java.math.BigDecimal ratePerKg);

    Shipment getById(UUID id);

    Shipment getByTrackingNumber(String trackingNumber);

    /** Bulk Shipment Tracking report: whatever subset of {@code numbers} matches this
     *  company's shipments (by tracking or shipment number), in a single query — the
     *  caller is responsible for reconciling which of its input numbers came back. */
    List<Shipment> bulkTrack(Collection<String> numbers);

    /** POD/delivery data for a shipment — null once it's never been assigned for delivery,
     *  and unset (delivered fields null) until {@link #deliver} closes it out. */
    DeliveryAssignment getDeliveryAssignment(UUID shipmentId);

    Page<Shipment> search(ShipmentCriteria criteria, Pageable pageable);

    /** Unpaged aggregates for the same search — the Booking/Delivery Report summary row. */
    ShipmentSummaryStats summaryStats(ShipmentCriteria criteria);

    /**
     * Commission totals for the same search, grouped by booking branch — the Commission
     * Report's branch-wise summary table. One row per booking branch with at least one
     * matching, charged shipment; a branch with no charge record on any matching shipment
     * is simply absent, not a zero row.
     */
    List<BranchCommissionSummary> commissionSummary(ShipmentCriteria criteria);

    /**
     * Shipment volume and outcome totals for the same search, grouped by booking branch —
     * the Branch Performance Report's per-branch row. One row per booking branch with at
     * least one matching shipment.
     */
    List<BranchPerformanceSummary> branchPerformance(ShipmentCriteria criteria);

    /**
     * Vendor Audit Report: per-branch paid/to-pay volumes and amounts, booking and
     * delivery (DRS) commission, ODA charges, other charges and cancellations for the same
     * search — reconciliation figures for a franchise/vendor branch. See {@link
     * VendorAuditRow} for the booking-vs-delivery grouping split and its one known
     * limitation. One row per company branch appearing as either a booking or a delivery
     * branch in the search.
     */
    List<VendorAuditRow> vendorAudit(ShipmentCriteria criteria);

    /**
     * Net amount per shipment, for the list row — batch-fetched (one query for the whole
     * page, not one per row) since {@code netAmount} lives on the separate
     * {@code shipment_charges} row, not on {@code Shipment} itself. A shipment missing
     * from the returned map has no charge record.
     */
    Map<UUID, BigDecimal> netAmountsFor(Collection<UUID> shipmentIds);

    /**
     * The commission breakdown (V28) per shipment, for the list row and the Booking/Delivery
     * Report exports — batch-fetched the same way as {@link #netAmountsFor}, since it lives
     * on the same {@code shipment_charges} row. A shipment missing from the returned map has
     * no charge record.
     */
    Map<UUID, com.courier.modules.shipment.domain.ShipmentCharge> chargesFor(Collection<UUID> shipmentIds);

    /**
     * Delivered-at per shipment, for the Delivery Report's list row — batch-fetched the
     * same way as {@link #netAmountsFor}, since {@code deliveredAt} lives on the separate
     * {@code DeliveryAssignment} row. A shipment missing from the returned map has not
     * been delivered (or has no assignment at all).
     */
    Map<UUID, java.time.Instant> deliveredAtFor(Collection<UUID> shipmentIds);

    /** Latest IN_SCAN ("received") timestamp per shipment — the Bulk Shipment Tracking
     *  report's Received Date column. Same batch-by-id shape as {@link #deliveredAtFor}. */
    Map<UUID, java.time.Instant> receivedAtFor(Collection<UUID> shipmentIds);

    /**
     * Current E-Way Bill invoice number per shipment, for the THC's own INVOICE NO column —
     * batch-fetched the same way as {@link #netAmountsFor}. A shipment missing from the
     * returned map has no E-Way Bill, or one with no invoice number recorded on it.
     */
    Map<UUID, String> invoiceNumbersFor(Collection<UUID> shipmentIds);

    /**
     * Current E-Way Bill's own provider-issued number per shipment (distinct from
     * {@link #invoiceNumbersFor}) — the THC's own E-WAY BILL NO column and the DRS's
     * own row. A shipment missing from the returned map has no E-Way Bill, or one whose
     * Part-A has not (yet) succeeded.
     */
    Map<UUID, String> ewayBillNumbersFor(Collection<UUID> shipmentIds);

    /**
     * Every POD-kind asset (photo + signature, every historical upload, newest first) per
     * shipment, for the POD Review table's photo-preview column — batch-fetched the same way
     * as {@link #netAmountsFor}. A shipment missing from the returned map has no POD asset at
     * all (never captured, or delivered by a path that predates POD Auto Verification).
     */
    Map<UUID, List<com.courier.modules.shipment.domain.ShipmentAsset>> podAssetsFor(Collection<UUID> shipmentIds);

    /**
     * Every shipment scanned onto any of these manifests, within the caller's company —
     * the THC Report's shipment-count/weight/package-count aggregate, batch-fetched the
     * same "one query, not one per row" way as {@link #netAmountsFor}.
     */
    List<Shipment> findByManifestIds(Collection<UUID> manifestIds);

    /**
     * Cancels a shipment. Refused once it has left the branch —
     * {@link com.courier.modules.shipment.domain.ShipmentStatus#isCancellable()} is the
     * single source of truth. Does not reverse a PREPAID debit; see the module doc.
     */
    Shipment cancel(UUID id, String remarks);

    List<ShipmentItem> getItems(UUID shipmentId);

    ShipmentCharges getCharges(UUID shipmentId);

    List<ShipmentStatusHistory> getHistory(UUID shipmentId);

    List<ShipmentDocument> getDocuments(UUID shipmentId);

    ShipmentDocument addDocument(UUID shipmentId, AddDocumentCommand command);

    /** The shipment's current E-Way Bill, if it has one — see
     *  {@code EwayBillService.findLatestForShipment}. Empty when it never needed or never
     *  received one. */
    java.util.Optional<EwayBillService.EwayBillSnapshot> getEwayBill(UUID shipmentId);

    /** Every uploaded image of this shipment (booking photo, POD photo/signature), newest
     *  first — see {@link ShipmentAsset}. */
    List<ShipmentAsset> getAssets(UUID shipmentId);

    /**
     * Stores a shipment photo — taken during booking, before the shipment leaves the
     * branch — in the configured object store and records it as a {@code BOOKING} asset.
     * Unlike {@link #uploadPodFile}, this persists the reference itself rather than
     * handing the URL back for a later call to fold in: a booking image is not part of any
     * state-machine step, so there is nothing else to wait for.
     *
     * @throws com.courier.shared.exception.BusinessRuleException the content type is not
     *         an accepted image, or no storage backend is configured
     */
    String uploadShipmentImage(UUID shipmentId, UploadShipmentImageCommand command);

    record UploadShipmentImageCommand(byte[] content, String filename, String contentType) {
    }

    /**
     * Persists a POD asset (photo or signature) immediately — the seam POD Auto
     * Verification ({@code com.courier.modules.pod}) uses to record the captured document
     * before the delivery decision is made, unlike {@link #deliver}'s own asset recording
     * which only fires once delivery actually commits. Requires no particular shipment
     * status — verification can run against an {@code OUT_FOR_DELIVERY} shipment that has
     * not yet been decided.
     *
     * @param kind {@code PHOTO} or {@code SIGNATURE}
     * @return the persisted asset, whose id is the {@code podDocumentId} the caller stamps
     *         onto its own verification record
     */
    ShipmentAsset attachPodAsset(UUID shipmentId, String kind, String url);

    /**
     * Called by {@code com.courier.modules.pod} whenever a shipment's POD verification
     * reaches {@code PASS} (AI auto-pass, a manual reviewer's approve, or a company-direct
     * upload). Marks the shipment {@code podApproved} and, if it is already {@code
     * DELIVERED} and its commission hasn't credited yet, credits it now — the delivery-time
     * mirror of the check {@code deliver()} itself runs when POD was already approved
     * before delivery. Idempotent: a second call for an already-approved/already-credited
     * shipment is a no-op.
     */
    void markPodApproved(UUID shipmentId);

    /** @param documentType one of the five the brief names, as a string on the wire */
    record AddDocumentCommand(String documentType, String documentName, String documentUrl,
                              String remarks) {
    }

    /** The persisted charge row plus the resolved route/rate codes, for a display-ready
     *  response. {@code applicableChargeLines} is resolved live off the shipment's own
     *  stored booking/delivery branch, chargeable weight and freight — not persisted, the
     *  same "recompute at read time" treatment {@code matchedRouteCode}/{@code
     *  matchedRateCode} already get — so a charge/setting renamed or reconfigured after
     *  booking still shows correctly; {@code charge.applicableCharges} (the persisted sum)
     *  is untouched and remains the source of truth for the actual net amount. */
    record ShipmentCharges(com.courier.modules.shipment.domain.ShipmentCharge charge,
                           String matchedRouteCode, String matchedRateCode,
                           java.util.List<com.courier.modules.pricing.application.calculator.ApplicableChargesCalculator.Line>
                                   applicableChargeLines) {
    }

    // =================================================================== Shipment Movement (V19)
    //
    // Manifest orchestrates both "create a manifest" and "dispatch a manifest" — it is the
    // only module that needs to call the other (manifest -> shipment): a shipment already
    // knows nothing about manifests except its own manifestId, so there is no reverse
    // arrow, and no service-to-service Spring circular dependency between the two modules.

    /** Refuses unless every shipment belongs to {@code flowType} — a manifest only ever
     *  carries shipments of its own flow (STANDARD vs MSRTC never mix). */
    void requireFlowType(Collection<UUID> shipmentIds, FlowType flowType);

    /**
     * Called only by {@code ManifestServiceImpl.create} — attaches a {@code BOOKED}
     * shipment to a newly created manifest and transitions it to {@code MANIFEST_CREATED}.
     * Not exposed on its own REST endpoint.
     *
     * <p>Two ways a shipment can qualify: if it already carries a real next stop
     * ({@code nextLocationId} or, failing that, {@code deliveryBranchId} — a crossing hop,
     * or a legacy row from before Load Sheet deferred branch assignment), it must travel
     * exactly {@code expectedBookingBranchId} -&gt; {@code expectedDeliveryBranchId}, same
     * as ever. Otherwise (the normal case since Load Sheet — booked with no branch
     * resolved yet) it is matched by destination city instead: it must be sitting at
     * {@code expectedBookingBranchId} and its own {@code toCity} must match
     * {@code manifestDestinationCity} — {@code deliveryBranchId}/{@code nextLocationId} are
     * then set to {@code expectedDeliveryBranchId} for the first time.
     *
     * @throws com.courier.shared.exception.BusinessRuleException the shipment is not
     *         {@code BOOKED}/{@code READY_FOR_MANIFEST}, travels a different lane than the
     *         manifest, or (city path) isn't going to {@code manifestDestinationCity}
     */
    Shipment attachToManifest(UUID shipmentId, UUID manifestId, UUID expectedBookingBranchId,
                             UUID expectedDeliveryBranchId, String manifestDestinationCity);

    /** As above; {@code hubTransfer} true means {@code expectedDeliveryBranchId} is a hub
     *  stop only — a freshly BOOKED shipment gets its next stop set but no delivery branch. */
    Shipment attachToManifest(UUID shipmentId, UUID manifestId, UUID expectedBookingBranchId,
                             UUID expectedDeliveryBranchId, String manifestDestinationCity,
                             boolean hubTransfer);

    /**
     * Called only by {@code ManifestServiceImpl.removeShipment} — the inverse of
     * {@link #attachToManifest}. Clears {@code manifestId} and reverts the shipment to
     * {@code BOOKED} so it can be picked up by a future manifest.
     *
     * @throws com.courier.shared.exception.BusinessRuleException the shipment is not
     *         {@code MANIFEST_CREATED}, or not currently on {@code manifestId}
     */
    Shipment detachFromManifest(UUID shipmentId, UUID manifestId);

    /** Read-only — {@code ManifestServiceImpl.dispatch} uses this to enforce "at least one
     * shipment on the manifest" before it will dispatch. Loading Sheet folded into
     * {@code MANIFEST_CREATED} itself (V20, on direct request) — adding a shipment to a
     * manifest already is the "loading sheet created" milestone, so this simply reads every
     * {@code MANIFEST_CREATED} shipment on the manifest rather than a separate scanned
     * subset. */
    List<Shipment> findManifestCreatedShipments(UUID manifestId);

    /** Load Sheet's own "which city am I creating this for" picker — every distinct
     *  {@code toCity} among this branch's own BOOKED/READY_FOR_MANIFEST shipments that
     *  have no delivery branch resolved yet ({@code attachToManifest}'s city-matching
     *  path). A shipment already carrying a real next stop (crossing hop, or a legacy row)
     *  never appears here — it is attached the old, branch-matching way instead. */
    List<String> findEligibleDestinationCities(UUID currentLocationId);

    /** Hub Dashboard's "Today's Inbound Shipments" — count of arrivals (final in-scan or
     *  a crossing hop) recorded at {@code branchId} within {@code [from, to)}. */
    long countArrivalsAt(UUID branchId, Instant from, Instant to);

    /**
     * Called only by {@code ManifestServiceImpl.dispatch}, after the manifest itself has
     * already been validated and moved to {@code DISPATCHED} — moves every given shipment
     * (already established to be its manifest's {@code MANIFEST_CREATED} shipments) to
     * {@code DISPATCHED} too, recording the vehicle and branch on each history entry.
     */
    List<Shipment> transitionToDispatched(List<UUID> shipmentIds, UUID manifestId, UUID vehicleId,
                                          UUID bookingBranchId);

    /**
     * Called only by {@code ManifestServiceImpl.dispatch}, immediately after {@link
     * #transitionToDispatched}, and only for a {@code DeliveryMode.DIRECT_COMPANY_DELIVERY}
     * manifest — moves every given (already {@code DISPATCHED}) shipment straight to
     * {@code IN_SCAN}, since no delivery branch exists to receive it and the company
     * vehicle/driver just assigned carries it the rest of the way itself. Publishes no
     * wallet/commission event (unlike a real branch in-scan) — see
     * {@code ShipmentServiceImpl.deliver} for where a Direct Company Delivery shipment's
     * own booking-branch commission is credited instead, and its javadoc for which
     * delivery-branch-attributed money (TO_PAY debit, COD debit, DRS charge, delivery
     * weight commission) is deliberately skipped for these shipments.
     */
    List<Shipment> markPickedUpForDirectDelivery(List<UUID> shipmentIds);

    /**
     * Receives every tracking number at {@code receivingBranchId}. Each must resolve to
     * a {@code DISPATCHED} shipment whose {@code deliveryBranchId} equals
     * {@code receivingBranchId} — the brief's own "Receiving Branch must match Delivery
     * Branch" rule. Per-item result, same "bulk operation reports per-row" shape
     * {@code BranchService.assignUsers} already uses.
     *
     * @param manifestNumber          purely descriptive — only used in a shortage ticket's
     *                                subject/description, never validated or looked up
     * @param missingTrackingNumbers  the operator's own explicit "not physically on this
     *                                THC" checklist answer (In Scan's unchecked rows) —
     *                                when non-empty, auto-raises a Support ticket (category
     *                                "Shipment Issue", HIGH priority) against the company so
     *                                a short receipt is never silently lost. Null/empty
     *                                raises nothing, same behaviour as before this existed.
     * @param remarks                 optional — replaces the default status-history text
     *                                ("In scan"/"In scan at crossing hub…") on every
     *                                shipment this call actually receives.
     * @param photoUrl                optional — from {@link #uploadInScanPhoto}, attached as
     *                                a shared {@code ShipmentAssetType.IN_SCAN} photo to every
     *                                shipment this call actually receives.
     */
    BulkMovementResult inScan(UUID receivingBranchId, List<String> trackingNumbers, String manifestNumber,
                              List<String> missingTrackingNumbers, String remarks, String photoUrl);

    /**
     * Uploads one photo taken while receiving shipments at In Scan — not tied to a single
     * shipment up front (a batch scan may receive several), so it is stored under a
     * {@code companyId/in-scan/…} key rather than {@code companyId/shipmentId/…} the way
     * {@link #uploadShipmentImage} and {@link #uploadPodFile} are. The returned URL is
     * passed into {@link #inScan}'s {@code photoUrl} and attached to every shipment that
     * call actually receives.
     */
    String uploadInScanPhoto(UploadInScanPhotoCommand command);

    record UploadInScanPhotoCommand(byte[] content, String filename, String contentType) {
    }

    /**
     * Assigns a delivery user to every shipment id, each of which must be {@code IN_SCAN}.
     * Creates or replaces that shipment's {@code DeliveryAssignment} row and moves it to
     * {@code OUT_FOR_DELIVERY}. Per-item result, same shape as {@link #inScan}.
     * {@code vehicleId}/{@code fuelCost}/{@code deliveryCharge} are all optional — same
     * "trip expenses, no validation beyond what the controller already did" treatment
     * {@code ManifestService.dispatch} gives its own trip-expense fields — and are
     * stamped on every row this call touches, same as the generated {@code drsNumber}.
     */
    BulkMovementResult assignOutForDelivery(Collection<UUID> shipmentIds, UUID deliveryUserId,
                                             UUID vehicleId, java.math.BigDecimal fuelCost,
                                             java.math.BigDecimal deliveryCharge);

    /** Result of a successful delivery-OTP request — mirrors {@code ManifestService
     *  .DispatchOtpIssued}, kept as its own record since {@code modules.shipment} must not
     *  depend on {@code modules.manifest} (see {@code ManifestServiceImpl}'s class doc). */
    record DeliveryOtpIssued(String maskedMobile, int expiresInMinutes) {
    }

    /**
     * Generates a 4-digit OTP, stores its hash against a re-issuable per-company/delivery-user
     * challenge ({@code DeliveryDispatchOtp}), and sends it to that delivery user's mobile over
     * the company's configured SMS channel — same mechanism {@code ManifestService
     * .requestDispatchOtp} uses for the driver dispatch OTP. Refuses an unknown delivery user or
     * one with no mobile number on file.
     */
    DeliveryOtpIssued requestDeliveryDispatchOtp(UUID deliveryUserId);

    /**
     * Verifies {@code otp} against the delivery user's currently-issued code — a wrong code
     * counts against a small attempt limit before the code is invalidated outright and a fresh
     * one must be requested. Verification is currently optional: {@link #assignOutForDelivery}
     * does not require it to have succeeded first, same "optional for now" stance
     * {@code ManifestService.verifyDispatchOtp} takes for THC dispatch.
     */
    void verifyDeliveryDispatchOtp(UUID deliveryUserId, String otp);

    /**
     * Closes a delivery: captures receiver name (required), remarks, and the optional
     * OTP/signature/photo, moves the shipment to {@code DELIVERED}.
     *
     * @throws com.courier.shared.exception.BusinessRuleException the shipment is not
     *         {@code OUT_FOR_DELIVERY}
     */
    Shipment deliver(UUID shipmentId, DeliverCommand command);

    /**
     * Opt-in per company ({@code CompanySettings.manualStatusOverrideEnabled}) manual
     * correction: pushes one shipment to {@code command.targetStatus()} by hand, for a
     * {@code COMPANY_ADMIN}/{@code BRANCH_MANAGER}, from any current status — including one
     * a normal screen (THC/DRS/Deliver) would never let this shipment reach next.
     *
     * <p>When the jump <em>is</em> a legal {@link ShipmentStatus#canTransitionTo} edge that a
     * real service method already covers ({@code IN_SCAN->OUT_FOR_DELIVERY} via
     * {@link #assignOutForDelivery}, {@code OUT_FOR_DELIVERY->DELIVERED} via {@link #deliver},
     * {@code ->CANCELLED} via {@link #cancel}) and that method's own precondition is met, this
     * delegates to it — money/wallet/POD side effects fire exactly as they would from that
     * screen. {@code command} carries whichever of that method's own required fields apply
     * (e.g. {@code deliveryUserId} for an OUT_FOR_DELIVERY jump, {@code receiverName} for a
     * DELIVERED jump).
     *
     * <p>Otherwise — an arbitrary jump (e.g. straight from {@code BOOKED} to {@code DELIVERED}),
     * or a legal edge whose real method's precondition isn't met (no {@code DeliveryAssignment}
     * row yet, say) — this falls back to a raw status write with <b>no side effect at all</b>:
     * no commission credit, no wallet debit, no POD gate. {@link OverrideStatusResult#warning()}
     * is non-null exactly when this fallback ran, so the caller can surface it.
     *
     * @throws com.courier.shared.exception.BusinessRuleException the company has not enabled
     *         {@code manualStatusOverrideEnabled}, the shipment is already in a terminal status
     *         (DELIVERED/RETURNED/CANCELLED), or {@code reason} is blank
     */
    OverrideStatusResult overrideStatus(UUID shipmentId, OverrideStatusCommand command);

    /**
     * @param targetStatus    required
     * @param reason          required, kept on {@code shipment_status_history.remarks}
     * @param deliveryUserId  used only when {@code targetStatus == OUT_FOR_DELIVERY}
     * @param vehicleId       optional, forwarded to {@link #assignOutForDelivery} only
     * @param fuelCost        optional, forwarded to {@link #assignOutForDelivery} only
     * @param deliveryCharge  optional, forwarded to {@link #assignOutForDelivery} only
     * @param receiverName    used only when {@code targetStatus == DELIVERED}
     * @param otp             optional, forwarded to {@link #deliver} only
     * @param signatureUrl    optional, forwarded to {@link #deliver} only
     * @param photoUrl        optional, forwarded to {@link #deliver} only
     */
    record OverrideStatusCommand(ShipmentStatus targetStatus, String reason, UUID deliveryUserId,
                                 UUID vehicleId, BigDecimal fuelCost, BigDecimal deliveryCharge,
                                 String receiverName, String otp, String signatureUrl, String photoUrl) {
    }

    /**
     * @param viaRealMethod true when a real service method ran (side effects applied)
     * @param warning       non-null only for the raw fallback path — explains that no side
     *                      effect fired, for the caller to surface to the user
     */
    record OverrideStatusResult(Shipment shipment, boolean viaRealMethod, String warning) {
    }

    /**
     * Stores one POD file (photo or signature capture) in the configured object store
     * and returns its URL — the caller then passes that URL into {@link #deliver} as
     * {@code signatureUrl}/{@code photoUrl}, the same "URL is what deliver() takes"
     * contract as before, now backed by a real upload instead of a caller-supplied link.
     *
     * @throws com.courier.shared.exception.BusinessRuleException {@code kind} is not
     *         {@code PHOTO}/{@code SIGNATURE}, the content type is not an accepted
     *         image/video/PDF, or no storage backend is configured
     */
    String uploadPodFile(UUID shipmentId, UploadPodFileCommand command);

    /** Oldest first, one step per status the shipment has actually reached — see {@code getHistory}
     * for the raw append-only log this is built from. */
    List<TimelineStep> timeline(UUID shipmentId);

    /**
     * One row per DRS "run" — a delivery user + delivery branch + calendar day, grouped
     * from {@code DeliveryAssignment} rows (there is still no separate DRS/batch table; a
     * run's identity is still this grouping, not the number — see {@code
     * MEMORY/modules/shipment-movement.md}). Since V31, each {@link #assignOutForDelivery}
     * call stamps a unique {@code drsNumber} ({@code "DRS" + 6-digit serial}) on every row
     * it touches; the summary surfaces the most recent one in the group, null for runs made
     * entirely of rows that predate that column. {@code from}/{@code to} default to the
     * trailing 30 days when null. {@code deliveryBranchId} restricts to one branch's runs
     * when given (a {@code BRANCH_MANAGER}'s own branch) — filtered server-side so a caller
     * scoped to one branch never receives another branch's rows over the wire.
     */
    List<DrsSummary> listDrs(java.time.LocalDate from, java.time.LocalDate to, UUID deliveryBranchId);

    /** Every shipment on one DRS run — same grouping key {@link #listDrs} used to build the row. */
    DrsDetail getDrsDetail(UUID deliveryUserId, UUID deliveryBranchId, java.time.LocalDate runDate);

    /** {@code drsNumber} is the most recently generated number among the run's assignments
     *  (null for runs made entirely of pre-V31 rows) — the grouping key itself is still
     *  delivery user + delivery branch + calendar day, unchanged. */
    record DrsSummary(UUID deliveryUserId, UUID deliveryBranchId, java.time.LocalDate runDate,
                      String drsNumber, int shipmentCount, int deliveredCount, int pendingCount) {
    }

    record DrsShipmentRow(UUID shipmentId, String shipmentNumber, String trackingNumber,
                          String receiverName, String receiverAddress, String receiverContact, UUID paymentModeId,
                          BigDecimal netAmount, com.courier.modules.shipment.domain.ShipmentStatus status,
                          java.time.Instant deliveredAt, String ewayBillNumber,
                          String fromCity, String toCity) {
    }

    record DrsDetail(UUID deliveryUserId, UUID deliveryBranchId, java.time.LocalDate runDate,
                     String drsNumber, List<DrsShipmentRow> shipments) {
    }

    record DeliverCommand(String receiverName, String remarks, String otp, String signatureUrl,
                          String photoUrl) {
    }

    /**
     * @param kind {@code PHOTO} or {@code SIGNATURE} — controls only the storage key,
     *             both kinds accept the same content types
     */
    record UploadPodFileCommand(byte[] content, String filename, String contentType, String kind) {
    }

    /** One outcome per input item — {@code message} is null on success. */
    record MovementOutcome(String reference, boolean success, String message) {
    }

    /** {@code drsNumber} is only ever set by {@link #assignOutForDelivery} — null for
     *  every other bulk movement (e.g. {@link #inScan}). {@code shortageTicketNumber} is
     *  only ever set by {@link #inScan}, and only when it was called with a non-empty
     *  {@code missingTrackingNumbers} — null otherwise. */
    record BulkMovementResult(List<MovementOutcome> results, String drsNumber, String shortageTicketNumber) {
        public BulkMovementResult(List<MovementOutcome> results) {
            this(results, null, null);
        }

        public BulkMovementResult(List<MovementOutcome> results, String drsNumber) {
            this(results, drsNumber, null);
        }

        public long successCount() {
            return results.stream().filter(MovementOutcome::success).count();
        }

        public long failureCount() {
            return results.size() - successCount();
        }
    }

    record TimelineStep(com.courier.modules.shipment.domain.ShipmentStatus status, String label,
                        java.time.Instant changedAt, UUID changedBy, boolean completed) {
    }
}
