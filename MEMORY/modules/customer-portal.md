# Module: Customer Portal (e-commerce-business backend)

**Status:** phase 1 built 2026-10-21 (V128): customer authorization, portal access, profile,
and the quote/markup/booking schema. Rate shopping, markup service, booking, tracking and labels
are later phases — see "Phases".
**Package:** `com.courier.modules.customerportal` (new). Small extensions in `auth` (`users.customer_id`,
customer-portal accounts) and `customer` (`CustomerPortalLookup`).
**Frontend:** separate repository `occplsoftech/e-commerce-bussines` (Angular, same conventions as
`courier-web`, deployed separately).

## What it is

A web portal for a courier company's **business customers** (`Customer` with type `BUSINESS`):
log in, enter pickup/delivery/package details, compare courier options with genuine prices, book one,
and follow the shipment. `courier-service` stays the only backend and the authority for prices,
ownership and bookings.

## Findings that shaped it (2026-10-21)

- No external courier provider (Delhivery, Blue Dart, Ekart, Shiprocket) is integrated anywhere in the
  code, and no credentials exist. Each one is a new adapter, disabled until its account and API access
  are confirmed. Nothing is shown for a provider that is not configured except "rate not available".
- OCCPL's own network already has genuine customer prices: pricing zones + rate cards
  (`customerpricing`, booking via `FreightCalculationServiceImpl`). That is the first provider
  ("OCCPL network"), booked into the normal `Shipment` flow.
- `Role.CUSTOMER` existed but nothing used it. Many staff services read with `isAuthenticated()`
  (e.g. `CustomerServiceImpl.READERS`), so **a CUSTOMER token must never reach staff endpoints** —
  see "Scope gate".

## Decisions (agreed with the owner 2026-10-21)

| Topic | Decision |
|---|---|
| Login | Courier's own login (`/api/v1/auth/login`), a user with role `CUSTOMER` linked to one business customer by `users.customer_id`. Same refresh tokens, logout, password reset. Not platform SSO. |
| Providers | OCCPL network first; Shiprocket and Delhivery adapters behind the common interface, enabled per company only with credentials; Blue Dart and Ekart report "not configured" until a contract and API documentation exist. |
| Markup | Applies to **external providers only**; OCCPL network prices come from the customer's own rate card. Configurable percentage (initially 10 %), per company, optionally per provider/customer, with effective windows. |
| Repository | `occplsoftech/e-commerce-business`. |

## Customer authorization (phase 1)

- `users.customer_id` (V128) — set only for `CUSTOMER` accounts. Read by `auth.User.customerId`.
- **Scope gate** `shared.config.CustomerScopeFilter` (in the security chain after the JWT filter): a
  principal holding role `CUSTOMER` may call only `/api/v1/customer-portal/**` and `/api/v1/auth/**`;
  anything else is 403 before any controller runs. `/api/v1/customer-portal/**` itself requires role
  `CUSTOMER` (staff tokens get 403). Fail-closed: a token with `CUSTOMER` plus any other role is still
  gated.
- `CurrentCustomerResolver` resolves the acting customer on every portal request from the verified
  principal: user id -> `users.customer_id` (fresh from the database) -> an ACTIVE BUSINESS customer in
  the same company. Any gap is 403. Nothing in a request body or path ever names the customer.
- Provisioning: `POST /api/v1/customers/{id}/portal-access` (COMPANY_ADMIN) creates the `CUSTOMER`
  account for a business customer: PENDING, temporary password returned once, activation email.
  `GET` shows whether access exists; `DELETE` disables the account. One account per customer in phase 1.
- Emailed links (activation, password reset) for `CUSTOMER` accounts use
  `app.auth.customer-portal-base-url` (`CUSTOMER_PORTAL_BASE_URL`), falling back to `app-base-url`.

## Endpoints (phase 1)

| Method | Path | Who |
|---|---|---|
| GET | `/api/v1/customer-portal/profile` | CUSTOMER — own customer, company name, addresses |
| POST/GET/DELETE | `/api/v1/customers/{id}/portal-access` | COMPANY_ADMIN |
| GET | `/api/v1/customer-portal/dashboard` | CUSTOMER — counts by stage + 5 recent shipments (phase 2) |
| GET | `/api/v1/customer-portal/pincodes/{pincode}` | CUSTOMER — city/district + OCCPL network flags; 422 malformed, `known=false` unknown (phase 2) |

`POST /api/v1/auth/forgot-password` also accepts `companyCode` instead of `companyId` (phase 2): the portal
login form knows the company only by its code.

Dashboard stages: booked = BOOKED + READY_FOR_MANIFEST; in transit = MANIFEST_CREATED + DISPATCHED + IN_SCAN +
OUT_FOR_DELIVERY; delivered, returned, cancelled as is. A shipment belongs to the customer by
`shipments.business_customer_id` (`ShipmentPortalQueries`). `pendingBookings` is 0 until portal bookings exist
(phase 4: bookings not yet a shipment — submitting or outcome unknown).

## Schema (V128)

- `pricing_markup_rules` — percent (0-100, DECIMAL(7,4)), enabled flag, components it applies to
  (`FREIGHT` by default), scope (provider code / customer, NULL = all), effective window. Changes add a
  row and close the previous window; audit via `AuditService`.
- `rate_quotes` + `rate_quote_options` — one comparison: request snapshot (JSON), weights, and per
  provider service the frozen amounts (base freight, other charges, markup %, base and amount, tax,
  total), breakdown JSON, availability or the reason it is unavailable, validity.
- `portal_bookings` — idempotent per `(company, customer, idempotency_key)`; status
  PENDING -> SUBMITTING -> BOOKED | FAILED | OUTCOME_UNKNOWN; shipment id, provider reference, AWB,
  label. OUTCOME_UNKNOWN is never retried automatically.

## Pricing rules (for phase 3)

- `BigDecimal`, HALF_UP to 2 places at each line; markup = base x percent / 100 on the configured
  components only; tax computed after markup on the taxable base per provider contract, never twice;
  COD, fuel, remote-area fees follow the rule's `applies_to` list.
- Snapshot at quote time; a booking uses the snapshot, revalidating with the provider when the quote
  has expired or the provider requires it. A later markup change never alters an existing quote or booking.

## Phases

> What comes after phase 5 (rate per service type, bulk booking, AWB at booking, hub forwarding...) is in [`customer-portal-roadmap.md`](customer-portal-roadmap.md).

1. Architecture, customer authorization, data model — **done** (this file).
2. Portal repo: login, dashboard, shipment form, validation — **done** (backend: dashboard, pincode lookup, forgot-password by code).
3. **done (rates)** — Provider adapters (`CourierProviderAdapter`: rates now; book, track, label, cancel join in phases 4-5),
   OCCPL network adapter, Shiprocket/Delhivery adapters (mocked tests, sandbox when credentials exist),
   `/customer-portal/rates/compare`, markup administration.
4. Booking with idempotency and confirmation — **done**.
5. Shipment history, tracking, label download, profile/addresses — **done** (cancellation not included).
6. Tests, security review, docs, environment and deployment — **done** (see "Security review and deployment"; unverified items listed there).

## Rate comparison (phase 3)

`POST /api/v1/customer-portal/rates/compare` (CUSTOMER; pickup, delivery, package) and
`GET /api/v1/customer-portal/quotes/{quoteId}` (own quotes only, else 404). `RateComparisonService`:

1. Validates (pincodes, weight > 0 up to 1000 kg with 3 dp, dimensions with 1 dp, inches converted to cm,
   1-999 packages, value > 0, PREPAID/COD) and rate-limits (20 comparisons/minute/account, in memory).
2. Asks every `CourierProviderAdapter`: OCCPL network on the request thread (it needs the bound company and
   principal), external ones in parallel on `courierRateExecutor` with `provider-timeout` (+2 s grace). A
   provider that fails, times out or is not connected becomes an "unavailable" row with a readable reason.
3. Prices each service with `MarkupCalculator` (see Pricing rules) and stores `rate_quotes` +
   `rate_quote_options` (available and unavailable) in one short transaction (`RateQuoteStore`), valid for
   `quote-validity` (30 min).

| Provider | Code | State |
|---|---|---|
| OCCPL network | `OCCPL_NETWORK` | Live. Customer's zone rate card (`ZoneRateService.calculate`), pickup/delivery/COD flags from the pincode master (`PincodeService.location`, new). Actual weight only (the rate card has no volumetric rule). No markup; GST at `occpl-network-gst-percent` (keep equal to the booking branch's GST). |
| Shiprocket | `SHIPROCKET` | Built, off until `SHIPROCKET_ENABLED=true` + `SHIPROCKET_API_EMAIL`/`SHIPROCKET_API_PASSWORD`. Login token cached 9 days; `GET /v1/external/courier/serviceability/`. Field names follow Shiprocket's docs and **must be checked against the account's real responses** before enabling (tax inclusion of `rate` in particular). |
| Delhivery | `DELHIVERY` | Built, off until `DELHIVERY_ENABLED=true` + `DELHIVERY_API_TOKEN` (`DELHIVERY_BASE_URL` = staging for tests). Pincode check, then charges for Surface and Express on max(actual, volumetric) grams; GST split out from `total_amount - gross_amount`. **Verify on staging.** |
| Shipyaari | `SHIPYAARI` | Built from Shipyaari's published API collection (docs.shipyaari.com, "Blaze"), off until `SHIPYAARI_ENABLED=true` + `SHIPYAARI_API_EMAIL`/`SHIPYAARI_API_PASSWORD` (`SHIPYAARI_BASE_URL` = `https://stage-api-seller.shipyaari.com` for tests). Aggregator over DTDC, Delhivery, Blue Dart, Ekart, Xpressbees, Amazon and others. `POST /api/v1/seller/signIn` → JWT (cached 20 h); rates `POST /api/v1/order/checkServiceabilityV2` (one rate per partner service; `base/add/variables/cod/insurance/tax` become charge lines that always add up to `total`); booking `POST /api/v1/order/placeOrderApiV3` in one call (assigns the courier and AWB); tracking `GET /api/v1/tracking/getTracking?trackingNo=`; label `POST /api/v1/labels/fetchLabels`. No pre-registered pickup location: the customer's own pickup address goes with every order, so the integration has only an e-mail and a password. A rate's `serviceCode` is `PARTNER\|SERVICE\|MODE` (e.g. `DTDC\|ECONOMY\|SURFACE`) and the order sends them as `courierPartner`, `courierPartnerServices`, `serviceMode`. Shipyaari debits **its own wallet** when it accepts an order (a seller-account wallet, separate from the customer wallet here); a refusal for that reason is logged and the customer sees a generic "could not accept" message. **Not run against a live account. Verify on staging before enabling**: (1) that `courierPartnerServices` = the quoted `companyServiceName` selects the quoted service (the docs only say "e.g. Economy 0.5 kg"), (2) the label response (the docs show no example; the first `https://` address in the answer is used, hosts `.shipyaari.com` and `.amazonaws.com`), (3) that our booking id is accepted as `orderId` (the docs advise against character ids), (4) `paymentMode` `PREPAID`/`COD` (the docs' example says `PREPAID`, their prose says "COD or Online"). Shipyaari publishes no webhook registration, so status comes from the hourly tracking sync only. |
| Blue Dart, Ekart | `BLUEDART`, `EKART` | Not connected (no contract/API docs). Always "not connected yet". |

Markup administration: `GET/POST /api/v1/pricing-markup-rules` (COMPANY_ADMIN). Resolution = most specific rule
in force (customer+provider > customer > provider > all); none = `default-markup-percent` (10) on FREIGHT. A new
rule closes the open rule of the same scope at its start; created/closed are audited
(`PRICING_MARKUP_RULE_CREATED/CLOSED`). Markup GST: `markup-gst-percent` (18) — **confirm with the accountant**.

Settings (`app.customer-portal.*`, env): `CUSTOMER_PORTAL_QUOTE_VALIDITY`, `CUSTOMER_PORTAL_PROVIDER_TIMEOUT`,
`CUSTOMER_PORTAL_VOLUMETRIC_DIVISOR`, `CUSTOMER_PORTAL_DEFAULT_MARKUP_PERCENT`, `CUSTOMER_PORTAL_MARKUP_GST_PERCENT`,
`CUSTOMER_PORTAL_OCCPL_NETWORK_GST_PERCENT`, `SHIPROCKET_*`, `DELHIVERY_*`, `SHIPYAARI_*`. Credentials never leave the server.

Not verified yet: no live/sandbox call to Shiprocket or Delhivery (no credentials); OCCPL network pricing not
exercised against real zone data; schema validation of the new entities not run against MySQL.

## Booking (phase 4)

`POST /api/v1/customer-portal/shipments` (header `Idempotency-Key`, body `{quoteId, optionId}`) and
`GET /api/v1/customer-portal/bookings/{id}` (own only). `PortalBookingService` + `PortalBookingStore`:

1. Same key again: the same request returns the existing booking (provider not called again); a different request
   is 409 `IDEMPOTENCY_KEY_REUSED`. Keys: 16-80 of `[A-Za-z0-9_-]`; the portal makes one per Confirm screen and reuses
   it for retries. A concurrent duplicate insert (unique key) is caught and answered with the stored booking.
2. `start` (one transaction, quote row `PESSIMISTIC_WRITE`): quote owned, option available, quote/option not expired
   (409 `QUOTE_EXPIRED` - the customer refreshes; we do not silently re-price), no other PENDING/SUBMITTING/BOOKED/
   OUTCOME_UNKNOWN booking of the quote (409 `QUOTE_ALREADY_BOOKED`). Saves SUBMITTING with the frozen option amount.
3. Provider called outside any transaction with our booking id as the provider's order id.
4. `finish`: BOOKED (reference, AWB, quote marked BOOKED), FAILED (provider certainly refused; nothing booked) or
   OUTCOME_UNKNOWN (timeout, unreadable answer, half-finished two-step booking, unexpected exception on an external
   call). OUTCOME_UNKNOWN is never retried automatically and blocks another booking of the quote until operations
   reconcile it (a staff reconciliation screen is a follow-up). Audited: `PORTAL_BOOKING_SUBMITTED/BOOKED/FAILED/OUTCOME_UNKNOWN`.

Per provider:
- **OCCPL network**: creates a **pickup request** (`PickupService.createFromCustomerPortal`, new, no staff gate) for the
  customer with expected parcels/weight and, in remarks (500 chars), the delivery details, contents, dimensions and
  quoted total. Booking reference = pickup number; the tracking number appears when operations collect the parcel and
  book it at the branch (existing pickup -> shipment flow). Pricing then comes from the counter's own engine; a
  difference from the quoted total must be settled by operations (follow-up: carry the quote into the booking screen).
- **Shiprocket**: `POST /v1/external/orders/create/adhoc` (order_id = booking id, `SHIPROCKET_PICKUP_LOCATION`), then
  `POST /v1/external/courier/assign/awb` with the chosen courier id. Order created but no AWB = OUTCOME_UNKNOWN with the
  Shiprocket shipment id.
- **Delhivery**: `POST /api/cmu/create.json` (form `format=json&data=...`, order = booking id,
  `DELHIVERY_PICKUP_LOCATION`). `success=false` = FAILED; 5xx/timeout = OUTCOME_UNKNOWN.
- External bookings pick up from the **registered pickup location** (OCCPL's warehouse/hub), not from each customer's
  address: per-customer pickup locations need the providers' pickup-address APIs (follow-up). The customer's pickup
  contact goes into the order comment.

Dashboard `pendingBookings` now counts the customer's PENDING/SUBMITTING/OUTCOME_UNKNOWN bookings.

## After booking (phase 5)

`PortalShipmentService` (own bookings only, 404 otherwise):
- `GET /customer-portal/bookings?status=&provider=&from=&to=&page=&size=` - history, newest first, size <= 50; `status` one or several comma-separated; `from`/`to` are
  booking dates (yyyy-MM-dd, India time, both inclusive); `provider` a provider code. `GET /customer-portal/bookings/couriers`
  lists the providers the customer has booked with (for the filter). Route, recipient, service,
  tracking number and amount from the booking + its frozen quote/option (`PortalBookingStore.page`, three queries a page).
- `GET /customer-portal/bookings/{id}` - detail: parties and parcel from the quote snapshot, frozen charge lines,
  `labelDownloadable` (BOOKED and the provider has label hosts; OCCPL network never - labels are printed at the branch).
  The phase-4 `BookingView` is still the answer of `POST /shipments`.
- `GET /customer-portal/bookings/{id}/tracking` - asked of the provider on every call; the latest status is also saved
  (see *Delivery status sync* below)
  (`CourierProviderAdapter.track`). Only BOOKED bookings call out. A tracking number issued later (OCCPL: when the branch
  books the parcel) is written back to the booking. OCCPL network: pickup steps from `pickup_requests` (looked up by
  pickup number for the signed-in customer) + the shipment's status history with branch names
  (`ShipmentPortalQueries.timeline`, no staff remarks or user ids). Shiprocket `GET /v1/external/courier/track/awb/{awb}`;
  Delhivery `GET /api/v1/packages/json/?waybill=`. Provider times without an offset are read as IST.
- `GET /customer-portal/bookings/{id}/label` - PDF streamed by the server (`Content-Disposition: attachment`,
  `no-store`). The provider URL (`portal_bookings.label_url`) never reaches the browser. Stored URL tried first; if it no
  longer answers it is cleared and the provider asked once (`CourierProviderAdapter.label`: Shiprocket
  `POST /v1/external/courier/generate/label`, Delhivery `GET /api/p/packing_slip?wbns=&pdf=true`). `LabelFetcher` guards
  the download: https only, the provider's own `labelHosts()` (exact host or `.suffix`), no redirects, <= 10 MB, body
  must start with `%PDF`.
- Tracking + label calls share `ProviderCallLimiter`: 30 a minute per account (in memory, like the compare limiter).
- `GET /customer-portal/shipments/{shipmentId}/tracking` - counter-booked shipments (dashboard list), customer's own.

Address book (`CustomerPortalAddressBook` in module customer, no staff gate, called only by `customerportal`):
`GET/POST /customer-portal/addresses`, `PUT/DELETE /customer-portal/addresses/{id}`. The customer types a pincode; the
geography ids come from the pincode master (`PincodeService.location`), so a pincode not on file cannot be saved (the
table stores ids only - the booking form still accepts any pincode). One default pickup and one default delivery
address; usage PICKUP/DELIVERY/BOTH must agree with the defaults; duplicate lines+pincode refused; at most 50; optimistic
`expectedVersion` (409). Remove = INACTIVE (kept for history). Profile addresses now show pincode, area, city, district
instead of ids. Audited as `CUSTOMER_ADDRESS_CREATED/UPDATED` with `source=CUSTOMER_PORTAL`.

Not verified against live providers: the tracking/label response fields above are as documented by Shiprocket and
Delhivery and are read defensively; check them against the accounts' own responses before enabling.

## Delivery status sync (V135)

Booked portal shipments remember the courier's latest status (`portal_bookings.delivery_status` = `DeliveryStage`:
AWAITING_PICKUP, PICKED_UP, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, RETURNED, CANCELLED; plus the courier's own text,
location, time, `tracking_synced_at`, `next_tracking_sync_at`). `DeliveryStage.of` reads courier wording by keyword; the
portal's `shipment-progress.ts` uses the same rules - keep them in step.
- **Background sync**: `TrackingSyncJob` (every 2 min) -> `TrackingSyncService.syncDue`: bookings whose
  `next_tracking_sync_at` has come, across companies (`findDueForTrackingSyncAcrossCompanies`, no company context), each
  courier call inside `CompanyContext.runAs(booking's company)`. First check 10 min after booking, then every
  `app.customer-portal.tracking-sync.interval` (1h). Finished (delivered, cancelled, return delivered) or older than 45 days
  -> `next_tracking_sync_at = NULL`, never asked again. Batch 100 a run. `TRACKING_SYNC_ENABLED=false` stops it.
- **Webhooks**: `POST /api/v1/webhooks/courier-tracking/{token}` (public). `courier_integrations.webhook_token` (48 hex,
  SecureRandom) is created when an integration is saved or listed; the full address is `IntegrationView.webhookUrl`
  (`TRACKING_WEBHOOK_BASE_URL`, else `APP_BASE_URL`) and is pasted into the courier's panel. No brand name in the path
  (Shiprocket refuses URLs containing "shiprocket"). Unknown token -> 404; known -> always 200. The payload is read
  leniently (Shiprocket `awb/current_status/current_timestamp/scans`, Delhivery `Shipment.AWB/Status.*`, lists), the
  status saved at once and confirmed with the courier's API 30 s later by the job, so a garbled push does not stick.
- Writes are JPQL bulk updates (`recordDelivery`, `recordSyncAttempt`, `syncSoon`) naming the company; `PortalBooking` is
  `@DynamicUpdate` so an entity save elsewhere never writes back a stale status.
- History: `GET /customer-portal/bookings?delivery=IN_TRANSIT,DELIVERED` (booked only; AWAITING_PICKUP also matches not yet
  checked). Summary and detail carry `deliveryStatus {stage, text, location, at, checkedAt}` (null until known).
- Not verified against live couriers: the webhook payload fields are as documented/commonly sent; unknown shapes only
  lead to a confirming sync.

## sKart international (phase 1: rates and tracking)

`SkartAdapter` (code `SKART`, "sKart International"): one franchisee account prices and books DHL, FedEx, UPS, Aramex,
Skynet and sKart's own services abroad. API: `devapiv2.skart-express.com/api/v1/booking` (OpenAPI "sKart APIs" 1.0.0,
development system; live URL from sKart -> `SKART_BASE_URL`). User name and password go in every body (GET: query); no token.
- **International only**: `CourierProviderAdapter.domestic()` is false, so the domestic comparison neither asks nor lists it.
  Prices come through `InternationalRateProvider` -> `RateComparisonService.estimateInternational` ->
  `POST /customer-portal/rates/international/estimate` {originPincode, destinationCountry, destinationPostcode,
  kind PARCEL|DOCUMENT|COMMERCIAL, weightKg, pieces, maxLengthCm}. Same markup/GST pricing (`priceOptions`); nothing stored.
- `POST /rate-calculator` booking_type 1: each product -> one rate; pre-tax `charge_amount` lines (zero dropped,
  FREIGHT/FUEL/REMOTE/OTHER) + one TAX line = sum of igst/cgst/sgst amounts. Verified: lines add up to
  `grand_total_with_gst` (DHL DEL 2244.00 + 403.92 = 2647.92).
- `GET /customer-portal/international/countries`: sKart `GET /country/` (blank name = all), active only, A-Z, cached 12 h.
- Connection test: `GET /country/INDIA` (400 "Invalid credentials" = refused). Tracking: `POST /track-shipment`;
  `track_data` is mostly oldest first but not always ("Shipment Booked" comes last) -> sorted newest first by time.
- No webhooks (`IntegrationView.statusPush=false`, no webhook address); statuses come from the hourly sync once booking exists.
- **Not built yet: booking.** `/booking-api` (international) needs country id, city, items with HSN codes and commodities,
  invoice no/date, currency id, export type, GST, KYC (`/kyc-upload` -> `kyc_info`), pickup window; DHL products send an
  OTP SMS to the sender's mobile (first call otp "" -> 201, second call with the OTP -> 200). The booking answer (AWB,
  label link) is not documented and no label/cancel endpoint exists: ask sKart for a sample booking response, the
  product-name -> `courier_id` mapping (rates return names only) and how labels are fetched.
- Verified live against the development system with the documentation's sample account (read-only calls only, 2026-10-29).

## Security review and deployment (phase 6)

Full review table, tests and residual risks: `docs/SECURITY.md` in the portal repository. Backend facts worth keeping:
- Every customer-facing service starts with `CurrentCustomerResolver.require()`; staff-facing ones
  (`CustomerPortalAccessService`, `MarkupRuleService`) carry `@PreAuthorize(COMPANY_ADMIN)`. `/auth/impersonate/**` is gated in
  `SecurityConfig` and again in `AuthService`.
- Rate limits (`CompareRateLimiter` 20/min, `ProviderCallLimiter` 30/min, one `SlidingWindowLimiter`) are per instance, in memory.
- Deployment lives in the OCCPL repository (`infra/vps`): `courier-portal` service, `PORTAL_HOST` Caddy block, backend env
  `APP_CORS_ALLOWEDORIGINS` (courier + portal hosts), `CUSTOMER_PORTAL_BASE_URL`, `SHIPROCKET_*`, `DELHIVERY_*`;
  `courier-deploy.sh portal <sha>`. Runbook section 7a.
- First things to check on the dev server: sign in as a portal customer, compare rates (OCCPL network works without any
  provider account), book via OCCPL network (creates a pickup), open the shipment, then connect Shiprocket/Delhivery in staging.

## Courier integrations (company-owned courier accounts)

A company administrator connects the company's own courier accounts in courier-web (Administration > Courier Integrations).
- `GET /api/v1/courier-integrations` (every courier, state, form fields), `PUT /{code}` (`{enabled, values}`), `POST /{code}/test`,
  `DELETE /{code}` (disconnect). COMPANY_ADMIN only (`@PreAuthorize` in `CourierIntegrationService`); always the caller's own company.
- Table `courier_integrations` (V129): one row per company + courier; plain fields in `settings_json`, secret fields as one JSON
  object AES-GCM encrypted with `SECRETS_ENCRYPTION_KEY` (`CourierSecretsCodec`, decrypted by hand so an unreadable secret cannot break
  loading the row). Secrets are never returned: the view says only `set`. A blank secret on save keeps the stored one.
- `CourierCatalog` lists the couriers and their fields. Shiprocket (email, password, pickupLocation), Delhivery (token,
  pickupLocation) and Shipyaari (email, password) are connectable; Blue Dart and Ekart are listed `available=false` (no integration code) and refused with 422.
  Base URLs are NOT fields (a company must not be able to point the server at an arbitrary host); they stay in the environment.
- `CourierCredentials` resolves per call, by the company of the request: saved account wins (a saved-but-disabled account switches the courier
  off for that company even if the server has env settings); no saved account falls back to `app.customer-portal.*` env settings;
  an unreadable secret or database error counts as "not connected". Adapters take a `Function<UUID, settings>`; `configured(companyId)`
  replaces `configured()` in the comparison. Shiprocket logins are cached per distinct account (hash of email + password).
- `testConnection(companyId)` on adapters: Shiprocket logs in afresh and (best effort) checks the pickup location exists
  (`GET /v1/external/settings/company/pickup`, response shape unverified, read defensively); Delhivery makes one pincode lookup with the token
  (pickup location can only be checked at booking). Tests need a saved, enabled account, are limited by `ProviderCallLimiter`, and the
  outcome is stored (`last_test_*`, cleared when a secret changes). Audited: `COURIER_INTEGRATION_SAVED/TESTED/DISCONNECTED`.
- Not yet verified: against MySQL, or with real Shiprocket/Delhivery accounts. To add Blue Dart / Ekart: write the adapter, add the
  provider to `CourierCatalog` with `available=true`, and register the bean; the screen needs no change.

## Known gaps after phase 1

- Customer accounts live in `users`, so staff **User Management** lists them too, and a company admin
  could add a staff role to one. The scope gate still keeps such an account inside the portal
  (fail-closed); hiding customer accounts from staff screens is a follow-up.
- ~~Profile addresses carry geography ids~~ - fixed in phase 5.
- Not yet verified against MySQL or a running server: V128 and the security chain are covered by unit
  tests only (`CustomerScopeFilterTest` drives the filter, not the full chain).
