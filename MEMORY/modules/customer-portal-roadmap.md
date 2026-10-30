# Roadmap: customer portal, booking and courier forwarding

> Written 2026-10-30 with the owner. The ordered work after the portal phases 1-5 in `customer-portal.md`.
> Status: `[x]` done · `[~]` in progress · `[ ]` todo. Size: S (a day or less), M (a few days), L (a week or more).
> Repos: **CS** = courier-service (backend), **CW** = courier-web (staff app), **EC** = e-commerce-bussines (customer portal).

## Phase summary

| Phase | Goal | Size | Depends on | Status |
|---|---|---|---|---|
| 6 | Pricing, booking and numbering foundations (done) | - | - | `[x]` |
| 7 | Stabilise and verify on dev | S | 6 | `[~]` |
| 8 | Hub forwards a shipment to a courier partner | L | 6 | `[ ]` **next** |
| 9 | Customer quick wins (labels, cancel, reschedule) | M | 6 | `[ ]` |
| 10 | Notifications and delivery exceptions | M | 6 | `[ ]` |
| 11 | Money: POD, invoices, COD remittance, commission | L | 8 | `[ ]` |
| 12 | Returns, reverse pickup and reports | L | 10, 11 | `[ ]` |
| 13 | Integrations and access control | L | 8 | `[ ]` |

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
| Page headers (brand band with icon) and Bulk booking table layout | EC | |

## Phase 7 — Stabilise and verify `[~]`

| # | Item | Repos | Size | Status |
|---|---|---|---|---|
| 7.1 | Retest on dev: branch manager assigns a pickup (#60), collected pickup becomes BOOKED, Excel rate import screen, per-service rates end to end | all | S | `[ ]` |
| 7.2 | Rate import: accept the service type **name** as well as the code | CS | S | `[ ]` |
| 7.3 | Warn when no slab covers the card's minimum chargeable weight | CS, CW | S | `[ ]` |
| 7.4 | Remove the old all-services "OCCPL Standard" test rate if the owner wants only the four services | dev data | S | `[ ]` |
| 7.5 | Status badge "Awaiting pickup" lags the tracker | EC | S | `[ ]` |
| 7.6 | Clean dev test data: pickups PU-20261030-0004..0012 and their shipments, test users, the 411016 pincode mapping | dev data | S | `[ ]` |
| 7.7 | **Security:** rotate the passwords pasted in chat (staff, e-commerce login, the AWS key id); change or delete the two test users whose passwords were reset | owner | S | `[ ]` |
| 7.8 | Stop `.env.bak` / `docker-compose.yml.bak` lingering in `/opt/occpl`; merge the infra PR for `claude/courier-s3-env` | infra | S | `[ ]` |

## Phase 8 — Forward to a courier partner from the hub `[ ]` **next**

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

## Phase 9 — Customer quick wins `[ ]`

| # | Item | Repos | Size |
|---|---|---|---|
| 9.1 | Print **all labels** of a bulk upload in one go; download the bulk result as Excel | EC | S |
| 9.2 | **Cancel** a booking before pickup (refund to wallet, release the shipment number) | CS, EC | M |
| 9.3 | **Reschedule** the pickup (date and slot) | CS, EC | M |
| 9.4 | Wallet statement export; shipments list export | EC | S |

## Phase 10 — Notifications and delivery exceptions `[ ]`

| # | Item | Repos | Size |
|---|---|---|---|
| 10.1 | SMS / WhatsApp / email to the customer at booked, picked up, out for delivery, delivered (reuse Communication Center) | CS | M |
| 10.2 | Delivery failed / undelivered: reason, the customer's choice (re-attempt, change address, return), staff queue | CS, CW, EC | L |

## Phase 11 — Money `[ ]`

| # | Item | Repos | Size |
|---|---|---|---|
| 11.1 | Proof of delivery download for the customer | CS, EC | S |
| 11.2 | Credit customers: ledger, invoices and statement in the portal (billing cycle already exists in staff) | CS, EC | M |
| 11.3 | **COD remittance:** collected amount, settlement status and date per shipment; remittance statement | CS, CW, EC | L |
| 11.4 | **Commission** for portal shipments (currently zero) and branch earnings on them; review credit-customer billing with the new flow | CS | M |
| 11.5 | Per-customer markup screen for external couriers (backend rules exist) | CW | M |
| 11.6 | Rate card extras: minimum freight field, tiered slab pricing (optional) | CS, CW | M |

## Phase 12 — Returns, reverse pickup, reports `[ ]`

| # | Item | Repos | Size |
|---|---|---|---|
| 12.1 | Return / RTO shipments and reverse pickup from the buyer | CS, CW, EC | L |
| 12.2 | Customer dashboard: spend, shipments by status, delays, courier split | CS, EC | M |

## Phase 13 — Integrations and access control `[ ]`

| # | Item | Repos | Size |
|---|---|---|---|
| 13.1 | **Enforce courier access at booking** (today it only hides couriers from the list) | CS | S |
| 13.2 | Branch-level courier assignment (customers are not linked to branches yet) | CS, CW | M |
| 13.3 | Connect the customer's shop (Shopify / WooCommerce) or an API key so orders arrive without re-typing | CS, EC | L |
| 13.4 | Filter the staff pickup list by branch for admins; branch dashboards | CW | S |

## Decisions and actions waiting on the owner

| Item | Needed |
|---|---|
| Shipyaari returns HTTP 500 | Activate a plan on the Shipyaari account |
| Company logo / favicon uploads vs the private S3 bucket | Choose: store in the database, or a public folder |
| Real rates for customers | Send the numbers or the filled Excel; load through the import |
| Which phase next | Recommended: phase 8, then 9 |

## Known behaviours to remember

- A portal shipment is created only if the pickup pincode is mapped to an active, booking-enabled branch (not a pickup partner) and the company has an active payment mode (paid, To Pay, COD, credit), service type and package type; otherwise the booking falls back to a plain pickup that the branch books later.
- The company's 15 kg counter minimum does not apply to portal shipments: the quoted chargeable weight is kept.
- Dev service type codes are AIR, CRG, PRM, SRF; the portal option is named from the type name ("OCCPL SURFACE").
- Migration numbers: always fetch `main` and take the next free one. The per-service-type migration had to move from V149 to V151 because another PR took V149 and V150.
