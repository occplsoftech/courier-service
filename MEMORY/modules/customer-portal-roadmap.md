# Roadmap: customer portal, booking and courier forwarding

> Written 2026-10-30 with the owner. The ordered work after the portal phases 1-5 in `customer-portal.md`.
> Status: `[x]` done · `[~]` in progress · `[ ]` todo. Size: S (a day or less), M (a few days), L (a week or more).
> Repos: **CS** = courier-service (backend), **CW** = courier-web (staff app), **EC** = e-commerce-bussines (customer portal).

## Phase summary

| Phase | Goal | Size | Depends on | Status |
|---|---|---|---|---|
| 6 | Pricing, booking and numbering foundations (done) | - | - | `[x]` |
| 7 | Stabilise and verify on dev | S | 6 | `[x]` (built 2026-11-04; two owner actions left, see 7.4 / 7.7 / 7.8) |
| 8 | Hub forwards a shipment to a courier partner | L | 6 | `[x]` |
| 9 | Customer quick wins (labels, cancel, reschedule) | M | 6 | `[x]` |
| 10 | Notifications and delivery exceptions | M | 6 | `[x]` |
| 11 | Money: POD, invoices, COD remittance, commission | L | 8 | `[x]` |
| 12 | Returns, reverse pickup and reports | L | 10, 11 | `[x]` |
| 13 | Integrations and access control | L | 8 | `[x]` (all live on dev 2026-11-17; Shopify/WooCommerce await a real store test) |
| 14 | Hub productivity: forward several shipments at once | M | 8 | `[x]` (14.1 done 2026-11-17) |

## Phase 6 — Foundations `[x]` (all merged and deployed 2026-10-30)

| Item | Repos | Notes |
|---|---|---|
| Customer rate per **service type** (AIR, SURFACE, CARGO, PREMIUM) with an all-services fallback card | CS, CW | V151. Portal shows one option per type; types without a card use company rates |
| Customer rate slabs include their upper weight (5 kg in 0-5, 5.3 kg in 5-10) | CS, CW | |
| **Business charges / Company charges** switch per customer | CS, CW | `customers.use_business_rates`, V147 |
| Import a customer's zones and rates from **Excel** (template, row check, all-or-nothing) | CS, CW | `/customer-rates/import` |
| **Bulk shipment booking** from Excel (profile pickup details, weight only, edit a row on screen) | CS, EC | `/customer-portal/bulk-bookings` |
| **AWB and shipment number issued at booking**, at the branch mapped to the pickup pincode; exact quoted price, no branch wallet debit | CS | `createFromPortalBooking`, V148 |
| Pickup belongs to the branch of its pincode; that branch's manager sees and assigns it | CS, CW | |
| Per-customer courier visibility (default OCCPL Network only) | CS, CW | V146 |
| Wallet, To Pay and credit customers; parcel photos; S3 files with signed links; label printing (A4, Code128) | CS, EC | |
| **Fixed-price slabs** (a slab can be a flat price: 0-10 kg at 100 fixed = 100 for 1 kg and for 10 kg) and the Excel import's `Slab n Pricing` column | CS, CW | V153 |
| **Quote option note** ("Minimum chargeable weight 20 kg" on Cargo) and clean service labels (Cargo, not CRG) in the calculator and booking screens | CS, EC | V154 |
| Branch manager can see the suggested partner and the partner list, and assign a pickup | CS, CW | `GET /pickups/partners` |
| Page headers (brand band with icon) and Bulk booking table layout | EC | |

## Phase 7 — Stabilise and verify `[x]` (code and dev checks done 2026-11-04; owner items open)

| # | Item | Repos | Size | Status |
|---|---|---|---|---|
| 7.1 | Retest on dev after the latest releases: branch manager assigns through the screen (#66, courier-web #27), fixed-price slab quotes, Cargo note on the live calculator | all | S | `[x]` verified on dev 2026-11-03/04 |
| 7.2 | Rate import: accept the service type **name** as well as the code | CS | S | `[x]` courier-service#70 |
| 7.3 | Warn when no slab covers the card's minimum chargeable weight | CS, CW | S | `[x]` courier-service#70 + courier-web#30 (refused on save; also in the import preview) |
| 7.4 | Remove the old all-services "OCCPL Standard" test rate if the owner wants only the four services | dev data | S | `[ ]` **owner decision** |
| 7.5 | Status badge "Awaiting pickup" lags the tracker | EC | S | `[x]` courier-service#70 (OCCPL bookings re-checked every 2 minutes) |
| 7.6 | Clean dev test data: pickups PU-20261030-0004..0012 and their shipments, test users, the 411016 pincode mapping | dev data | S | `[x]` my test pickups/shipments cancelled 2026-11-04; pickups of "Shubham", "Siddesh" and 2026-10-31 left for the owner; test users and the 411016 mapping kept |
| 7.7 | **Security:** rotate the passwords pasted in chat (staff, e-commerce login, the AWS key id); change or delete the two test users whose passwords were reset | owner | S | `[ ]` **owner** |
| 7.8 | Stop `.env.bak` / `docker-compose.yml.bak` lingering in `/opt/occpl`; merge the infra PR for `claude/courier-s3-env` | infra | S | `[ ]` **owner (server)** |

## Phase 8 — Forward to a courier partner from the hub `[x]`

Scenario: the customer books with OCCPL Network, OCCPL collects the parcel and brings it to a hub or branch, and there the shipment can be handed to Delhivery, Shiprocket or another connected courier. The shipment (AWB, shipment number) already exists from the first booking (phase 6).

| # | Item | Repos | Size |
|---|---|---|---|
| 8.1 | Shipment fields: partner, partner AWB, label, partner cost, status `FORWARDED`; migration | CS | M |
| 8.2 | "Rates for this shipment" (real weight, size, addresses) across the company's connected couriers | CS | M |
| 8.3 | "Forward" call: books with the chosen courier through the existing adapters, stores AWB and label; failure leaves the shipment at the branch with the reason; unknown outcome flagged, never auto-retried | CS | L |
| 8.4 | Staff screen: Forward button, compare-and-confirm dialog, partner AWB and label print, Forwarded badge | CW | M |
| 8.5 | Customer tracking: shows "delivered by <courier>, AWB ..." and merges the courier's events; the customer keeps the OCCPL AWB | CS, EC | M |
| 8.6 | Courier cost saved as our cost; per-shipment margin visible (customer price unchanged) | CS, CW | S |
| 8.7 | Safeguards: admin and branch/hub manager only, audit log, forwarding twice blocked, cancel at the courier first | CS | S |

Defaults agreed in the brief: courier collects from the branch/hub; customer price unchanged; bulk forwarding later.

## Phase 9 — Customer quick wins `[x]`

| # | Item | Repos | Size |
|---|---|---|---|
| 9.1 | Print **all labels** of a bulk upload in one go; download the bulk result as Excel | CS, EC | S |
| 9.2 | **Cancel** a booking before pickup (refund to wallet, release the shipment number) — done (OCCPL Network only) | CS, EC | M |
| 9.3 | **Reschedule** the pickup (date and slot) — done | CS, EC | M |
| 9.4 | Wallet statement export; shipments list export — done | CS, EC | S |

## Phase 10 — Notifications and delivery exceptions `[x]`

| # | Item | Repos | Size |
|---|---|---|---|
| 10.1 | SMS / WhatsApp / email to the customer at booked, picked up, out for delivery, delivered (reuse Communication Center) | CS | M |
| 10.2 | Delivery failed / undelivered: reason, the customer's choice (re-attempt, change address, return), staff queue — done (failed attempt, reason, message; the customer's choice; staff queue). Return = mark Returned; return due after 3 attempts / 7 days | CS, CW, EC | L |

## Phase 11 — Money `[x]`

| # | Item | Repos | Size |
|---|---|---|---|
| 11.1 | Proof of delivery download for the customer — done (name/time always, photo/signature once approved; printable POD) | CS, EC | S |
| 11.2 | Credit customers: ledger, invoices and statement in the portal — done (read-only; approved invoices) | CS, EC | M |
| 11.3 | **COD remittance** — done: separate amount to collect; collected COD paid straight into the customer's wallet on delivery (no remittance batches) | CS, CW, EC | L |
| 11.4 | **Commission** for portal shipments — done: same % as a counter booking, credited at in-scan/delivery; invoices match portal shipments by customer id | CS | M |
| 11.5 | Per-customer markup screen for external couriers — done (Rate Master → Courier Markup: rules, add, end now, which markup applies) | CS, CW | M |
| 11.6 | Rate card extras — done: minimum freight and tiered slab pricing (per card, both optional) | CS, CW | M |

## Phase 12 — Returns, reverse pickup, reports `[x]`

| # | Item | Repos | Size |
|---|---|---|---|
| 12.1 | Return / RTO shipments and reverse pickup from the buyer — done: RTO charge = forward freight; OCCPL return tracked as a trip back (Load Sheet to the booking branch, hand back); reverse pickup on OCCPL Network (outside couriers later) | CS, CW, EC | L |
| 12.2 | Customer dashboard — done: all couriers, period switch (7/30/90 days, this month), spend, on-time %, where the parcels are, courier split, late and failed-delivery lists | CS, EC | M |

## Phase 13 — Integrations and access control `[x]`

| # | Item | Repos | Size |
|---|---|---|---|
| 13.1 | **Enforce courier access at booking** — done: the booking step re-checks allowed + connected (and OCCPL-only for return pickups) before anything is created or charged | CS | S |
| 13.2 | Branch-level courier assignment — done: home branch per customer (from pickup pincode, editable) and a courier list per branch as the default for its customers | CS, CW | M |
| 13.3 | Connect the customer's shop (Shopify / WooCommerce) or an API key so orders arrive without re-typing — chosen: an Orders inbox the customer books from; order: a) API key, b) Shopify (customer pastes a custom-app token), c) WooCommerce. a) done; b) Shopify done; c) WooCommerce done (same flow; note + Completed back) | CS, EC | L |
| 13.4 | Filter the staff pickup list by branch for admins; branch dashboards — done: branch picker on Pickup Requests (list + counts) and View on the Dashboard (an admin sees any branch's dashboard) | CS, CW | S |

## Phase 14 — Hub productivity `[x]`

| # | Item | Repos | Size |
|---|---|---|---|
| 14.1 | Forward several shipments at once from a hub (one courier choice per shipment, one confirm) — done: Shipments At Hub → Forward Shipments, cheapest pre-selected, up to 20 per call (CS#115, CW#46/#47) | CS, CW | M |

## Done outside the phases (2026-11-17 to 2026-11-19)

| Item | Repos |
|---|---|
| Platform → All Bookings for the super admin: every booking across companies with carrier (OCCPL / forwarded / booked with, real courier behind an aggregator), Excel | CS#116, CW#48 |
| Area serviceability: per-area switch, booking blocked when off, Unserviceable Requests report with counts per pincode + area (staff and portal), Turn on | CS#117, CW#49/#50 |
| Booking picks the service from zone-tariff rate cards in the summary panel; no Service Type dropdown; NON-DOC default | CW#51/#52/#53 |

## Next (not started)

- **WheelsEye API integration** — waiting for the owner: purpose, API docs, credentials.

## Decisions and actions waiting on the owner

| Item | Needed |
|---|---|
| Shipyaari returns HTTP 500 | Activate a plan on the Shipyaari account |
| Company logo / favicon uploads vs the private S3 bucket | Choose: store in the database, or a public folder |
| Real rates for customers | Send the numbers or the filled Excel; load through the import |
| Real Shopify / WooCommerce test | A Shopify development store and a WooCommerce site on https (keys pasted in the portal by the owner) |
| Shiprocket cancel (403) | Give the API user permission to cancel |
| Blue Dart, Ekart, Porter | Connect the accounts (credentials) |
| AIR rate to Pune in PUNE_CASH_2025 | Add the lane if AIR should be offered for Pune bookings |
| TEST_BR wallet ₹10.80 | Top up to test Paid bookings as the branch |
| Passwords pasted in chat; `.bak` files on the server | Rotate the passwords; delete the files |

## Known behaviours to remember

- A portal shipment is created only if the pickup pincode is mapped to an active, booking-enabled branch (not a pickup partner) and the company has an active payment mode (paid, To Pay, COD, credit), service type and package type; otherwise the booking falls back to a plain pickup that the branch books later.
- The company's 15 kg counter minimum does not apply to portal shipments: the quoted chargeable weight is kept.
- Dev service type codes are AIR, CRG, PRM, SRF; the portal option is named from the type name ("OCCPL SURFACE").
- Migration numbers: always fetch `main` and take the next free one. The per-service-type migration had to move from V149 to V151 because another PR took V149 and V150.
