# Zone Tariff (company rate cards by zone)

**Status:** Phase 1 (V138): zones, zone locations, pincode → zone lookup. Phase 2 (V139): rate cards, weight slabs, charge
rules, GST, quote endpoint. Not built yet: Excel import of the sheet, booking / customer portal pricing (Phase 3), screens. Package `com.courier.modules.tariff`. Permission module `TARIFF` (MASTER shape).

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
  `POST/PUT/DELETE /{id}/charges[/{chargeId}]`, `POST /quote`. Same `TARIFF` permission module; audit `TARIFF_RATE_*`, `TARIFF_CHARGE_*`.

## Setup the company does (Step 1, no code)
Service Type master: `SURFACE`, `AIR`, `PREMIUM`, `CARGO`. Package Type master: `DOC`, `NONDOC`. Phase 2 rate lines point at these.

## Next
- Excel import of the sheet format (cleaned CSV lives in the session scratchpad; the sheet has repeated rows, Kerala rows with a
  blank state = their own to zone, and no Cargo Ex-Maharashtra rate).
- Phase 3: price calculation, booking screen, customer portal OCCPL network options.
- courier-web screens for Phase 1 (zones list/detail, add locations, pincode test) and the sidebar entry.

## Verification
Unit tests only (`TariffZoneServiceImplTest` 22, `TariffZoneTest` 4, `TariffPriceCalculatorTest` 12, `TariffRateCardServiceImplTest` 9).
**V138 / V139 have not been run against MySQL**; CI runs unit tests only.
