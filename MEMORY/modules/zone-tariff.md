# Zone Tariff (company rate cards by zone)

**Status:** Phase 1 built 2026-10-27 (V138): zones, zone locations, pincode → zone lookup. Rate cards (Phase 2) and booking /
portal pricing (Phase 3) are not built yet. Package `com.courier.modules.tariff`. Permission module `TARIFF` (MASTER shape).

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

## Setup the company does (Step 1, no code)
Service Type master: `SURFACE`, `AIR`, `PREMIUM`, `CARGO`. Package Type master: `DOC`, `NONDOC`. Phase 2 rate lines point at these.

## Next
- Phase 2: branch → from zone, rate cards (from zone, category, effective dates, GST/fuel/docket), rate lines (to zone × service type ×
  package type × slab, `FIXED` / `PER_EXTRA_KG` / step), charge rules (fuel, hamali, COD, FOV…), Excel import of the sheet format.
  Deleting a zone must then be refused while a rate card uses it.
- Phase 3: price calculation, booking screen, customer portal OCCPL network options.
- courier-web screens for Phase 1 (zones list/detail, add locations, pincode test) and the sidebar entry.

## Verification
Unit tests only (`TariffZoneServiceImplTest` 21, `TariffZoneTest` 4). **V138 has not been run against MySQL**; CI runs unit tests only.
