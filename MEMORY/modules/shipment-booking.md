# Shipment Booking

**Insurance Applicable (2026-09-08, `V65`):** optional checkbox at booking time —
`Shipment.insuranceApplicable`. When checked, `ShipmentServiceImpl.copyCharge` sets
`ShipmentCharge.insuranceCharge` to `freight * 2%` instead of the Pricing Engine's own
rate-driven `priced.insuranceCharge()` — same delta-and-GST treatment `odaCharge` gets
(unlike `appointmentDeliveryCharge`, which is deliberately untaxed, insurance stays
taxed since the engine's own figure always was). No new `shipment_charges` column —
`insurance_charge` already existed. Applied to `courier_db` and verified live via curl
(throwaway `:8082`, real `:8080`/`:4200` untouched): same lane, freight `15.00` —
unchecked gives `insuranceCharge: 0`/`netAmount: 17.70`, checked gives `insuranceCharge:
0.30` (2% of freight) and `netAmount: 18.054` (+0.30 insurance +0.054 GST on that
delta). No frontend click-through this session, curl only.

**Appointment Delivery (2026-09-08, `V60`):** optional checkbox at booking time —
`Shipment.appointmentDelivery`/`appointmentDate`/`appointmentTimeSlot` (free text, e.g.
"1:00-2:00"), plus an optional `ShipmentCharge.appointmentDeliveryCharge` that is
deliberately never taxed with GST (direct user request), added straight into
`netAmount` alongside `gstAmount` rather than folded into it — unlike `otherCharges`.
`applyInvariants` requires both date and slot once checked, nulls them otherwise. See
`CHANGELOG.md` for the full plumbing; not yet applied to the dev DB.

**Status:** DONE (v0.16.0, 2026-07-30). New package `com.courier.modules.shipment`,
migration `V17`. Replaces the earlier, never-built design sketch in this file's
predecessor — the old `modules/shipment.md` doc-only note (consignor/consignee,
`deliveryType`, `V5__shipment.sql`) is gone; nothing in it was ever implemented.

**Correction (0.18.1, 2026-08-12):** the *Shape* section below (`senderCustomerId`,
`receiverCustomerId`, `senderAddressId`, `receiverAddressId`) and *Business rules* step 1-2
(load the `Customer`/address by id) describe a design that was **never actually built** —
what shipped, and is still true today, is plain-text `senderName`/`senderAddress`/
`senderContact` (and the receiver equivalents) with no FK at all, exactly as
`customer.md`'s independence rule says it must be. This doc was never corrected after the
simpler shape shipped. As of 0.18.1, booking does call into the Customer module —
`CustomerService.findOrCreateForBooking` — but only to write a reusable `Customer` row for
future search suggestions, never to read/validate one at booking time and never producing
an FK on `Shipment`. See `CHANGELOG.md` 0.18.1.

## Purpose

The core transaction of the platform. A shipment is booked only after Customer,
Serviceability + Route + Pricing (all inside one Pricing Engine call), and — for a
PAID/PREPAID booking — the Branch Wallet have all agreed. This module orchestrates;
it never re-decides business logic another module already owns.

## Shape

```
Shipment (aggregate root, CompanyOwnedEntity)
├── shipmentNumber, trackingNumber (AWB)   unique per company, immutable
├── bookingDate, bookingBranchId, deliveryBranchId
├── senderCustomerId, receiverCustomerId, senderAddressId, receiverAddressId
├── serviceTypeId, packageTypeId, paymentModeId       — Master's own ids
├── shipmentType   DOCUMENT | NON_DOCUMENT | CARGO     — content category, independent
│                  of packageTypeId (container) and serviceTypeId (speed)
├── expectedDeliveryDate   bookingDate + ServiceType.deliveryDays
├── actualWeight, volumetricWeight, chargeableWeight   from the item grid
├── declaredValue, numberOfPackages, remarks
├── status   ShipmentStatus (see below)
├── items          List<ShipmentItem>       one-to-many, this module's own table
└── (charges, history, documents — see below, each its own table)

ShipmentItem     — itemName, quantity, weight, lengthCm/widthCm/heightCm, declaredValue,
                   fragile, dangerousGoods

ShipmentCharge   — the Pricing Engine's own charge breakup, persisted verbatim at
                   booking time (freight/fuelCharge/handlingCharge/odaCharge/
                   insuranceCharge/gstAmount/discountAmount/roundOff/netAmount), plus
                   matchedRouteId/matchedRateId (no physical FK — cross-module, informational),
                   otherCharges (manual, booking-time — see 0.24.0 below for its own GST),
                   appliedFreightFactor (0.24.0, `V35` — null outside the Freight Factor
                   fallback)

ShipmentStatusHistory  — append-only; previousStatus/status/remarks/changedBy/changedAt

ShipmentDocument — documentType (INVOICE|EWAY_BILL|PACKING_LIST|LR_COPY|POD),
                   documentName, documentUrl (no file-storage backend yet — the URL is
                   the source of truth, same honesty note CompanyLogo carries), remarks
```

Six tables total (`shipments`, `shipment_items`, `shipment_charges`,
`shipment_status_history`, `shipment_documents`, `shipment_assets` — the last added
0.23.0), all owned by this module from day one — no physical FK to any cross-module id
(booking/delivery branch, sender/receiver customer/address, service/package/payment
type); each is validated through that module's own application service, the same
cross-feature treatment `rate_master.route_id` and `customer_addresses`' geography ids
already get.

### `shipment_assets` (V33, 0.23.0)

One row per uploaded shipment image, immutable — a re-upload adds a new row rather than
replacing one. `assetType` is `BOOKING` (a photo attached during booking, this
feature) or `POD` (delivery photo/signature, moved here from two columns that used to
live directly on `DeliveryAssignment` — see `shipment-movement.md`); `kind` is `PHOTO`
or `SIGNATURE` (`BOOKING` is always `PHOTO`). A read (`ShipmentMapper`) takes the
newest row per `(assetType, kind)` as current — the same "newest row wins" shape those
two `DeliveryAssignment` columns used to embody directly, now generalised to one table
instead of duplicated per capture point. `ShipmentServiceImpl.uploadShipmentImage`
(booking side, `FileStoragePort` key prefix `shipment-photo`) writes a `BOOKING` row
the instant the upload succeeds — deliberately not a two-step "upload then confirm"
like POD's `uploadPodFile`/`deliver()` pair, because a booking photo isn't gated behind
any state-machine transition the way POD is behind `deliver()`.

## State machine

The full ten-state vocabulary is declared now (`ShipmentStatus.canTransitionTo`,
`.isCancellable`), so Manifest Management and the delivery modules that follow extend
a graph rather than invent one — but **this module itself only ever writes `BOOKED`
(on create) and `CANCELLED` (on cancel)**:

```
BOOKED ──> READY_FOR_MANIFEST ──> MANIFESTED ──> DISPATCHED ──> RECEIVED
   │              │                    │              │
   │              │                    │              ├──> OUT_FOR_DELIVERY ──> DELIVERED (terminal)
   │              │                    │              └──> RETURN_INITIATED ──> RETURNED (terminal)
   └──────────────┴────────────────────┴──> CANCELLED (terminal, only from BOOKED/
                                              READY_FOR_MANIFEST/MANIFESTED — once a
                                              shipment has left the branch, DISPATCHED
                                              onward, cancelling it is a return, not an undo)
```

## Business rules

At booking, in `ShipmentServiceImpl.create` (one `@Transactional`):

1. Load sender/receiver `Customer` (a 404 from Customer's own service becomes a 422
   "No such sender/receiver customer" here — a bad reference is this module's problem,
   not Customer's, mirroring `customer.md` decision 5).
2. Load the named address off that customer's own address book; must actually belong
   to them (422 otherwise).
3. Resolve each address's pincode to its raw code (`PincodeService.getById(...).getCode()`)
   — Pricing wants the code, not the id an address carries.
4. Build the item grid (or a single fallback item from top-level `actualWeight`/
   dimensions if `items` is empty); sum actual weight, volumetric weight (via the
   Pricing Engine's own reusable `VolumetricCalculator`/`WeightCalculator` — not
   reimplemented here) and chargeable weight.
5. `PackageType.maxWeightKg` ceiling check, if the master row has one set.
6. **One call** to `PricingEngine.calculate` — this is the seam that covers the
   brief's "Serviceability Check", "Route Validation" and "Pricing Engine" workflow
   steps all at once, since Pricing already runs route, serviceability, rate and
   weight-slab validation internally. Calling `RouteService`/`RateService` separately
   first would duplicate logic Pricing already owns.
   **(0.20.6/0.20.7) If there's no route/rate for this lane at all**, the Pricing Engine
   itself — not this module — falls back to the company's distance×weight Freight Factor
   grid instead of raising a refusal; this module has no fallback logic of its own to
   maintain. See `pricing-engine.md`'s "Freight Factor fallback" section and
   `CHANGELOG.md` 0.20.7 for why it lives there (not here) and the two real bugs found
   live along the way.
7. For a payment mode with `collectAtBooking = true` (PAID): check the booking
   branch's wallet `availableBalance >= netAmount` *before* committing anything
   (422 `"Insufficient wallet balance: available X, required Y."` otherwise).
8. Generate AWB + shipment number (retry-on-collision, 5 attempts; unique constraint
   is the backstop), persist shipment + items + charges + `BOOKED` history entry,
   audit `SHIPMENT_BOOKED`.
9. If PAID, publish `ShipmentEvent.PrepaidBookingConfirmed` — debited only
   **after commit** (see Wallet debit seam below). Carries no commission any more
   (0.24.3) — branch commission is credited later, when the shipment's Trip Challan
   (manifest dispatch) is created, not at booking. See `shipment-movement.md`'s
   "Dispatch" section and `branch-wallet.md`.

Update (`PUT /shipments/{id}`): full replacement, only while still `BOOKED`
(`isEditable()`), optimistic-lock via `version`, re-runs the same Customer/address/
Pricing validation, replaces the item grid and the one charge row (never appends a
second). Does **not** touch a wallet already debited at booking — a PAID shipment's
price can drift on edit; the original debit stands.

Cancel (`POST /shipments/{id}/cancel`): refused once `isCancellable()` is false
(`DISPATCHED` onward — "it has left the branch"), appends a `CANCELLED` history entry.
Does not reverse a PREPAID debit already posted.

## AWB / shipment number generation

`AwbNumberGenerator`/`ShipmentNumberGenerator` produce the candidate string;
`ShipmentServiceImpl` retries existence-check up to 5 times before giving up
(`IllegalStateException`), with `UNIQUE (company_id, tracking_number)` /
`(company_id, shipment_number)` as the actual backstop against a race — **not**
`MAX(...)+1`. `AwbNumberGeneratorTest`/`ShipmentNumberGeneratorTest` cover the format;
`ShipmentServiceImplTest` doesn't re-test uniqueness under real concurrency (that would
need an integration test against the real repository, not a mocked one).

## Wallet debit seam — `WalletService.debitForBooking(BookingDebitCommand)`

The "Booking debit seam" `MEMORY/modules/branch-wallet.md` deliberately left unbuilt
ahead of its own consumer now exists. Built as a `Command` record
(`branchId`, `amount`, `shipmentNumber`, `remarks`), matching the existing
`CreditCommand`/`DebitCommand` convention rather than a raw-parameter method — a
`SuperAdminBoundaryTest` assertion (`WalletService` exposes no method with a bare
`BigDecimal` parameter, so nothing can "just set a balance") caught the first draft,
which took `(UUID, BigDecimal, String, String)` directly.

Unlike `WalletService.debit` (manual, `COMPANY_ADMIN`-only), `debitForBooking` is
`isAuthenticated()` — Shipment Booking has already decided who may book through that
branch; this seam only moves the money that decision earns. It still refuses an
out-of-scope branch and an insufficient balance exactly as `debit` does.

`ShipmentBookingWalletListener` is `@TransactionalEventListener(phase = AFTER_COMMIT)`
+ `REQUIRES_NEW` — the same shape `finance.application.WalletProvisioningListener`
uses. The shipment is already durable by the time this runs, so a debit failure (an
insufficient balance the pre-booking check missed under a race, or a wallet gone
`INACTIVE` mid-flight) cannot roll the booking back — the shipment stays `BOOKED`,
undebited, logged for manual reconciliation. A real, accepted gap, not swept under
"impossible" — the same shape the wallet-provisioning race already carries.

## API

| Method | Path | Notes |
|---|---|---|
| `POST` | `/api/v1/shipments` | Book |
| `PUT` | `/api/v1/shipments/{id}` | Full replacement, only while `BOOKED` |
| `GET` | `/api/v1/shipments` | Paged/sorted/filtered/searched |
| `GET` | `/api/v1/shipments/{id}` | Includes item grid |
| `GET` | `/api/v1/shipments/track/{trackingNumber}` | **Not** a second bare `/shipments/{x}` — ambiguous with `/shipments/{id}` at the Spring MVC routing layer, the same "honest deviation" every other module makes when the brief's literal path doesn't fit |
| `POST` | `/api/v1/shipments/{id}/cancel` | Query param `remarks` (optional) |
| `POST` | `/api/v1/shipments/{id}/documents` | Attach a document reference |
| `GET` | `/api/v1/shipments/{id}/charges` | The persisted Pricing Engine breakup |
| `GET` | `/api/v1/shipments/{id}/history` | Oldest first |
| `GET` | `/api/v1/shipments/{id}/documents` | Newest first |
| `GET` | `/api/v1/shipments/{id}/items` | The item grid alone |
| `POST` | `/api/v1/shipments/{id}/image-upload` | multipart `file` (JPEG/PNG/WEBP/HEIC) → `{url}`, 0.23.0 — persists a `BOOKING` `ShipmentAsset` row immediately, no separate confirm step |

RBAC is still role-based, matching every other module in the project ahead of the
authorise-on-permissions capstone: `WRITERS = hasAnyRole(COMPANY_ADMIN,
BRANCH_MANAGER, OPERATOR)` for create/update/cancel/document-upload,
`isAuthenticated()` for every read. One new permission-catalogue row,
`SHIPMENT_UPLOAD` (catalogue 222 → 223) — `SHIPMENT_CREATE/UPDATE/DELETE/SEARCH/
IMPORT/EXPORT/PRINT/ASSIGN` already existed from `V6`, another instance of the
"responsibility list is ahead of the code" pattern. `BRANCH_MANAGER`/
`BOOKING_OPERATOR` (already holding `SHIPMENT_CREATE`) were extended with it.

## Frontend (`features/shipment`)

API-only, no mock data. Reuses `CustomerService` (search, address book),
`MasterDataService` (branch/service-type/package-type/payment-mode pickers) and calls
the Pricing Engine directly (`POST /pricing/calculate`, no frontend module of its own)
for a live Step 3 preview — the actual booking re-prices through the same engine
server-side inside `POST /shipments`.

**Pages**: `shipment-list` (server pagination/sort/search/filter/CSV export, gated row
actions mirroring the backend's `isEditable`/`isCancellable`), `shipment-create` (the
single-page booking screen below — since 0.23.0 also a "Shipment Image" picker card,
placed after Parties per the user's own instruction; the file uploads only once
`book()` returns a real shipment id, fire-and-forget so a failed upload never blocks
the booking itself), `shipment-view` (detail + gated Edit/Cancel + links to the three
sub-resource pages — the page `TrackBox`/`Track` resolve a search into, i.e. this
app's actual "tracking page"; since 0.23.0 shows a "Shipment Photo" card when
`shipmentImageUrl` is set, mirroring the existing "Proof of Delivery" block),
`shipment-edit` (full replacement, 409 reload, booking branch shown read-only),
`shipment-charges`, `shipment-history` (timeline), `shipment-documents` (list + an
inline attach form — no dialog, since there's nothing to upload to yet).

**`shipment-create` went through two layouts in one build.** It shipped first as a
four-step wizard (Booking & Parties → Items & Package → Pricing → Confirm). The user
then supplied a single-page "Lorry Receipt" mockup and asked for that screen to book
from directly, so it was rebuilt as one page: Booking Details → Shipment Details →
Items → Parties (sender/receiver, deliberately **last**) on the left, a **sticky live
"Booking Summary"** sidebar on the right — Route, Load, a live charge breakdown, the
Payment Mode picker, then the Book Shipment button, all reusing the app's own design
tokens rather than the mockup's own palette (a `--info`/`--brand-600` left-border
accent on the sender/receiver cards is the one colour cue actually borrowed). Pricing
is no longer a manual step: a debounced `Subject` (`schedulePricing` → 500ms →
`switchMap` into a `forkJoin` of the two pincode lookups + `PricingEngine.calculate`)
reprices automatically the moment every required field is filled in, cancelling any
in-flight request on the next change. **Booking Branch is not a picker at all** — the
brief asked for "book from my own branch, no dropdown," so it reads
`AuthService.user()?.branchId` once at construction and renders either the resolved
branch name or a hard "no branch assigned — ask an admin" stop; see the JWT `bid`/`hid`
claim addition in `MEMORY/modules/auth.md` this required.

**The `computed()` bug** (found live, not by `ng build`/`ng test`, in the wizard
version and carried into the rewrite): `canAdvance`/`bookingLabels` (`readyToPrice`/
`myBranchLabel` after the rewrite) were originally `computed()` signals that read
`FormControl.value` (the branch/service-type/package-type/payment-mode `mat-select`s)
alongside real signals (`sender`, `senderAddress`, …). `computed()` only tracks
**signal** reads for its re-run decision; a plain `.value` read is invisible to it, so
the cached result went stale the instant a dropdown changed without a signal *also*
changing — "Continue" stayed disabled forever, even after every field was correctly
filled in (verified live via `ng.getComponent(el)` in the browser console: the raw
boolean expression evaluated `true`, the cached `computed()` still returned `false`).
Fixed by converting every such check to a plain method — Angular's OnPush change
detection already re-invokes template-bound methods on every event the component
handles, so a plain method stays fresh with no extra signal wiring. The same bug, same
fix, existed in `shipment-edit.ts`'s `canSave`.

**A second, unrelated staleness bug surfaced while wiring up "default to my own
branch":** `AuthService.hydrate()` — which rebuilds the signed-in session from the
stored access token alone on every page load, as opposed to `applySession`'s in-memory
copy set once at the moment of login — had **no way to recover `branchId`/`hubId` at
all**, because neither field was ever a JWT claim before this module needed one. A
hard page reload silently lost both, so the branch name and the "no branch assigned"
message it should have shown were indistinguishable in the UI: fixed on the backend by
adding optional `bid`/`hid` claims to the access token (`auth.domain.User` gained the
same `branch_id`/`hub_id` mapping `company.User` already had on the shared `users`
table) and on the frontend by having both `hydrate()` and `applySession()` fall back to
the JWT claim. Full detail in `MEMORY/modules/auth.md`.

**GST on Other Charges + editable Freight Factor (0.24.0).** `otherCharges` now carries
its own GST, at the **booking branch's** own `gstPercentage` (V25) — folded straight into
the persisted `gstAmount`/`netAmount` in `ShipmentServiceImpl.copyCharge` (one combined
figure, not a second column), via new `gstOnOtherCharges`/`netAmountWithOtherCharges`
helpers that also keep the pre-booking wallet check and the audit log in step with what
gets persisted. Separately, when a lane falls back to the Freight Factor grid (no
route/rate — see `pricing-engine.md`'s "Freight Factor fallback"), `shipment-create.ts`
now shows a "Freight Factor" input in the Booking Summary, pre-filled with the matched
cell's own value and a "min X, increase only" hint; typing a higher number reprices
through the same debounced `/pricing/calculate` call the rest of the page already uses —
sent as `CreateShipmentRequest.freightFactorOverride`, the server (`PricingEngineImpl
.priceByDistanceAndWeight`) is the only place "increase only" is actually enforced, a
too-low value just surfaces its own 422 through the page's existing `pricingError` slot.
Changing branch/service/weight clears any typed override, since a different lane may not
even hit the fallback or may match a different cell. The live preview's own GST/Net
Amount lines fold in Other Charges' GST too (client-side, mirroring `copyCharge`, off
`BranchSummaryResponse.gstPercentage` — a new field on `GET /branches/directory`, the
same "ride along for a preview" precedent `postalCode` already set there) so the sidebar
total — and the printed consignment copy's total — match what booking actually persists.

**111 frontend tests** (98 → 111): `shipment.service.spec.ts` (HTTP contract per
endpoint, incl. the Pricing Engine call and the pincode-resolution lookup) and
`item-entry-grid.spec.ts` (the client-side weight-preview math — actual weight sums
`weight × quantity`, volumetric is `l×w×h/5000`, chargeable picks the larger, a row
missing any one dimension contributes no volumetric weight). `ng build` clean.

**Nav**: the aspirational `Operations` → "Shipment Booking"/"Shipment Search" leaves
(pointing at `/operations/booking`/`/operations/search`, placeholders since before
this module existed) now point at the real `/shipments/new`/`/shipments` routes;
`manifest`/`receive`/`sorting`/`dispatch`/`delivery` stay aspirational until Manifest
Management ships. The dashboard's `book`/`search` quick actions got real routes too —
both previously toasted "available once the shipments module ships."

### Service rate cards (2026-11-19, CW#51–#53)

- `shipment-create.ts` has no Service Type dropdown. `serviceRates$()` prices every active service type with `POST /district-level-freight/calculate`, using the same pricing chain as the booking: business-customer zone → zone tariff (the customer's own card first since V183, see `zone-tariff.md`) → District Level Freight.
- Each `ServiceRate` holds freight, oda, extras, gst, roundOff, total, days and basis, or an error. The GST is `round((freight+oda+extras) * branch GST %) / 100`.
- `availableRates`, `unavailableRates` and `cheapestRate` are computed.
- The aside `lr__sum` shows "Choose a service" with the cards until `serviceTypeId` is set. After that it shows the Booking Summary with a `.chosen` bar ("LABEL · delivery date · Change"). `changeService()` clears it.
- Package Type defaults to NON-DOC: the zone tariff needs both service type and package type.
- Spec: `shipment-rates.spec.ts`.

## Verified by running it (2026-07-30, MySQL 8.0.46, `SERVER_PORT=8081`)

Backend over HTTP (fresh `Rahul Deshmukh`/`Neha Kulkarni` customers created for the
run, Pune-GPO/Mumbai-Central pincodes, the `PNQ_BOM` route and `RATE-PNQ-BOM-STD`/
`RATE-UI-TEST` rate lanes already on file from Rate Master's own verification):

- TO_PAY booking (no wallet touched), PAID booking against a zero-balance wallet
  (422 `"Insufficient wallet balance: available 0.0000, required 136.00."`), credited
  the wallet, re-booked PAID — wallet debited **exactly** `136.00` after commit
  (5000.00 → 4864.00)
- Cancel, then a second cancel attempt correctly refused (422, "can no longer be
  cancelled — it has left the branch")
- Update re-prices and replaces the charge row; a stale `version` on update correctly
  409s
- Document attach + list; charges; history (oldest first); track-by-AWB
- Three business-rule refusals: nonexistent sender customer, an address belonging to
  the wrong customer, actual weight over a package type's `maxWeightKg`
- List filtering by status

Through the Angular console (`asha@legacy.test`/`LEGACY_CO`, `COMPANY_ADMIN` — the
signed-in dev session was first found on a `BRANCH_MANAGER` token scoped to one
branch, confirming the branch-picker options are correctly scoped before switching):
the full four-step wizard end to end (branch pickers, customer search + address
pre-select, item grid with live weight preview, the pricing preview reproducing the
exact HTTP-verified figures, the confirm summary, booking), the detail page,
Charges/History/Documents pages (including attaching a document through the UI form),
the list page (search/filter/pagination/status badges/kebab actions), and Edit
(full hydration of every field including the item grid, save-and-reprice). The
`computed()` staleness bug above was found and fixed during this pass.

## Not exercised

- A `BRANCH_MANAGER`/`BOOKING_OPERATOR`-scoped token against the *company-wide* branch
  set (only confirmed the JWT-role gap the branch-scoping itself already documents:
  `BOOKING_OPERATOR` is a company role, not a JWT authority — see "authorise on
  permissions" in `MEMORY/BACKLOG.md`)
- The `RIVAL_CO` cross-company check (inherited gap, no active user there)
- A `DISPATCHED`+ cancel refusal over live HTTP — nothing yet transitions a shipment
  past `BOOKED`; that is Manifest Management, deliberately not started here
- Concurrent booking under real load (AWB/shipment-number uniqueness is unit-tested
  and constraint-backed, not load-tested)

## Deliberately not touched

Manifest Management (the next module — do not start it per instruction), Hub
Management, the authorise-on-permissions capstone (this module's own endpoints are
role-gated like every other module, not permission-code-gated).
