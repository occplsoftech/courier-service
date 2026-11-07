# Module: Business Customer (customer extension + customerpricing)

**Status:** backend + frontend built and verified live 2026-10-13 (V105-V107). Booking
integration NOT wired yet — see "Not done".
**Packages:** `com.courier.modules.customer` (extended in place) and the new
`com.courier.modules.customerpricing`. **Frontend:** `features/customer` (extended),
`features/business-customer` (new), `core/models/pricing-zone.model.ts`.

## What it is

Sidebar group **Business Customer** (COMPANY_ADMIN-only, like Masters/Charges) with three
leaves: **Customer** (`/business-customer/customers` — the existing `CustomerList` pinned to
`BUSINESS` customers via route `data.businessOnly`), **Pricing Zone**
(`/business-customer/pricing-zones[/new|/:id]`), **Rate** (`/business-customer/rates[/:zoneId]`).
Workflow: create business customer -> add pickup/delivery addresses -> create pricing zones
-> add districts/cities -> set the zone's rate card.

## Customer extension (V105) — extended, not a parallel table

`customers` gained `contact_person`, `default_payment_mode` (`CREDIT|PAID|TOPAY`, default
PAID — enum `CustomerPaymentMode`, distinct from master `PaymentMode`), `billing_frequency`
(`WEEKLY|FIFTEEN_DAYS|MONTHLY`, default MONTHLY) and a billing address (5 text columns).
`customer_addresses` gained `address_usage` (`PICKUP|DELIVERY|BOTH`, default BOTH); the
existing default-pickup/default-delivery flags are unchanged. Existing rows = PAID/MONTHLY/BOTH,
so nothing already booked changes. The user's "15DAYS" is `FIFTEEN_DAYS` (a Java enum constant
cannot start with a digit); the UI labels it "15 Days". List filters: paymentMode,
billingFrequency (plus the old type/status/search; search now also hits contact person + GST).

## Pricing zones (V106)

`customer_pricing_zones` (FK customers.id) -> `customer_zone_locations` (district_id, nullable
city_id = whole district; geography ids validated in the service, no physical FK, like
`customer_addresses`). Rules: **only BUSINESS customers**; zone name unique per customer
(case-insensitive, live rows only); a location may not sit in two zones of one customer, and a
whole-district entry overlaps any city of that district (`ZoneLocation.overlaps`); city must
belong to the district. Soft delete everywhere, so uniqueness is checked in the service over
live rows, not with DB unique keys.

## Rate (V106)

One live `customer_zone_rates` row per zone: min chargeable weight, docket, mathadi, pack,
ODA (fixed charges), fuel % (of freight), FOV % (of declared value), effective window, status;
child `customer_zone_rate_slabs` = per-kg slabs, half-open `[min,max)`, non-overlapping, only the
last may be open-ended. `PUT /customer-rates/zone/{zoneId}` replaces header + slabs in one
transaction. **Quote** (`POST /customer-rates/calculate`): resolve the customer's ACTIVE zone for
(district, city) — a city match beats a whole-district match — then chargeable = max(actual, min),
freight = chargeable x slab rate, + docket + mathadi + pack + ODA (only if `odaApplicable`) +
fuel% x freight + FOV% x declared value. No GST (the pricing engine owns that).
Since V160 (11.6) a card also has `min_freight` (freight = max(slab freight, it), before fuel %) and `slab_mode`
WHOLE (above) or TIERED (each slab prices only its own kilos; a fixed slab adds its flat price once entered).
`GET /pricing-zones/resolve` returns the zone alone.

## Endpoints

`/api/v1/pricing-zones` (POST, GET list, GET /resolve, GET/PUT /{id}, POST /{id}/locations,
DELETE /{id}/locations/{locId}, PATCH /{id}/activate|deactivate, DELETE /{id});
`/api/v1/customer-rates` (GET, GET/PUT/DELETE /zone/{zoneId}, PATCH /zone/{zoneId}/activate|deactivate,
POST /calculate). Writes `hasRole(COMPANY_ADMIN)` on the service; reads `isAuthenticated()`.

## Permissions

New `PermissionModule.PRICING_ZONE` and `CUSTOMER_RATE`, MASTER shape (9 codes each, catalogue
253 -> 271). **V107 backfills them into every existing company's COMPANY_ADMIN role** — the JWT
is built from `role_permissions` rows, not derived from the catalogue, so a company created
before V106 otherwise shows the Customer leaf but no Pricing Zone / Rate leaves (found live).
Nav leaves are gated on `CUSTOMER_READ`, `PRICING_ZONE_READ`, `CUSTOMER_RATE_READ`.

## Gotcha found live: audit before flush

`GlobalMasters` geography reads (`DistrictService.getById`) run under the platform company id.
A dirty entity auto-flushed in that window trips `CompanyEntityListener` ("Cross-company update
blocked"), 403 COMPANY_ISOLATION_VIOLATION. Both `view()` helpers call `repository.flush()` before
any geography/customer lookup. Mocked unit tests cannot see this.

## Booking option (2026-10-13)

`shipment-create.ts` has a "Business Customer booking" checkbox on the Parties card: pick a business customer (and pickup address) to fill the Consignor and default the payment mode (CREDIT->TBB, TOPAY->To Pay, PAID->Paid; the master has no CREDIT row). Convenience only: no customer id stored, pricing unchanged.

## Dashboard (2026-10-13)

`GET /business-customers/dashboard` + `/business-customer/dashboard`. Bookings per customer are matched on consignor mobile (no customer id on shipments), cancelled excluded — approximate by design. Shows counts, payment/billing mix, Total Business and Payment Pending (net_amount; pending = To Be Billed/To Pay/COD bookings — there are no payment receipts yet). No zone/rate content on the dashboard, by request.

## Billing (2026-10-13, V110, `modules/customerbilling`)

Invoices per business customer, auto-issued by a daily job when the customer's billing cycle (WEEKLY / FIFTEEN_DAYS / MONTHLY) closes, or generated manually up to a date.
Includes every non-cancelled booking not on a live invoice (consignor-mobile match); Paid-at-booking ones are `prepaid`. Receipts via `customer_invoice_payments`
(UNPAID -> PARTLY_PAID -> PAID); cancel only with no receipts, which frees the shipments. Permission module `CUSTOMER_BILLING` (+ COMPANY_ADMIN backfill in the same migration).
Approval + e-mail (V111): invoices start PENDING_APPROVAL; approving e-mails the customer (log-only provider unless SMTP is configured); receipts need an approved invoice; mark-paid / reverse / proof upload (FileStoragePort).
**Gotcha:** the JWT carries all permission codes (~7.5 KB for a company admin) — raise-the-limit fix is in `application.yml` + `nginx.conf`, but adding permission modules keeps growing the
token; the real fix is to stop putting every code in the JWT.

## Booking pricing (2026-10-14, V113)

Wired into `FreightCalculationServiceImpl` (District Level Freight's seam), not `PricingEngine`: that is where booking freight/ODA actually come from. `shipments.business_customer_id` stores the picked customer.
Zone covers destination -> freight = chargeable (max(actual, zone min)) x slab rate, ODA from zone; no zone -> District Level Freight as before. Zone docket/mathadi/pack/fuel%/FOV% = `customerExtraCharges`,
carried as Other Charges (server minimum, operator may raise; screen prefills). Booking screen sends customer + declared value to `/district-level-freight/calculate`. Billing still matches by consignor mobile.

## Not done / follow-ups

- ~~Shipment booking does not use customer zone rates~~ — done 2026-10-14, see "Booking pricing" above.
- The pre-existing legacy-site data (77 customers, 71 rate cards at sumansoftwares.com) is not imported.
- No bulk import/export for zones or rates; no "copy zone" action.
- Dev fixtures in `courier_db`: business customer "Verify Traders Pvt Ltd" (3 addresses),
  zone "Zone A" on an individual customer GURUDATTA AGENCY (created before the BUSINESS-only rule).
