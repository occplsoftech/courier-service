# Zone Tariff (company rate cards by zone)

**Status:** Phase 1 (V138): zones, zone locations, pincode → zone lookup. Phase 2 (V139): rate cards, weight slabs, charge
rules, GST, quote endpoint. Phase 3 (no migration): counter booking and the customer portal price from it. Screens: courier-web
`features/zone-tariff` (zones, rate cards, calculator) and the booking screen's Package Type + preview inputs; menu rows V140.
Excel import of the cash-booking sheet: `TariffRateImportService` (`POST /{id}/import/preview`, `POST /{id}/import`). Package `com.courier.modules.tariff`. Permission module `TARIFF` (MASTER shape).

## Why a new module
OCCPL Courier's cash-booking tariff (`CASH_BOOKING_RATES_2.xlsx`) prices by **from zone × to zone × mode (service type) × product
(package type) × weight slab**. None of the existing pricing fits:
- District Level Freight (what booking uses today): branch × destination district, per-kg slabs only; no mode, no product, no zones.
- Rate Master: per branch-to-branch route; no zone groups.
- Customer pricing zones (`customerpricing`): zones belong to one business customer, districts/cities only.

## Phase 1: zones (V138)
- `tariff_zones`: `code` (unique per company among live rows, upper-cased, spaces → `_`), `name`, `zone_usage` (`FROM`, `TO`,
  `BOTH`), `description`, `display_order`, `status`.
- `tariff_zone_locations`: `scope` (`STATE`, `DISTRICT`, `CITY`, `PINCODE`) plus the ids above the scope (a CITY row also stores its
  district and state). Geography ids are validated against the global masters (no physical FK).
- **One zone per place per direction.** Two zones whose usages overlap (TO/BOTH, or FROM/BOTH) cannot hold the same place at the
  same scope. A FROM-only and a TO-only zone may share a place. A city and its state in different zones is not a clash: that is
  how specificity works. Widening a zone's usage re-checks its places.
- **Most specific wins** when resolving a pincode: pincode, city, district, state (`LocationScope.rank`). Inactive zones are skipped;
  only zones of the asked direction answer. Pincode → city/district via `PincodeService.location`, state via the district.
- All locations of a request are validated before anything is saved (all or nothing), so no dirty row is flushed while the
  global geography masters are read under the platform company (the gotcha recorded in `business-customer.md`).

## API (`/api/v1/tariff-zones`)
`POST` (with optional locations), `GET` (filters usage/status/search; locations counted, not listed), `GET /resolve?pincode=&direction=TO|FROM`,
`GET/PUT/DELETE /{id}`, `POST /{id}/locations` (list), `DELETE /{id}/locations/{locationId}`, `PATCH /{id}/activate|deactivate`.
Writes `COMPANY_ADMIN` (on the service); reads any signed-in user. Audit: `TARIFF_ZONE_*`.

## Phase 2: rate cards (V139)
- **No branch → zone mapping table.** The pickup pincode (branch or customer address) is resolved FROM, the delivery pincode TO.
  Pune branch's pincode sits in the PUNE zone, so it picks the PUNE card.
- `tariff_rate_cards`: code, name, `from_zone_id` (must be FROM/BOTH), `effective_from`/`effective_to` (inclusive, null open),
  `gst_percent` (18), `volumetric_divisor` (null = actual weight only), `total_rounding` (NONE / NEAREST_RUPEE / UP_RUPEE),
  status. **Starts INACTIVE.** Activating needs at least one slab and no other ACTIVE card of the same from zone on any of the
  same dates (also checked when an active card is edited).
- `tariff_rate_slabs`: one weight band of a **lane** = to zone (TO/BOTH) × service type × package type. `FIXED`, or
  `PER_EXTRA_KG` with `step_kg` (default 1). A lane is always replaced whole (`PUT /lanes`) and validated whole: lightest slab
  FIXED, no overlap, no gap above 0.001 kg.
- **Freight rule (user-confirmed 2026-10-27):** the first slab whose upper weight reaches the chargeable weight. FIXED → its rate.
  PER_EXTRA_KG → rate of the last fixed slab before it + `rate` × ceil((weight − that slab's upper weight) / step). Same for every
  service type. The sheet's Premium rows say "Addition 500GM"; the user said per extra kg for all, so the import should use
  step 1 unless told otherwise (step is per slab, so 0.5 stays possible).
- Chargeable weight: max(actual, volumetric), volumetric = L×W×H×pieces / divisor, skipped for `PackageType.documentType`; rounded
  up to the gram.
- `tariff_charge_rules`: code (FUEL, HAMALI, DOCKET, COD, FOV, ODA…), name, optional to zone / service type / package type filters,
  `applies_when` ALWAYS / COD_ONLY / ODA_ONLY (ODA from the pincode master), `calc_method` FLAT / PERCENT_OF_FREIGHT / PER_KG
  (whole kg, rounded up) / PER_PIECE / PERCENT_OF_VALUE, min, max, `taxable`, order, status. Per code the **most specific** match
  wins (to zone 4 > service 2 > package 1; a COD/ODA rule beats ALWAYS on a tie). Same code + same filters + same condition
  is refused. Fuel is % of freight only.
- GST on freight + taxable charges: CGST + SGST (half each) when the pickup and delivery states are the same, else IGST.
  Then non-taxable charges, then rounding (shown as round-off).
- Arithmetic lives in `domain.TariffPriceCalculator` (pure). `TariffQuoteServiceImpl` resolves zones, refuses a non-serviceable
  delivery pincode and COD where the pincode has none, picks the card for the booking date (today, Asia/Kolkata) and the lane.
- A zone used by a card (from) or by slabs/charges (to) cannot be deleted, nor narrowed out of that direction.
- API `/api/v1/tariff-rate-cards`: CRUD, `PATCH activate|deactivate`, `PUT /{id}/lanes`, `DELETE /{id}/lanes?toZoneId&serviceTypeId&packageTypeId`,
  `POST/PUT/DELETE /{id}/charges[/{chargeId}]`, `POST /quote`, `POST /quote/options` (every lane — documents and parcels —
  to the delivery zone, cheapest first, too-heavy lanes under `unavailable`; the courier-web calculator, laid out like the
  e-commerce portal's rate calculator, uses it). Same `TARIFF` permission module; audit `TARIFF_RATE_*`, `TARIFF_CHARGE_*`.

## Phase 3: booking and portal (no migration)
- **Counter booking** goes through `FreightCalculationService` as before. New 7-arg `calculate(..., TariffInput)`
  (service type, package type, pieces, booking date); the old 6-arg one passes null and never consults the tariff.
  Order: Business Customer zone → **zone tariff** → District Level Freight.
- The tariff applies when the **booking branch's postal code** (`BranchLookupPort.findPostalCode`) is in a FROM zone with an
  ACTIVE card on the booking date (`TariffQuoteService.quoteIfApplies`). Then it is authoritative: no to zone or no lane is an
  error, never a silent fall-through to District Level Freight. A company with no tariff zones is unaffected.
- **Counter booking had stopped sending `packageTypeId`** (the field became optional when Route/Rate pricing stopped), so the
  tariff could not price a create until courier-web offered Package Type again (courier-web zone tariff PR).
- Shape (`FreightCalculationServiceImpl.fromTariff`): `baseFreight` = tariff freight, `odaCharge` 0, all the card's charges (ODA
  included) summed into `customerExtraCharges` with `customerZoneName` = "Zone tariff CODE: FROM → TO", so the existing
  "Other Charges must be at least …" rule makes the clerk carry them. `ratePerKg` = freight ÷ weight, so a raised Rate/KG override
  still works. **Booking's GST (branch %) and rounding apply, not the card's**; tariff COD-only charges never apply at the counter
  (booking passes cod=false); the tariff's volumetric is not used (booking passes its own chargeable weight).
- Port `districtfreight.domain.ZoneTariffPort`, adapter `tariff.infrastructure.DistrictFreightZoneTariffAdapter`.
- `POST /district-level-freight/calculate` (the booking screen's live preview) takes optional `serviceTypeId`, `packageTypeId`,
  `numberOfPackages`, `bookingDate`. **courier-web must send them**, otherwise the preview shows District Level Freight while the
  booking itself prices from the tariff.
- **Customer portal**: `OccplNetworkAdapter` keeps the customer's own zone rate first; with none it calls
  `TariffQuoteService.quoteOptions(..., documents=false)` from the pickup pincode: one option per parcel service type with a lane to
  the delivery zone, cheapest first, code `OCCPL_<SERVICE>`, ETA = `ServiceType.deliveryDays`. Lines: freight, each charge
  (FUEL → fuel, COD, ODA → remote area, FOV → insurance, docket/hamali → handling, else other) and the card's GST as TAX (so the
  portal adds no GST of its own). Round-off left out. Booking it still creates a pickup request; the service code goes in the remarks.

## Setup the company does (Step 1, no code)
Service Type master: `SURFACE`, `AIR`, `PREMIUM`, `CARGO`. Package Type master: `DOC`, `NONDOC`. Phase 2 rate lines point at these.

## Geography and OCCPL's zone places (V149, V150 — 2026-10-30)
- V142/V143 add places by name and skip what the global masters lack. They ran when only Maharashtra was loaded (V141), so
  NORTH, SOUTH, KERALA, EAST, WEST, CENTRAL, NORTH_EAST were empty and METRO held Mumbai's city only: no Pune lane outside
  Maharashtra could be priced.
- V149 loads every other state (India Post directory). V150 then adds those zones' states, and METRO's metros as **districts**
  (Delhi's every district, Mumbai, Kolkata, Chennai, Bangalore, Hyderabad): the directory files a metro's pincodes under many
  talukas, so city places would miss most of them. District beats state, so METRO wins over SOUTH/EAST/NORTH.
- **V152 (2026-10-30): PUNE, EXPUNE, METRO as in the previous software** (its "Edit Domestic Zone" screens, pasted by the
  user). METRO = New Delhi (all Delhi), Hyderabad, Bangalore, Chennai: Mumbai → MAHARASHTRA, Kolkata → EAST. PUNE = Pune City
  taluka (holds Pimpri, Chinchwad) + pincodes 411057 (Wakad/Hinjewadi), 411061 (Pimple Gurav). EXPUNE = Maval taluka + town pincodes
  410501 Chakan, 412105 Alandi, 412101 Dehu Road, 412115 Pirangut, 412201 Loni Kalbhor, 412208 Talegaon Dhamdhere, 412209/412220
  Ranjangaon, 412308 Fursungi, 411025 Khadakwasla; the rest of Pune district is MAHARASHTRA (same rates as EXPUNE). CENTRAL and EAST
  checked against the old lists: whole states, no change.
- Still no zone: Andaman & Nicobar, Dadra & Nagar Haveli, Daman & Diu is WEST, Lakshadweep none. EXMAHARASHTRA stays empty.

## Next
- Excel import (built 2026-10-28): header row found in the first 15 rows ("Admin" title row first); names matched ignoring case
  and punctuation against codes and names ("BY AIR" → AIR, "Document" → DOC by prefix, "EX PUNE" → EXPUNE); a row with State/City
  goes to the to zone named after it; other from zones skipped; identical repeats counted once, different rates for one band an
  error; "Per Kg" and "Addition 500GM" → PER_EXTRA_KG step 1 (warning on the 500 g rows). All or nothing, one transaction, lanes in
  the file replace those lanes via `TariffRateCardService.saveLane`; other lanes kept.
- **The real `CASH_BOOKING_RATES_2.xlsx`** (run through preview with zones named like the sheet): 1,000 rows, 810 repeats, 73
  lanes; 16 errors — rows 499–500 and 989–1002 are SOUTH with Kerala's rates and a blank State (fill State = KERALA); no Cargo
  EXMAHARASHTRA lane; 148 "Addtion 500GM" rows. The sheet ends at exactly 1,000 data rows, so it may be cut off.

## Verification
Unit tests only (`TariffZoneServiceImplTest` 22, `TariffZoneTest` 4, `TariffPriceCalculatorTest` 12, `TariffRateCardServiceImplTest` 11,
plus tariff cases in `FreightCalculationServiceImplTest` and `OccplNetworkAdapterTest`).
**V138 / V139 have not been run against MySQL**; CI runs unit tests only.
