# BACKLOG

Ordered. Top item is always the next thing to build.
`[ ]` todo · `[~]` in progress · `[x]` done

---

## Customer portal, booking and courier forwarding — phases 6-13

The ordered plan lives in [`modules/customer-portal-roadmap.md`](modules/customer-portal-roadmap.md) (phase 6 done; phase 7 in progress; **phase 8, forwarding a shipment to a courier partner from the hub, is next**).

---

## Phase 0 — Foundation  `[x]` (v0.1.0)

- [x] Maven project, Java 21, Spring Boot 3.4.1
- [x] Package structure (`shared` + `modules`)
- [x] `BaseEntity` / `CompanyOwnedEntity` — UUID PK, soft delete, versioning
- [x] `CompanyContext` + `CompanyResolutionFilter` + `CompanyEntityListener` + `CompanyFilterAspect`
- [x] JWT security (provider, filter, `SecurityConfig`)
- [x] `GlobalExceptionHandler` + `ErrorCode` taxonomy
- [x] `ApiResponse` / `PageResponse` wrappers
- [x] Audit support (JPA auditing + `audit_logs` + `AuditService`)
- [x] Redis config (Lettuce, JSON serializer, cache manager)
- [x] Flyway config + `V1__baseline.sql`
- [x] Swagger / OpenAPI 3 config with bearer auth
- [x] Dockerfile (multi-stage) + `docker-compose.yml`

---

## Phase 2 — Auth  `[x]` (v0.2.0) — built ahead of the company module, on request

- [x] `User` entity (company-owned) + `Role` enum + BCrypt(12) encoder
- [x] `V2__auth.sql` — `users`, `user_roles`, `refresh_tokens`, `user_sessions`,
      `login_history`, `password_reset_tokens`, `email_verification_tokens`
- [x] `POST /auth/login` — companyId-scoped, throttled, lock-aware
- [x] `POST /auth/refresh` — rotation + family reuse detection
- [x] `POST /auth/logout` — single device and `allDevices`
- [x] `POST /auth/forgot-password` / `reset-password` / `change-password`
- [x] `POST /auth/verify-email`, `GET /auth/me`
- [x] Account lock after 5 failures; auto-unlock; reset clears the lock
- [x] Password policy + optional expiry
- [x] Remember Me (30d refresh), session cap, device tracking
- [x] Token revocation: Redis denylist behind a `shared` SPI
- [x] Custom `UserDetails` / `UserDetailsService` / provider / manager
- [x] Service-layer unit tests
- [ ] `POST /auth/register` — **intentionally absent**, belongs to Phase 1 onboarding

## Phase 2 — Super Admin: Subscription Plan  `[x]` (v0.3.0)

- [x] `SUPER_ADMIN` role — new top tier, distinct from `PLATFORM_ADMIN`; wired into
      `Roles`, auth's `Role` enum, `AuthenticatedUser`, `CompanyResolutionFilter`
- [x] `SubscriptionPlan` entity (platform-level: extends `BaseEntity`, no `tenant_id`)
- [x] `PlanType` — TRIAL | BASIC | STANDARD | PREMIUM | ENTERPRISE
- [x] `V3__subscription.sql` — `subscription_plans`, 2 unique keys, 3 indexes, 3 CHECKs
- [x] Repository + `JpaSpecificationExecutor`; native uniqueness checks that see
      soft-deleted rows
- [x] `SubscriptionPlanSpecifications` — filter, search, LIKE escaping
- [x] Service interface + implementation, class-level `@PreAuthorize(SUPER_ADMIN)`
- [x] 7 endpoints: create, update, get, list, activate, deactivate, soft delete
- [x] Pagination, whitelisted sorting, filtering, searching; `size` capped at 100
- [x] Business rules: unique code/name, no negative price, free TRIAL, unlimited
      ENTERPRISE, soft delete only, client-supplied-version optimistic locking
- [x] Audit events for every write, `_UPDATED` recording changed fields only
- [x] 40 unit tests (domain, service, mapper, roles)
- [x] Booted on MySQL 8.0.46: `V3` applied, `ddl-auto: validate` passed, all seven
      endpoints exercised. Fixed two runtime-only defects — native `enum` mapping for
      `plan_type`, and `SELECT COUNT(*) > 0` returning `BIGINT` into a `boolean`
- [ ] Refuse to delete a plan that has subscribers — now possible:
      `CompanyRepository.countBySubscriptionPlanId` exists and is unused
- [ ] Method-security integration slice: 403 for `PLATFORM_ADMIN`, 200 for `SUPER_ADMIN`

## Phase 2 — Super Admin: Company  `[x]` (v0.4.0)

> Replaced the planned **Phase 1 — Tenant**: a company *is* the owner, and branches sit
> under a company. `MEMORY/modules/company.md` is now a redirect stub.

- [x] `Company` entity — the company root (`BaseEntity`, separate `id` and `companyId`)
- [x] `CompanyStatus` TRIAL | ACTIVE | INACTIVE | SUSPENDED | EXPIRED, transitions in the enum
- [x] `V4__company.sql` — `companies`, `company_roles`, `company_role_permissions`,
      `company_settings`; 5 unique keys, FK to `subscription_plans` (RESTRICT)
- [x] Repository + Specification filtering, searching, LIKE escaping
- [x] Service + impl, class-level `@PreAuthorize(SUPER_ADMIN)`
- [x] 10 endpoints: create, update, get, list, activate, suspend, expire, delete,
      roles, settings
- [x] Initialization: generated companyId, plan link, 5 roles with plan-gated
      permissions, ~24 settings, `PENDING` company admin
- [x] `UserProvisioningService` in `auth` — the seam for creating that admin
- [x] `Role` enum + `Roles` gained COMPANY_ADMIN, HUB_MANAGER, VIEWER
- [x] `CompanyEvent` sealed + `AFTER_COMMIT` listener (created/updated/activated/
      suspended/expired)
- [x] `CompanyDirectory` — displaces `StandaloneCompanyDirectory`, enforces status,
      **resolves `LoginRequest.companyCode`**
- [x] 50 unit tests; booted, `V4` applied, all endpoints exercised end to end
- [ ] **FK `users.tenant_id -> companies.tenant_id`** — deferred: the dev database holds
      one orphan user row (`ops@acme.test`). Delete or re-point it, then ship the
      constraint as `V5`
- [ ] `CompanyStatusGuard` — reject non-operational companies on *every* company-scoped
      request, not only at login (today an issued token works for up to 15 more minutes)
- [ ] Scheduled expiry job for companies past `trialEndDate` / `subscriptionEndDate`
- [ ] Self-serve company registration (public, rate limited)
- [ ] `GET /companies/me` for `COMPANY_ADMIN`, plus company-side role customisation
- [ ] Method-security integration slice: 403 for `PLATFORM_ADMIN`, 200 for `SUPER_ADMIN`

## Phase 3 — Company Administration: Role Management  `[x]` (v0.5.0)

> Extends the `company_roles` table `V4` created — not a new table. First module a
> company's own admin can use. See `MEMORY/modules/role.md`.

- [x] `RoleType` (ADMINISTRATION | OPERATIONS | FINANCE | SUPPORT | READ_ONLY) and
      `RoleStatus` (ACTIVE | INACTIVE) replacing the boolean `is_active`
- [x] `CompanyRole` gained `roleType`, `isDefault`, `status` + code normalisation
- [x] `V5__role_management.sql` — ALTER + data carry-over + index rebuild, `OPERATOR`
      renamed to `BOOKING_OPERATOR` in place, three new roles backfilled for existing
      companies with permissions and plan-gated `BULK_BOOKING`
- [x] Seeded catalogue 5 -> 8 roles (booking/delivery split, finance, customer service)
- [x] Repository + Specification: filter by status, type, system/default, permission
- [x] Service with **per-method** `@PreAuthorize`: COMPANY_ADMIN writes, SUPER_ADMIN reads
- [x] 8 endpoints incl. `/assignable`, activate, deactivate, soft delete
- [x] Rules: per-company unique code and name, system roles undeletable, default role
      protected, at most one default, optimistic locking
- [x] 35 unit tests; booted, `V5` applied against V4-shaped data, cross-company isolation
      verified (404 on every verb, no leak in search, spoofed companyId overridden)
- [x] Permission Management delivered separately (v0.6.0, `MEMORY/modules/permission.md`)
- [ ] Authorise on permissions rather than JWT role names — deferred to after User Management
- [ ] Method-security integration slice for the read/write split

## Phase 3 — Company Administration: Permission Management  `[x]` (v0.6.0)

> Permissions became a table. See `MEMORY/modules/permission.md`.

- [x] `Permission` entity (platform-level) replacing the 30-constant enum
- [x] `PermissionModule` (28) + `PermissionAction` (15) + `PermissionStatus`
- [x] `DefaultPermissionCatalog` — 174 rights, not the 420 cross product
- [x] `RolePermission` join entity replacing the `company_role_permissions` collection
- [x] `V6__permission_management.sql` — 2 tables, 174 seeded rows, grant migration, drop
- [x] Catalogue CRUD (`SUPER_ADMIN` writes, both tiers read); system rows read-only;
      still-granted permission undeletable
- [x] Bulk grant/revoke to a company's roles (`COMPANY_ADMIN`, own company only)
- [x] Plan gating from the seeded `feature.*` settings (fails closed)
- [x] Lockout guard: cannot revoke the company's last `ROLE_UPDATE` (before/after aware)
- [x] 38 unit tests; booted, `V6` applied against real fixtures, cross-company verified
- [ ] Authorise `@PreAuthorize` on permission codes — after User Management

## Phase 3 — Company Administration: User Management  `[x]` (v0.7.0)

> The `users` table became a shared kernel — see `MEMORY/modules/user.md`.

- [x] `company.User` entity over the shared `users` table (`@Entity "CompanyUser"`),
      `UserStatus`, `Gender`
- [x] `UserRole` join (`user_company_roles`) to `company_roles`, distinct from auth's enum
- [x] `V7__user_management.sql` — ALTER users + new join table + unique keys/indexes
- [x] `CompanyUserRepository` + Specification; `UserRoleRepository`; criteria/specs
- [x] Service with per-method `@PreAuthorize`; reuses auth's `PasswordPolicy`
- [x] 15 endpoints: CRUD, activate/deactivate/lock/unlock, reset + self change-password,
      assign/remove role, assign branch/hub
- [x] Rules: email per-company / username global / employee-code per-company uniqueness,
      one branch + one hub, no self-report, self-guards, soft delete, optimistic locking
- [x] Branch/hub manager scoped reads; SUPER_ADMIN read-all
- [x] `UserEvent` sealed + AFTER_COMMIT listener (11 events)
- [x] 35 unit tests; booted, `V7` applied, cross-company + branch-scoping verified
- [ ] Bulk user import (spec: "future ready")

## Phase 3 — Company Administration: Company Settings  `[x]` (v0.8.0)

> New wide table alongside the key/value one. See `MEMORY/modules/company-settings.md`.

- [x] `CompanySettings` entity (wide, `company_settings_config`), one row per company,
      no soft delete; enums WeightUnit/DimensionUnit/ThemePreference
- [x] `V8__company_settings_config.sql` — ~50 columns, defaults, GST 0–100 CHECK
- [x] Get-or-create seeded from the company; merge-not-blank writes
- [x] 8 endpoints: GET, full PUT (version-checked), 6 section PATCHes
- [x] Reads any company user; writes COMPANY_ADMIN; super-admin no-company refused
- [x] Repository + Specification (for a future super-admin report); mapper; validation
- [x] 11 unit tests; booted, V8 applied, cross-company clean, all sections exercised
- [ ] Consume in Shipment/Finance/etc; make auth read the security section (company-owned)

## Phase 4 — Organization Structure: Branch Management  `[x]` (v0.9.0)

> First Phase-4 module. See `MEMORY/modules/branch.md`.

- [x] `Branch` entity (`branches`, V9), company-owned; BranchType (5), BranchStatus
- [x] Code/name unique per company; geo + hours + working-days + 6 capability flags
- [x] Repository + Specification (filters + branchIds scope); mapper; validation
- [x] Service per-method `@PreAuthorize` + in-code branch-level scoping
- [x] 9 endpoints: CRUD, activate/deactivate, assign-manager, assign-users
- [x] RBAC: COMPANY_ADMIN all; BRANCH_MANAGER own; users read own; SUPER_ADMIN read-all
- [x] `BranchEvent` sealed + AFTER_COMMIT listener
- [x] 20 unit tests; booted, V9 applied, branch-manager scoping + cross-company verified
- [ ] `users.branch_id` / `branches.manager_id` FKs once dev orphan rows reconciled
- [ ] Enforce one HEAD_OFFICE per company if the product needs it

## Phase 5 — Finance: Branch Wallet  `[x]` (v0.10.0)

> New module `com.courier.modules.finance`. See `MEMORY/modules/branch-wallet.md`.

- [x] `Wallet` (`wallets`, V10) — one per branch, no balance setter; two balances
      (available + hold), `DECIMAL(19,4)`, `WalletStatus`
- [x] `WalletTransaction` (`wallet_transactions`, V10) — append-only ledger, denormalised
      `balanceBefore`/`balanceAfter`, every column but `paymentStatus` immutable
- [x] Enums: `TransactionType` (CR/DR), `SubTransactionType` (12 codes, each carrying its
      allowed direction), `ReferenceType`, `PaymentStatus`, `WalletStatus`
- [x] `V10__branch_wallet.sql` — 2 tables, FKs to `branches`/`wallets`, non-negative and
      positive-amount CHECKs, the indexes on wallet number / branch / transaction no /
      created_at, and a **globally** unique `payment_reference`
- [x] Repositories incl. pessimistic `lockByBranchIdWithinCompany`; Specification that fails
      closed without a wallet scope; mapper; validation
- [x] Service with per-method `@PreAuthorize` + in-code branch scoping (404 read / 403 write)
- [x] 7 endpoints: get, summary, transactions, **recharge/order**, recharge, credit, debit
- [x] Auto-provisioning from `BranchCreated` + idempotent `getOrCreateForBranch` backstop
- [x] `PaymentGatewayPort` + Razorpay adapter (REST, no SDK) + fail-closed default
- [x] Recharge safety: server-fixed amount, signature verification, gateway-authoritative
      amount, capture/order/currency checks, idempotent on the payment id
- [x] `WalletEvent` sealed + AFTER_COMMIT listener; 5 new audit actions
- [x] 53 unit tests, all green
- [x] Booted on MySQL 8.0.46 — V10 applied, `validate` passed, enums stored as VARCHAR, both
      provisioning paths fired, ledger chained exactly, all refusals returned the right status
- [ ] **Cross-company runtime check** — blocked: `RIVAL_CO` has no active user and no branch.
      Provision both, then confirm 404 on every wallet verb and no statement leak
- [ ] `holdBalance` has no writer yet — `applyHold`/`releaseHold` land with Shipment
- [x] Internal booking-debit seam for Shipment (not `COMPANY_ADMIN`-gated) —
      `WalletService.debitForBooking(BookingDebitCommand)`, built with Shipment Booking
- [x] Razorpay webhook (`payment.captured`) so a closed browser still settles — `RazorpayWebhookController`
      + `WalletService.settleFromWebhook`, `RAZORPAY_WEBHOOK_SECRET` (2026-09-02). Platform gateway
      only; a company's own `CompanyRazorpayConfig` account has no webhook secret field yet, so its
      webhook events fail signature verification silently (ack'd 200, nothing settled) — same gap as
      before for that path only
- [ ] Refund/reversal path; low-balance threshold + alert on `WalletDebited`
- [ ] Realign the UI-11 frontend, which was built against a guessed contract
      (`/branch-wallets/{id}`, `CREDIT`/`DEBIT`, long sub-type names)

## Phase 6 — Master Data  `[x]`  DONE (v0.11.0)

> Twelve reference lists, migration **`V11`**, package `com.courier.modules.master`.
> See `MEMORY/modules/master-data.md`.

- [x] Geography hierarchy: country -> state -> district -> city -> area -> pincode
- [x] Catalogues: vehicle type, package type, service type, payment mode, weight slab
- [x] Route master (booking branch -> delivery branch, distance, transit days)
- [x] 85 endpoints, one shared head + one abstract service for all twelve
- [x] Angular: four config-driven components serve all twelve lists (UI-12)
- [x] `MASTER_DATA` permission module; catalogue 174 -> 187
- [ ] Bulk import (`MASTER_DATA_IMPORT` is seeded; the endpoint is not)
- [ ] **Cross-company leak test over HTTP** — blocked on an active `RIVAL_CO` user

### Route Management extension  `[x]`  DONE (v0.13.1, `V15`)

> A brief asked for a standalone Route Management module; `master_routes` already
> covered the domain, so this extends it instead of duplicating it — asked directly,
> user's choice. No new table, package, endpoint or permission code. See
> `MEMORY/modules/master-data.md` §"Route Management (2026-07-30 extension)".

- [x] `transit_hours` (`[0, 23]`, remainder on top of `transit_days`)
- [x] `distance_unit` (new `DistanceUnit` enum, one constant `KM` today)
- [x] Wired through entity/DTOs/mapper/service/audit snapshot + Angular masters screens
- [x] Verified live over HTTP and through the Angular console; backend 544/544,
      frontend 82/82 (a table-format regression the new work introduced was caught by
      the existing `master-table.spec.ts` before verification)

## Phase 7 — Customer Management  `[x]`  DONE (v0.13.0)

> New package `com.courier.modules.customer`, migration **`V14`**. Pulled forward ahead
> of Hub/Rate Master by explicit request; permission rows already existed (seeded in
> `V6`), so this shipped with no permission-catalogue migration. See
> `MEMORY/modules/customer.md`.

- [x] `Customer` (reusable master data) + `CustomerAddress` (child, own table, real FK)
- [x] `V14__customer_management.sql` — `customers`, `customer_addresses`
- [x] 9 endpoints: CRUD-ish (no `DELETE /customers/{id}` — not in the spec), lifecycle,
      3 address endpoints nested under a customer
- [x] Business rules: mobile unique per company (not reserved past soft delete), GST
      mandatory only for `BUSINESS`, at most one default-pickup / default-delivery address
      per customer (auto-exclusive, not rejected), duplicate address refused
- [x] Geography ids on an address validated against the **global** masters
      (`com.courier.modules.master`'s own service interfaces — cross-feature dependency,
      not a port, since the arrow points forward with no cycle)
- [x] Angular: list/create/edit/view + an address book with a cascading geography picker
      dialog (country -> state -> district -> city -> area -> pincode)
- [x] 19 backend unit tests, 12 new frontend tests; verified over real HTTP (create,
      duplicate mobile 409, GST-missing 422, default-address exclusivity, duplicate
      address 422, foreign id 404, `SUPER_ADMIN` refused 403) and through the Angular
      console, including a real bug the browser check caught (address dialog had no
      internal scroll region and was unreachable past viewport height — fixed)

## Phase 4 — Organization Structure: Rate Master  `[x]`  DONE (v0.14.0, `V16`)

> New package `com.courier.modules.rate`. One row prices one weight slab for one
> Route + Service Type + Package Type + Payment Mode combination. See
> `MEMORY/modules/rate-master.md`.

- [x] `Rate` entity + `rate_master` table (`V16`), own `WeightUnit` enum
- [x] 7 endpoints: create, full-replacement update, get, paged/sorted/filtered/searched
      list, activate, deactivate, `POST /rates/calculate`. No `DELETE` (spec's fields
      listed `RATE_DELETE` seeded-but-unused, same pattern as `CUSTOMER_DELETE`)
- [x] Business rules: only an active Route may carry an active Rate; no two ACTIVE rates
      for the same combination may overlap weight, `[min, max)`, checked on
      create/update/activate
- [x] `RouteService.findByBranches` added to the already-shipped Route Management module
      — Rate Calculation is handed a branch pair, not a route id
- [x] New `PermissionAction.CALCULATE`; `RATE_MASTER` gains it plus `ACTIVATE`/
      `DEACTIVATE`; catalogue 219 → 222
- [x] Angular: list/create/edit/view + Rate Calculator (page and dialog, one shared
      component), Weight Slab Grid (client-side overlap mirror) embedded in the form
- [x] 29 backend unit tests, 16 frontend tests; verified over real HTTP (CRUD, overlap,
      inactive-route, duplicate-code, exact/overage/gap calculation, RBAC) and through
      the Angular console — a migration column-name bug and two message-formatting bugs
      were caught only by the live run, not `mvn test`
- [ ] Not exercised: a `BRANCH_MANAGER`/`BOOKING_OPERATOR`-scoped token calling the
      calculator (no such user in the dev fixtures), the `RIVAL_CO` cross-company check

## Pricing Engine  `[x]`  DONE (v0.15.0, no migration)

> New package `com.courier.modules.pricing`, no table, no persistence. Reusable
> Strategy+Factory service Shipment Booking/Quotation/the mobile app/future integrations
> call to price a shipment — a superset of Rate Master's own `POST /rates/calculate`
> (adds volumetric weight, serviceability, configurable charge toggles). See
> `MEMORY/modules/pricing-engine.md`.

- [x] `PricingEngine`/`PricingEngineImpl`, `PricingContext`, `PricingCommand`/`PricingResult`
- [x] `PricingStrategy`/`StandardPricingStrategy` (Strategy) + `PricingFactory`/
      `PricingFactoryImpl` (Factory)
- [x] `ChargeCalculator` Strategy, 8 implementations: Freight (slab match, ported from
      `RateServiceImpl.calculate`), Fuel, Handling, ODA, Insurance, GST, Discount, Round Off
- [x] Weight module: `WeightCalculator`, `VolumetricCalculator`, `ChargeableWeightCalculator`
      — `chargeableWeight = MAX(actual, volumetric)`
- [x] Validation module: `RouteValidation`, `RateValidation`, `WeightValidation`,
      `BookingValidation` (folds in pincode serviceability)
- [x] Two small seams on shipped modules: `RateService.findActiveCandidates`,
      `PincodeService.findByCode`
- [x] `PricingProperties` (`pricing.*`): volumetric divisor, Fuel/ODA/Insurance/Discount
      toggles, rounding rule
- [x] `POST /api/v1/pricing/calculate`, `isAuthenticated()`, no new permission codes
- [x] 55 backend unit tests; `mvn test` 573 → 627; verified live over HTTP on a temporary
      instance (exact-slab and volumetric-dominant quotes cross-checked against Rate
      Master's own verified numbers, serviceability/weight/service-type refusals, a
      hand-checked discount, Swagger registration)
- [ ] No frontend — not asked for by this module's Definition of Done
- [ ] A genuine weight-slab gap no longer exists in the dev fixtures to exercise live
      (`RATE-UI-TEST` now fills the `GAP-LOW`/`GAP-HIGH` gap) — covered by a unit test instead
- [ ] Not exercised: a `BRANCH_MANAGER`/`BOOKING_OPERATOR`-scoped token, the `RIVAL_CO`
      cross-company check

## Shipment Booking  `[x]`  DONE (v0.16.0, `V17`)

> New package `com.courier.modules.shipment`. The core transaction: books only after
> Customer, Serviceability+Route+Pricing (one Pricing Engine call) and, for a PAID
> booking, the Branch Wallet have all agreed. See `MEMORY/modules/shipment-booking.md`.

- [x] 5 tables (`shipments`, `shipment_items`, `shipment_charges`,
      `shipment_status_history`, `shipment_documents`), all owned by this module
- [x] `ShipmentStatus` — full ten-state graph declared now (`canTransitionTo`,
      `isCancellable`), though this module only ever writes `BOOKED`/`CANCELLED`
- [x] AWB + shipment number generation: format generator + existence-check retry (5
      attempts), `UNIQUE (company_id, tracking_number)`/`(company_id, shipment_number)` as
      the backstop — no `MAX()+1`
- [x] Booking orchestrates, never re-decides: Customer/address ownership validated
      in-module (cross-module id, not cross-module logic), Pricing Engine's one call
      covers serviceability + route + rate + weight-slab, wallet checked pre-commit and
      debited AFTER_COMMIT via `WalletService.debitForBooking(BookingDebitCommand)`
- [x] Package-type weight ceiling, optimistic-lock update (re-prices, replaces the charge
      row), cancel refused once `DISPATCHED`+
- [x] 8 endpoints incl. `GET /shipments/track/{trackingNumber}` (not a second bare
      `/shipments/{x}` route — ambiguous with `/shipments/{id}`)
- [x] One new permission, `SHIPMENT_UPLOAD` (catalogue 222 → 223); RBAC still role-based
      (`COMPANY_ADMIN`/`BRANCH_MANAGER`/`OPERATOR` write, any authenticated reads) — the
      authorise-on-permissions capstone below is still the gap, not new to this module
- [x] Angular: 7 pages (list, wizard-based create, view, edit, charges, history,
      documents), reuses Customer/Master/Rate/Pricing frontend services rather than
      duplicating lookups; 111 frontend tests, `ng build` clean
- [x] Verified live over HTTP (TO_PAY and PAID bookings, insufficient-balance refusal,
      wallet debit after commit, cancel + double-cancel refusal, update + re-price +
      stale-version 409, document attach, three business-rule refusals) and through the
      Angular console end to end (all four wizard steps, detail/charges/history/
      documents pages, edit-and-save) — a real bug (`computed()` caching a value that
      read a plain `FormControl.value`, so "Continue" stayed disabled after every field
      was filled in) was caught only by the live run, not `ng build`/`ng test`
- [ ] Not exercised: `BRANCH_MANAGER`/`BOOKING_OPERATOR`-scoped tokens (the JWT-authority
      gap below), the `RIVAL_CO` cross-company check, a `DISPATCHED`+ cancel refusal over
      live HTTP (nothing yet transitions a shipment past `BOOKED` — Manifest Management)

## Shipment Movement  `[x]`  DONE (v0.17.0 → v0.17.1, `V19`+`V20`)

> New package `com.courier.modules.manifest` (the minimal Manifest prerequisite this
> module needed but nothing had built — confirmed with the user before starting),
> extends `com.courier.modules.shipment`. **v0.17.1** (same day, on direct request)
> folded `OUT_SCAN` back into `MANIFEST_CREATED` — four movement steps now (Dispatch/
> In Scan/Out For Delivery/Deliver), not five; creating a manifest already is "out
> scan created". See `MEMORY/modules/shipment-movement.md`.

- [x] `Manifest`/`Vehicle` (new tables), `DeliveryAssignment` (new table, current-state
      not ledger), `shipment_status_history` gains `branch_id`/`manifest_id`/`vehicle_id`
- [x] `ShipmentStatus` renamed to match the brief's vocabulary: `MANIFESTED` →
      `MANIFEST_CREATED`, `RECEIVED` → `IN_SCAN`, new `OUT_SCAN` state
- [x] 5 endpoints under `/api/v1/shipment-movement` (out-scan/dispatch/in-scan/
      out-for-delivery/deliver, bulk ones report per-item outcome) +
      `GET /shipments/{id}/timeline`
- [x] Business rules: OUT_SCAN only from MANIFEST_CREATED + no double-scan; DISPATCH
      needs ≥1 OUT_SCAN shipment; IN_SCAN's receiving branch must match delivery
      branch; DELIVER requires a receiver name
- [x] Permissions: reused the already-seeded `MANIFEST_DISPATCH`/`MANIFEST_RECEIVE`/
      `DELIVERY_DISPATCH`/`DELIVERY_DELIVER`/`TRACKING_CREATE` catalogue rows instead
      of adding six new ones — see the module doc's mapping table
- [x] One-directional `Manifest` → `Shipment` module dependency only, to avoid a
      Spring circular-bean startup failure the natural split would have caused
- [x] Angular: 6 pages exactly matching the brief (Out Scan folds in Create Manifest),
      nav's five aspirational leaves un-tagged and re-split by branch (booking desk:
      Out Scan/Dispatch; delivery desk: In Scan/Out For Delivery/Deliver) — corrects a
      prior guessed assumption in `navigation.config.spec.ts`, not just extends it
- [x] ~23 new backend unit tests (650 → 673, 660 pass — 13 pre-existing unrelated
      master-module failures, confirmed isolated), 7 new frontend tests (118 → 125,
      120 pass — 5 pre-existing unrelated failures, confirmed isolated)
- [x] Verified live over HTTP (full pipeline, Pune → Latur, every refusal exercised)
      and through the Angular console (Out Scan/Timeline/Delivery driven end to end)
- [ ] No standalone Manifest/Vehicle management UI (list/edit/deactivate screens) —
      not asked for by this module's own Frontend section, only what Out Scan/Dispatch
      need inline
- [ ] `driverUserId` accepts any company user, not a real "driver" role — no company
      role in `DefaultRoleCatalog` models one; would need its own decision, not made
      here
- [ ] `ManifestStatus.COMPLETED` declared, nothing writes it — no "close the
      manifest once everything has arrived" step existed in this module's scope
- [ ] `RETURNED` reachable in the `ShipmentStatus` graph from `IN_SCAN`/
      `OUT_FOR_DELIVERY` but no endpoint writes it — a future Returns module's job
- [ ] Not exercised: `BOOKING_OPERATOR`/`DELIVERY_OPERATOR`-scoped JWT tokens (the
      inherited "company role, not JWT authority" gap), the `RIVAL_CO` cross-company
      check, concurrent out-scan/dispatch under real load

## E-Way Bill Management  `[x]`  DONE (v0.30.0, `V47`)

> New package `com.courier.modules.ewaybill`, integrated inline into Shipment Booking's
> own `create()`/`update()` transaction (this codebase mints the AWB synchronously, with
> no separate later step to intercept). See `MEMORY/modules/eway-bill.md`.

- [x] `EwayBill` entity/repository, `EwayBillStatus` lifecycle (`CANCELLED` terminal, a
      cancelled row is reissued fresh, never reused)
- [x] Configurable `CompanySettings.ewayBillMandatoryValue` (default 50000.00), never
      hardcoded; `shipments.invoiceValue`/`ewayBillRequired` (frozen at booking time)
- [x] Booking-time gate: mandatory-and-missing/invalid blocks the whole transaction before
      AWB minting, with the brief's own exact refusal wording
- [x] `EwayBillProvider`/`LocalEwayBillProvider` — local-only validation seam, ready for a
      real government API to be swapped in later with no caller change
- [x] Standalone `POST/PUT/GET /eway-bills`, `.../validate`, `.../upload`, `.../cancel`
- [x] `PermissionModule.EWAY_BILL` (8 rights, catalogue 223 → 231); RBAC still role-based
      like every other module ahead of the "authorise on permissions" capstone below
- [x] Frontend: booking-flow E-Way Bill card + Booking Summary line (client-side check is
      UX only), Shipment Details E-Way Bill card + Validate/Upload/Cancel
- [x] 21 new backend unit tests (`mvn test` 791 → 813 at this task's own commit point;
      caught a real cancel-reissue bug in `upsertForShipment`), frontend `tsc`/`ng build`
      clean
- [ ] Not verified live — no MySQL boot or browser click-through this session
- [ ] No standalone E-Way Bill list/management page — not asked for by this module's own
      Frontend section
- [ ] External government/GST-network E-Way Bill API — deliberately not implemented, per
      the brief's own instruction; `EwayBillProvider` is the seam for it later

## POD Auto Verification  `[x]`  DONE (v0.30.1, `V48`+`V49`)

> New package `com.courier.modules.pod`. AI-scored gate in front of the existing
> `ShipmentServiceImpl.deliver()` — AI never itself moves a shipment to `DELIVERED`. See
> `MEMORY/modules/pod-verification.md`.

- [x] `PodVerification`/`PodVerificationStatus` (`PASS`/`REVIEW`/`FAIL`), `PodVerificationService`/`Impl`
- [x] `PodVerificationProvider` abstraction + `HeuristicPodVerificationProvider` — honest
      deterministic local scorer (no AI vendor credential in this dev environment),
      not coupled to one provider; a real vision/OCR provider is a second implementation
- [x] Image-quality scoring via `ImageIO` (darkness/blur/resolution), signature presence,
      AWB cross-check against the platform's own DB record, SHA-256 duplicate-POD
      detection, a weak honestly-labelled tampering signal
- [x] Configurable thresholds, never hardcoded (`POD_AUTO_VERIFY_THRESHOLD`/
      `POD_MANUAL_REVIEW_THRESHOLD`, defaults 85/60)
- [x] `pod.ai.enabled=false` -> `UnavailablePodVerificationProvider`: provider-unavailable
      always routes to `REVIEW`, never a silent `PASS`
- [x] `POST /shipments/{id}/pod/verify`, `GET .../pod/verification`, `POST .../pod/review`,
      plus `GET /pod/pending-review` (added beyond the brief's own list — the reviewer
      worklist)
- [x] RBAC role-based like every module since Ticket Support; no new permission-catalogue
      rows (same precedent as Ticket/Follow-up/Vehicle-fleet)
- [x] Frontend: `delivery.ts` reworked into capture -> AI verify -> decision flow; new
      `pod-review.ts` Manual Review screen; new nav leaf "POD Review"
- [x] Found and fixed a real pre-existing platform bug: `GlobalExceptionHandler` 500'd on
      a missing multipart part instead of a clean 400
- [x] 22 new backend unit tests (`mvn test` 813 → 835), `tsc`/`ng build` clean
- [x] Verified live over real HTTP against real `courier_db` (business rules, refusals,
      isolation, the AI pipeline itself confirmed reaching and executing) — see the module
      doc's Verified-live section for exactly what was and wasn't covered
- [ ] The full PASS/REVIEW/FAIL happy path not exercised live — no S3/file-storage backend
      configured in this dev environment (accepted pre-existing gap, not new here)
- [ ] Delivery/POD-Review click paths not exercised live — no `OUT_FOR_DELIVERY` fixture
      existed at the logged-in test branch this session
- [ ] `deliver()` itself does not server-side-require a `PASS` verification to exist — the
      gate is frontend-level by design, deliberately not coupling `ShipmentServiceImpl` to
      the new `pod` module; see the module doc's "Deliberately not built"

## Communication Center  `[x]`  DONE (v0.31.0, `V50`)

> New package `com.courier.modules.communication`. Event-driven WhatsApp/SMS/Email —
> `ShipmentServiceImpl` publishes plain `ShipmentEvent` records, never sends anything itself.
> See `MEMORY/modules/communication.md`.

- [x] `communication_template`/`communication_setting`/`communication_log` (all company-owned),
      `customers` gained `whatsapp_enabled`/`sms_enabled`/`email_enabled` (default `TRUE`)
- [x] Two deliberately separate on/off switches: `communication_setting.enabled` (channel
      master switch) vs. `communication_template.status` (per-event-per-channel switch) —
      resolves a real contradiction between the brief's own DB schema and Default-Events
      sections
- [x] `WhatsAppProvider`/`SmsProvider`/`EmailProvider` abstractions, each a `LogOnly*` default
      + a real implementation (`MetaWhatsAppProvider`/`GenericHttpSmsProvider`/
      `SmtpEmailProvider`) gated by an explicit `app.communication.<channel>.enabled` property,
      mirroring `PaymentGatewayConfig`'s own two-explicit-conditions shape
- [x] `ShipmentEvent` gained six plain-scalar records (`Booked`/`Dispatched`/`ReceivedAtBranch`/
      `OutForDelivery`/`Delivered`/`Cancelled`), published from `ShipmentServiceImpl`'s six
      existing call sites; `ShipmentCommunicationListener` (`AFTER_COMMIT`+`REQUIRES_NEW`) is
      the only place an event becomes a communication attempt
- [x] `CommunicationDispatchJob` (`@Scheduled`, cross-tenant sweep) — the "ready for Kafka"
      event abstraction the brief asked for, since no Kafka dependency exists in this repo
- [x] `ShipmentDirectoryPort`/`CommunicationShipmentDirectoryAdapter` goes straight to
      repositories, never a `@PreAuthorize`-guarded service method — the dispatch job's
      scheduler thread carries no authenticated caller
- [x] Secrets AES-256-GCM via the same `EncryptedStringConverter` `CompanyRazorpayConfig` (V46)
      already uses; never returned by any API response
- [x] 14 endpoints across four controllers; RBAC role-based like every module since Ticket
      Support (no new `PermissionModule`/`PermissionAction` rows)
- [x] Frontend: Dashboard, Channel Settings, Templates (+ preview/enable-disable dialog), Logs
      (+ Retry Failed), Shipment Details Communication tab, Customer preference checkboxes
- [x] 36 new backend unit tests (`mvn test` 835 → 871), 11 new frontend tests (`ng test`
      134 → 145); verified fully live end to end on real `courier_db` (booking → queue →
      dispatch → SENT, cancellation → correctly-cancelled-for-no-template, RBAC 403,
      test-connection, template preview, customer preference defaults)
- [ ] `RTO_INITIATED`/`RTO_DELIVERED` declared, never published — no return-to-origin flow
      exists in this codebase yet; a future RTO module publishes into these with no schema
      change here
- [ ] `DELIVERED` status modelled, never reached — no provider delivery-receipt webhook exists
      yet for any channel
- [ ] A genuine `FAILED`/retry cycle not exercised live — no real vendor credentials in this
      dev environment to force a provider failure; covered by unit tests instead

## Phase 4 — Organization Structure: Hub, Serviceability  `[ ]`  <- **NEXT**

> Takes migration **`V17`+** (`V16` used by Rate Master). Hub is almost certainly the
> Branch module's shape (`users.hub_id` already exists; HUB_MANAGER mirrors
> BRANCH_MANAGER). Not started.

- [ ] `Hub` entity + CRUD + HUB_MANAGER scoping (reuse Branch as the template)
- [ ] Serviceable pincode registry (pincode -> branch), the hot booking lookup —
      the pincode master and its `serviceable` flag now exist; what is missing is the
      pincode-to-branch assignment
- [ ] **Cross-company leak test** per company-owned repository

## Phase 3 (carryover) — Authorise on Permissions  `[ ]`

> The capstone that makes roles/permissions load-bearing. Deferred while Phase 4 proceeds.

- [ ] Resolve a user's effective permission codes from `user_company_roles` ->
      `RolePermissionService.resolveEffectiveCodes`
- [ ] Put codes into the security context (JWT authorities or per-request), cache them
- [ ] Switch company-module `@PreAuthorize` from `hasRole(...)` to `hasAuthority(<CODE>)`
- [ ] Decide the JWT end-state vs auth's `Role` enum authorities
- [ ] Token revocation on user lock/deactivate/delete, via `UserEventListener`
- [ ] Role-deletion semantics for holders; `users.tenant_id -> companies.tenant_id` FK

---

## Cross-Cutting / Tech Debt

- [ ] Testcontainers harness + shared `AbstractIntegrationTest`
- [ ] **Cross-company leak test suite** — one per company-owned repository
- [ ] Rate limiting filter (Redis token bucket)
- [ ] Idempotency keys on booking endpoints
- [ ] Structured JSON logging for prod profile
- [ ] CI: build + test + Flyway dry-run on PR
- [ ] `mvnw` wrapper committed
- [ ] Secrets moved to a real secret manager (currently env vars)
- [ ] Audit log retention/archival job (1 year, then cold storage)
- [ ] OpenAPI contract snapshot test to catch breaking changes
- [ ] **Fix `AuthServiceTest.companyRequired`** — a stale assertion, not a bug. It expects
      `ForbiddenException("companyId is required")` for a login with neither companyId nor slug,
      but that path now falls through to `AuthService.resolvePlatformCompany` and returns
      `401 INVALID_CREDENTIALS`, which is the intended behaviour. Fix the test, not the code

## Known Gaps in the Foundation (accepted for now)

| Gap | Why accepted | When to fix |
|---|---|---|
| ~~No `UserDetailsService`~~ | Delivered in Phase 2 | done |
| ~~No refresh-token store~~ | Delivered in Phase 2 (MySQL-backed) | done |
| `V1__baseline.sql` only has `audit_logs` | Business tables ship with their modules | ongoing |
| ~~Rate limiting not wired~~ | Login throttle delivered; general-purpose filter still open | partial |
| No FK on `users.tenant_id` | One orphan row in the dev database | next migration |
| ~~`LoginRequest.companyCode` rejected~~ | Delivered with `CompanyDirectory` | done |
| Suspension enforced at login only | Needs `CompanyStatusGuard` on every request | before prod |
| Denylist fails open when Redis is down | Availability over a 15-min revocation window | accepted |
| No `NotificationPort` impl in prod | Context fails fast until SMTP is wired — deliberate | before prod |
| Company filter enabled per-request, not per-session-factory | Simpler; revisit if async/`@Async` reads appear | When background jobs land |
